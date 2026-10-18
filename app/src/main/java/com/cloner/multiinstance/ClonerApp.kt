package com.cloner.multiinstance

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Build
import com.cloner.multiinstance.engine.CloneContext
import com.cloner.multiinstance.engine.CrashReporter
import com.cloner.multiinstance.engine.HookDiagnostics
import com.cloner.multiinstance.engine.PendingCloneStore
import com.cloner.multiinstance.engine.TargetActivityContext
import com.cloner.multiinstance.engine.TargetClassLoaders
import com.cloner.multiinstance.engine.hook.VirtualInstrumentation
import com.cloner.multiinstance.engine.hook.ActivityThreadHook

class ClonerApp : Application() {

    /**
     * :clone process میں target ایپ کے resources۔ یہ process ہماری اپنی UI
     * کبھی نہیں چلاتا — اس میں صرف target ایپ چلتی ہے، اس لیے application
     * level پر resources بدلنا محفوظ ہے۔
     */
    private var targetResources: Resources? = null

    override fun getResources(): Resources = targetResources ?: super.getResources()

    override fun attachBaseContext(base: Context) {
        CrashReporter.install(base)
        val hookOk = VirtualInstrumentation.install()
        // VirtualApp-style H-handler hook: swaps the target's real ActivityInfo
        // into the LaunchActivityItem BEFORE performLaunchActivity, so the
        // correct theme is applied at Activity.attach() time (primary mechanism;
        // the instrumentation themeFix stays as backstop).
        val hHookOk = ActivityThreadHook.install(base)
        HookDiagnostics.record(base, "H-hook installed=$hHookOk")
        val effective: Context = if (isCloneProcess()) {
            HookDiagnostics.record(base, "clone process start; instrumentation hook installed=$hookOk")
            // Application-level resources کو target ایپ کے resources سے بدلیں —
            // target ایپ اکثر application context سے drawables مانگتی ہے۔
            val targetPkg = PendingCloneStore.getPackageName(base)
            if (targetPkg != null) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val targetCtx = base.createPackageContext(
                        targetPkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                    )
                    targetResources = targetCtx.resources
                    HookDiagnostics.record(base, "application resources redirected to $targetPkg")
                }.onFailure {
                    HookDiagnostics.record(
                        base,
                        "application resources redirect FAILED: ${it.javaClass.simpleName}: ${it.message}"
                    )
                }
            } else {
                HookDiagnostics.record(base, "no pending package name; application resources NOT redirected")
            }
            val cloneId = PendingCloneStore.getCloneId(base)
            val res = targetResources
            // Boot the target app's own Application class. The clone process's real Application
            // is ClonerApp, so the target's Application.onCreate() never runs — and target code
            // depending on its statics (e.g. Facebook's AppContext.set) crashes with
            // IllegalStateException. Boot it here, before any activity exists.
            if (targetPkg != null && cloneId != null && res != null) {
                val targetAppInit = runCatching {
                    @Suppress("DEPRECATION")
                    val appInfo = base.packageManager.getApplicationInfo(targetPkg, 0)
                    val appClassName = appInfo.className
                    if (appClassName.isNullOrEmpty()) {
                        HookDiagnostics.record(base, "targetApp: default android.app.Application — nothing to boot")
                    } else {
                        HookDiagnostics.record(base, "targetApp: booting $appClassName")
                        // SHARED per-APK loader (same cache as newActivity) — statics are per-ClassLoader.
                        val cl = TargetClassLoaders.of(appInfo.sourceDir, javaClass.classLoader)
                        // Discover SoLoader (possibly obfuscated class name) BEFORE boot: fast path
                        // standard name, slow path scan primary dex for com.facebook.soloader.* with sSoSources.
                        val soLoaderClass: Class<*>? = runCatching {
                            try {
                                Class.forName("com.facebook.soloader.SoLoader", false, cl)
                            } catch (e: ClassNotFoundException) {
                                var found: Class<*>? = null
                                @Suppress("DEPRECATION")
                                val dexFile = dalvik.system.DexFile(appInfo.sourceDir)
                                try {
                                    val entries = dexFile.entries()
                                    while (entries.hasMoreElements() && found == null) {
                                        var name = entries.nextElement()
                                        if (name.startsWith("L") && name.endsWith(";")) {
                                            name = name.substring(1, name.length - 1).replace('/', '.')
                                        }
                                        if (name.startsWith("com.facebook.soloader.") && !name.contains("$")) {
                                            try {
                                                val c = Class.forName(name, false, cl)
                                                if (c.declaredFields.any { it.name == "sSoSources" }) found = c
                                            } catch (t: Throwable) { }
                                        }
                                    }
                                } finally {
                                    runCatching { dexFile.close() }
                                }
                                found ?: throw ClassNotFoundException("obfuscated SoLoader not found in primary dex")
                            }
                        }.getOrNull()
                        if (soLoaderClass != null) {
                            // init() is idempotent (first call wins): init now with target's lib dir,
                            // so the target's later init() becomes a no-op and sources cover target .so files.
                            val initOk = runCatching {
                                val targetAppInfo = base.packageManager.getApplicationInfo(targetPkg, 0)
                                val fakeCtx = object : ContextWrapper(base) {
                                    override fun getApplicationInfo(): ApplicationInfo = targetAppInfo
                                }
                                val m = try {
                                    soLoaderClass.getMethod("init", Context::class.java, Int::class.javaPrimitiveType)
                                } catch (e: NoSuchMethodException) {
                                    soLoaderClass.declaredMethods.first {
                                        java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                                        it.parameterTypes.size == 2 &&
                                        it.parameterTypes[0] == Context::class.java &&
                                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                                    }.apply { isAccessible = true }
                                }
                                m.invoke(null, fakeCtx, 0)
                            }
                            initOk.exceptionOrNull()?.let {
                                HookDiagnostics.record(base, "soloader: init FAILED: " + it.javaClass.simpleName + ": " + it.message)
                            } ?: HookDiagnostics.record(base, "soloader: inited (" + soLoaderClass.name + ") with target lib dir")
                        } else {
                            HookDiagnostics.record(base, "soloader: class not found, skipped")
                        }
                        // Single clean boot attempt (no polluting attempt-1 anymore).
                        val bootOk = runCatching {
                            val appCtx = TargetActivityContext(base, cloneId, res)
                            val app = android.app.Instrumentation().newApplication(cl, appClassName, appCtx)
                            app.onCreate()
                        }
                        bootOk.exceptionOrNull()?.let {
                            HookDiagnostics.record(base, "targetApp: FAILED: " + it.javaClass.simpleName + ": " + it.message)
                        } ?: HookDiagnostics.record(base, "targetApp: newApplication OK; onCreate OK")
                    }
                }
                targetAppInit.exceptionOrNull()?.let {
                    HookDiagnostics.record(
                        base,
                        "targetApp: FAILED: " + it.javaClass.simpleName + ": " + it.message
                    )
                }
            } else {
                HookDiagnostics.record(
                    base,
                    "targetApp: SKIPPED (targetPkg=$targetPkg cloneId=$cloneId resources=${res != null})"
                )
            }
            if (cloneId != null) CloneContext(base, cloneId) else base
        } else {
            base
        }
        super.attachBaseContext(effective)
    }

    private fun isCloneProcess(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName().endsWith(":clone")
        } else {
            false
        }
    }.getOrDefault(false)
}
