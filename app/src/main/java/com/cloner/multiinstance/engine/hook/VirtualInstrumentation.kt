package com.cloner.multiinstance.engine.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import java.lang.reflect.Field
import com.cloner.multiinstance.engine.HookDiagnostics
import com.cloner.multiinstance.engine.PendingCloneStore
import com.cloner.multiinstance.engine.StorageSandbox
import com.cloner.multiinstance.engine.TargetActivityContext
import com.cloner.multiinstance.engine.TargetAppContext
import com.cloner.multiinstance.engine.TargetClassLoaders

object VirtualInstrumentation {
    const val EXTRA_TARGET = "com.cloner.multiinstance.TARGET"
    const val EXTRA_CLONE_ID = "com.cloner.multiinstance.CLONE_ID"
    const val EXTRA_APK_PATH = "com.cloner.multiinstance.APK_PATH"

    @Volatile
    private var installed = false

    /**
     * Installs the hook by replacing ActivityThread's Instrumentation.
     * Only overrides newActivity/newApplication (both public API) — the
     * stub redirection itself is done by CloneEngine, which launches the
     * manifest-declared StubActivity directly with the real intent inside.
     */
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val f = atClass.getDeclaredField("mInstrumentation").apply { isAccessible = true }
            val base = f.get(currentAt) as Instrumentation
            if (base is HookedInstrumentation) return@runCatching true
            f.set(currentAt, HookedInstrumentation(base))
            installed = true
            true
        }.getOrDefault(false)
    }

    private class HookedInstrumentation(private val base: Instrumentation) : Instrumentation() {

        companion object {
            /**
             * Hands the target Intent from newActivity to callActivityOnCreate.
             * Both run sequentially on the main thread inside performLaunchActivity,
             * so this is race-free. Re-reading EXTRA_TARGET from activity.intent
             * proved unreliable (framework may not deliver the same Intent object).
             * Always assigned in newActivity (even null) so a stale value can never
             * leak into an unrelated launch.
             */
            @Volatile
            private var pendingTarget: Intent? = null
        }

        @Suppress("DEPRECATION")
        override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
            val target = intent?.getParcelableExtra<Intent>(EXTRA_TARGET)
            pendingTarget = target
            if (target != null) {
                // apkPath lives on the nested target intent; fall back to the stub
                // intent itself so a misplaced extra can never crash here again.
                val apkPath = target.getStringExtra(EXTRA_APK_PATH)
                    ?: intent?.getStringExtra(EXTRA_APK_PATH)
                    ?: error("APK path missing")
                val targetClass = target.component?.className
                    ?: error("Target component missing")
                // Resolve activity alias: <activity-alias> entries (e.g. com.opera.Opera)
                // declare no loadable class; ActivityInfo.targetActivity names the real
                // one (e.g. com.opera.android.BrowserActivity). The H-hook already
                // resolved it for the framework's ActivityInfo; resolve it here too
                // because this method loads the class from the intent's component.
                val resolvedClass = runCatching {
                    val hostCtx = getHostContext() ?: return@runCatching targetClass
                    val comp = target.component ?: return@runCatching targetClass
                    val ai = hostCtx.packageManager.getActivityInfo(comp, 0)
                    ai.targetActivity ?: targetClass
                }.getOrDefault(targetClass)
                if (resolvedClass != targetClass) {
                    getHostContext()?.let {
                        HookDiagnostics.record(
                            it,
                            "newActivity: activity alias " + targetClass + " -> real " + resolvedClass
                        )
                    }
                }
                // Load the target APK's activity class with the SHARED per-APK ClassLoader
                // (TargetClassLoaders.of) — the target Application booted in ClonerApp uses the
                // same loader, which is REQUIRED: statics are per-ClassLoader.
                return base.newActivity(TargetClassLoaders.of(apkPath, cl), resolvedClass, target)
            }
            return base.newActivity(cl, className, intent)
        }

        override fun newApplication(
            cl: ClassLoader,
            className: String,
            context: Context
        ): Application {
            // The framework builds `context` from the target's ApplicationInfo (our H-hook
            // swapped the ActivityInfo), so its dataDir is /data/user/0/<targetPkg> which
            // our UID cannot write. Wrap with storage redirection BEFORE the target
            // Application's attachBaseContext runs, or it crashes on first file write
            // (e.g. Facebook's AuraLacrimaConfig -> FileNotFoundException ENOENT).
            // Our own ClonerApp (hostPkg) is never wrapped.
            val wrapped = runCatching {
                val hostCtx = getHostContext()
                // Resolve target info: prefer the H-hook's published target (it runs
                // moments earlier on this same main thread, so it works in the fresh
                // :clone process where PendingCloneStore is empty). Fall back to
                // PendingCloneStore, then to the framework context's package.
                val targetPkg = PendingTarget.targetPkg
                    ?: hostCtx?.let { PendingCloneStore.getPackageName(it) }
                    ?: context.packageName
                val cloneId = PendingTarget.cloneId
                    ?: hostCtx?.let { PendingCloneStore.getCloneId(it) }
                val hostPkg = hostCtx?.packageName
                if (hostCtx != null && cloneId != null && targetPkg != null && targetPkg != hostPkg) {
                    // Java-level file I/O redirect (v2.13): some apps build HARDCODED absolute
                    // paths (/data/user/0/<pkg>/...) and open them directly, bypassing the
                    // ContextWrapper above. Replace Libcore.os BEFORE attachBaseContext runs.
                    // Same sandbox root as TargetAppContext so both redirects stay consistent.
                    OsRedirectHook.install(
                        hostCtx,
                        targetPkg,
                        StorageSandbox.rootOf(hostCtx, cloneId)
                    )
                    val wrappedCtx = TargetAppContext(context, hostCtx, cloneId)
                    HookDiagnostics.record(
                        context,
                        "newApplication: engaging storage hooks for " + targetPkg
                    )
                    wrappedCtx
                } else {
                    HookDiagnostics.record(
                        context,
                        "newApplication: storage hooks SKIPPED (targetPkg=" + targetPkg +
                            " cloneId=" + cloneId + " hostPkg=" + hostPkg + ")"
                    )
                    context
                }
            }.getOrDefault(context)
            val app = base.newApplication(cl, className, wrapped)
            // v2.27: Facebook's AuraApplication launches background startup jobs during
            // onCreate() that need the static AppContext holder initialized via
            // AppContext.set(). In the cloned environment this init is skipped, causing
            // "AppContext.set has not been invoked" on Aura-IO threads. Invoke the
            // obfuscated static set(Context) manually BEFORE onCreate() runs (the
            // framework calls onCreate later via callApplicationOnCreate).
            initTargetAppContext(wrapped, app)
            return app
        }

        /**
         * v2.27: Manually initializes the target app's static AppContext holder.
         * The throwing method from on-device stack traces is X.6VG.A01; X.6VG is the
         * AppContext holder and A01 is its get(). We find its static set(Context)
         * method and invoke it with the Application instance.
         */
        private fun initTargetAppContext(context: Context, app: Application) {
            runCatching {
                // Use the Application's own classloader: it loaded AuraApplication,
                // so it can load X.6VG from the same APK/obfuscation.
                val classLoader = app.classLoader
                var invoked = false
                runCatching {
                    val holder = Class.forName("X.6VG", false, classLoader)
                    val setMethod = holder.declaredMethods.firstOrNull {
                        java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                            it.parameterTypes.size == 1 &&
                            Context::class.java.isAssignableFrom(it.parameterTypes[0])
                    }
                    if (setMethod != null) {
                        setMethod.isAccessible = true
                        setMethod.invoke(null, app)
                        HookDiagnostics.record(
                            context,
                            "appctx: invoked X.6VG." + setMethod.name + "(application) OK"
                        )
                        invoked = true
                    } else {
                        HookDiagnostics.record(
                            context,
                            "appctx: X.6VG found but no static set(Context) method"
                        )
                    }
                }.onFailure {
                    HookDiagnostics.record(context, "appctx: X.6VG lookup failed: " + it.message)
                }
                if (!invoked) {
                    HookDiagnostics.record(context, "appctx: FAILED to invoke AppContext.set")
                }
            }.onFailure {
                HookDiagnostics.record(context, "appctx: exception: " + it.message)
            }
        }

        /** Our ClonerApp instance (already created when a target Application boots). */
        private fun getHostContext(): Context? = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            atClass.getDeclaredMethod("getApplication").invoke(currentAt) as Context
        }.getOrNull()

        override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
            // Prefer the static handoff from newActivity; fall back to the intent extra.
            @Suppress("DEPRECATION")
            val fromStatic = pendingTarget.also { pendingTarget = null }
            @Suppress("DEPRECATION")
            val fromIntent = activity.intent?.getParcelableExtra<Intent>(EXTRA_TARGET)
            val target = fromStatic ?: fromIntent
            HookDiagnostics.record(
                activity,
                "target source: " + when {
                    fromStatic != null -> "static"
                    fromIntent != null -> "intent-extra"
                    else -> "NONE"
                } + "; activity.intent keys=" + activity.intent?.extras?.keySet()
            )
            val cloneId = target?.getStringExtra(EXTRA_CLONE_ID)
                ?: activity.intent?.getStringExtra(EXTRA_CLONE_ID)
            val targetPkg = target?.component?.packageName
            if (cloneId != null && targetPkg != null) {
                HookDiagnostics.record(activity, "wrap: begin for $targetPkg")
                val targetRes = runCatching {
                    @Suppress("DEPRECATION")
                    activity.createPackageContext(
                        targetPkg,
                        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                    ).resources
                }
                HookDiagnostics.record(
                    activity,
                    "wrap: packageContext " + if (targetRes.isSuccess) "OK"
                    else "FAILED: " + targetRes.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + ": " + it.message }
                )
                val res = targetRes.getOrNull()
                if (res != null) {
                    // Path A: full wrap (resources + storage) via mBase swap
                    val pathA = runCatching {
                        val mBaseField = ContextWrapper::class.java
                            .getDeclaredField("mBase").apply { isAccessible = true }
                        HookDiagnostics.record(activity, "wrap: A mBase field OK")
                        val originalBase = mBaseField.get(activity) as Context
                        mBaseField.set(activity, TargetActivityContext(originalBase, cloneId, res))
                        HookDiagnostics.record(activity, "wrap: A mBase set OK")
                    }
                    // Path C: pre-fill ContextThemeWrapper's cached mResources with the target's
                    // Resources. attach() caches OUR resources before the wrap; without this,
                    // activity.getResources() keeps serving OUR table -> Resources$NotFoundException.
                    val pathC = runCatching {
                        val twRes = android.view.ContextThemeWrapper::class.java
                            .getDeclaredField("mResources").apply { isAccessible = true }
                        twRes.set(activity, res)
                        HookDiagnostics.record(activity, "wrap: C theme mResources pre-filled with target resources")
                    }
                    val aMsg = "pathA " + if (pathA.isSuccess) "OK"
                    else "FAILED: " + pathA.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + ": " + it.message }
                    val cMsg = "pathC " + if (pathC.isSuccess) "OK"
                    else "FAILED: " + pathC.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + ": " + it.message }
                    HookDiagnostics.record(activity, "wrap: $aMsg | $cMsg")
                    if (pathA.isSuccess || pathC.isSuccess)
                        HookDiagnostics.record(activity, "activity wrap OK for $targetPkg")
                    else
                        HookDiagnostics.record(activity, "activity wrap FAILED both paths for $targetPkg")
                }
                // Theme: اس Infinix Android build میں ContextThemeWrapper کا mTheme field
                // موجود نہیں (AOSP 15 میں بھی ہے)۔ تین راستے آزماتے ہیں:
                // (a) setTheme — mThemeResource اپ ڈیٹ کرتا ہے، (b) mThemeResource field
                // براہِ راست، (c) base context (TargetActivityContext) کا getTheme() override۔
                // Wrap کے بعد چلتا ہے تاکہ activity.getResources() target کے ہوں، اور
                // super.callActivityOnCreate سے پہلے۔
                val themeFix = runCatching {
                    val cls = target?.component?.className
                    if (cls == null) {
                        HookDiagnostics.record(activity, "theme: no target component, skipped")
                    } else {
                        val pm = activity.packageManager
                        val ai = pm.getActivityInfo(android.content.ComponentName(targetPkg, cls), 0)
                        val appTheme = pm.getApplicationInfo(targetPkg, 0).theme
                        var themeRes = ai.theme
                        if (themeRes == 0) themeRes = appTheme
                        if (themeRes == 0) {
                            HookDiagnostics.record(activity, "theme: target theme=0, skipped")
                        } else {
                            // (a) normal setTheme — updates mThemeResource on this Android build
                            runCatching { activity.setTheme(themeRes) }
                            // (b) direct mThemeResource field set, in case setTheme is a no-op
                            runCatching {
                                android.view.ContextThemeWrapper::class.java
                                    .getDeclaredField("mThemeResource").apply { isAccessible = true }
                                    .setInt(activity, themeRes)
                            }
                            // (c) base-context getTheme() override, in case getTheme() delegates to base
                            (activity.baseContext as? TargetActivityContext)?.let {
                                it.targetThemeRes = themeRes
                                it.targetAppThemeRes = appTheme
                                it.resetThemeCache()
                            }
                            // (d) deep diagnostic: replicate AppCompat's EXACT check.
                            val deepDiag = runCatching {
                                val apkPath = activity.packageManager.getApplicationInfo(targetPkg, 0).sourceDir
                                val cl = TargetClassLoaders.of(apkPath, javaClass.classLoader)
                                // 1. What IS 0x7f1300c2?
                                val themeName = runCatching { res?.getResourceName(themeRes) }.getOrNull()
                                HookDiagnostics.record(activity, "theme: resid 0x" + themeRes.toString(16) + " name=" + themeName)
                                // 2. mThemeResource field value (did our sets stick?)
                                val mtr = runCatching {
                                    android.view.ContextThemeWrapper::class.java.getDeclaredField("mThemeResource")
                                        .apply { isAccessible = true }.getInt(activity)
                                }.getOrNull()
                                HookDiagnostics.record(activity, "theme: mThemeResource field=0x" + (mtr?.toString(16) ?: "?"))
                                // 3. EXACT AppCompat check via target's R$styleable
                                try {
                                    val styleable = Class.forName("androidx.appcompat.R\$styleable", false, cl)
                                    val attrs = styleable.getField("AppCompatTheme").get(null) as IntArray
                                    val idx = styleable.getField("AppCompatTheme_windowActionBar").getInt(null)
                                    val ta = activity.theme.obtainStyledAttributes(attrs)
                                    val has = ta.hasValue(idx)
                                    ta.recycle()
                                    HookDiagnostics.record(activity, "theme: EXACT AppCompat check hasValue=" + has + " (index=" + idx + ", firstAttr=0x" + attrs.firstOrNull()?.toString(16) + ")")
                                    // 4. Also resolve Facebook's own windowActionBar attr directly
                                    try {
                                        val attrCls = Class.forName("androidx.appcompat.R\$attr", false, cl)
                                        val fbWA = attrCls.getField("windowActionBar").getInt(null)
                                        val tv2 = android.util.TypedValue()
                                        val hasFb = activity.theme.resolveAttribute(fbWA, tv2, true)
                                        HookDiagnostics.record(activity, "theme: fb windowActionBar 0x" + fbWA.toString(16) + " resolved=" + hasFb)
                                    } catch (e: Throwable) {
                                        HookDiagnostics.record(activity, "theme: fb attr lookup FAILED: " + e.javaClass.simpleName)
                                    }
                                } catch (e: Throwable) {
                                    HookDiagnostics.record(activity, "theme: styleable lookup FAILED: " + e.javaClass.simpleName + ": " + e.message)
                                }
                            }
                            deepDiag.exceptionOrNull()?.let {
                                HookDiagnostics.record(activity, "theme: deepDiag FAILED: " + it.javaClass.simpleName)
                            }
                            // (e) DIAGNOSTIC: is our wrapper actually in the chain? Is getTheme() delegating?
                            runCatching {
                                val baseCtx = activity.baseContext
                                HookDiagnostics.record(activity, "theme: baseContext=" + baseCtx?.javaClass?.name +
                                    " targetThemeRes=" + ((baseCtx as? TargetActivityContext)?.targetThemeRes?.toString(16) ?: "N/A"))
                                // Call the wrapper's getTheme() directly — does it build correctly?
                                val wrapperTheme = (baseCtx as? TargetActivityContext)?.let {
                                    it.targetThemeRes = themeRes  // ensure set
                                    it.getTheme()
                                }
                                val tvW = android.util.TypedValue()
                                val wrapperHasWA = wrapperTheme?.let { runCatching { it.resolveAttribute(android.R.attr.windowActionBar, tvW, true) }.getOrDefault(false) } ?: false
                                HookDiagnostics.record(activity, "theme: wrapper.getTheme() direct call hasWindowActionBar=" + wrapperHasWA)
                                // What does activity.getTheme() return? Same object?
                                val actTheme = activity.theme
                                HookDiagnostics.record(activity, "theme: activity.getTheme()==wrapperTheme? " + (actTheme === wrapperTheme))
                            }.onFailure {
                                HookDiagnostics.record(activity, "theme: diagnostic (e) FAILED: " + it.javaClass.simpleName)
                            }
                            // (f) BRUTE FORCE: seed activity.getTheme() with our HOST's working theme,
                            // then layer Facebook's theme on top. If AppCompat reads activity.getTheme(),
                            // the base attrs (windowActionBar) will be present.
                            runCatching {
                                val hostCtx = activity.applicationContext  // our host app context
                                val hostTheme = hostCtx.theme
                                val actTheme = activity.theme
                                actTheme.setTo(hostTheme)  // copy our KNOWN-GOOD base theme
                                actTheme.applyStyle(themeRes, true)  // layer Facebook's theme on top
                                val tvB = android.util.TypedValue()
                                val hasWA = runCatching { actTheme.resolveAttribute(android.R.attr.windowActionBar, tvB, true) }.getOrDefault(false)
                                HookDiagnostics.record(activity, "theme: brute-force seeded from host + 0x" + themeRes.toString(16) + " hasWindowActionBar=" + hasWA)
                            }.onFailure {
                                HookDiagnostics.record(activity, "theme: brute-force FAILED: " + it.javaClass.simpleName + ": " + it.message)
                            }
                        }
                    }
                }
                themeFix.exceptionOrNull()?.let {
                    HookDiagnostics.record(activity, "theme: FAILED: " + it.javaClass.simpleName + ": " + it.message)
                }
                // ---- diagnostic probes: which Resources actually contains the crashing drawable? ----
                val probeId = 0x7f080075 // from the Facebook crash report; diagnostic only
                fun probe(tag: String, r: android.content.res.Resources?) {
                    val result = r?.let { res ->
                        runCatching { res.getResourceName(probeId) }.fold(
                            { name -> "FOUND($name)" },
                            { e -> "NOT_FOUND(${e.javaClass.simpleName})" }
                        )
                    } ?: "null-resources"
                    HookDiagnostics.record(
                        activity,
                        "probe[$tag]: $result | class=" + (r?.javaClass?.name ?: "null")
                    )
                }
                val appRes = runCatching { (activity.applicationContext as? Application)?.resources }.getOrNull()
                probe("app-level", appRes)
                probe("activity-level", runCatching { activity.resources }.getOrNull())
                val aInfo = runCatching { activity.packageManager.getApplicationInfo(targetPkg, 0) }.getOrNull()
                HookDiagnostics.record(
                    activity,
                    "apkInfo: sourceDir=" + aInfo?.sourceDir +
                        " splits=" + aInfo?.splitSourceDirs?.toList()
                )
                val manualProbe = runCatching {
                    val am = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val m = android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                    val ai = activity.packageManager.getApplicationInfo(targetPkg, 0)
                    m.invoke(am, ai.sourceDir)
                    ai.splitSourceDirs?.forEach { m.invoke(am, it) }
                    val base = activity.resources
                    val manual = android.content.res.Resources(am, base.displayMetrics, base.configuration)
                    manual.getResourceName(probeId)
                }
                HookDiagnostics.record(
                    activity,
                    "probe[manual+splits]: " + manualProbe.fold(
                        { "FOUND($it)" },
                        { "FAILED(${it.javaClass.simpleName}: ${it.message})" }
                    )
                )
                // ---- end probes ----
            } else {
                HookDiagnostics.record(
                    activity,
                    "activity wrap SKIPPED: cloneId=$cloneId targetPkg=$targetPkg"
                )
            }
            // Hand the target app its REAL launch intent (action/categories/extras).
            // activity.getIntent() otherwise returns our stub intent (no ACTION_MAIN etc.),
            // and apps like Canva dereference it in onCreate -> NullPointerException.
            if (target != null) {
                val mIntentOk = runCatching {
                    val f = Activity::class.java.getDeclaredField("mIntent").apply { isAccessible = true }
                    f.set(activity, target)
                }
                HookDiagnostics.record(activity, "mIntent replaced with target: " + if (mIntentOk.isSuccess) "OK"
                    else "FAILED: " + mIntentOk.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message })
            }
            // (h2) v2.30: v2.28's wrapper forceAppCompatTheme is REMOVED (it built a
            // HOST-based theme lacking the target's attribute IDs, so AppCompat's
            // check still failed). v2.29's obtainStyledAttributes override did not
            // compile (final in Context) — but final means it delegates to getTheme(),
            // so the wrapper's getTheme() (target-based theme from targetResources)
            // IS the hook point. Keep only activity.setTheme() (harmless); the
            // wrapper now returns its original target-based theme.
            runCatching {
                activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
                HookDiagnostics.record(activity, "appcompat: setTheme to Theme_AppCompat_Light_NoActionBar (activity)")
            }.onFailure {
                HookDiagnostics.record(activity, "appcompat: setTheme FAILED: " + it.javaClass.simpleName + ": " + it.message)
                runCatching {
                    activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
                    HookDiagnostics.record(activity, "appcompat: setTheme fallback to AppCompat Light (activity)")
                }.onFailure { e2 ->
                    HookDiagnostics.record(activity, "appcompat: setTheme fallback FAILED: " + e2.javaClass.simpleName)
                }
            }
            // (h) CRITICAL v2.25: set Activity.mTheme directly to the wrapper's theme.
            // v2.24 on-device logs proved: activity.getTheme() != wrapperTheme.
            // Activity.getTheme() returns the Activity's OWN mTheme field
            // (ContextThemeWrapper.mTheme), NOT mBase.getTheme(). We set mBase to our
            // wrapper but never mTheme, so AppCompat's obtainStyledAttributes ran
            // against the wrong theme object. The wrapper's theme is verified good
            // (hasWindowActionBar=true). Runs BEFORE super.callActivityOnCreate and
            // BEFORE the delegate redirect below.
            runCatching {
                val wrapper = activity.baseContext as? TargetActivityContext
                if (wrapper == null) {
                    HookDiagnostics.record(activity, "mtheme: wrapper null, skipped")
                } else {
                    val wrapperTheme = wrapper.theme
                    var cls: Class<*>? = activity.javaClass
                    var mThemeField: Field? = null
                    while (cls != null && mThemeField == null) {
                        mThemeField = runCatching { cls.getDeclaredField("mTheme") }.getOrNull()
                        cls = cls.superclass
                    }
                    if (mThemeField == null) {
                        HookDiagnostics.record(activity, "mtheme: mTheme field NOT FOUND")
                    } else {
                        mThemeField.isAccessible = true
                        mThemeField.set(activity, wrapperTheme)
                        HookDiagnostics.record(activity, "mtheme: Activity.mTheme set to wrapper theme; " +
                            "activity.getTheme()===wrapperTheme? " + (activity.theme === wrapperTheme))
                    }
                }
            }.onFailure {
                HookDiagnostics.record(activity, "mtheme: set FAILED: " + it.javaClass.simpleName + ": " + it.message)
            }
            // (g) AppCompat delegate context redirect — redirect ALL Context-typed fields.
            // v2.20 redirected only X.AkD.A0l (first field holding the activity) but the
            // AppCompat theme crash PERSISTED, so the theme check must use a DIFFERENT
            // Context field (or multiple). v2.22 is aggressive: redirect EVERY
            // Context-typed field in the delegate hierarchy to our wrapper, plus scan
            // the Activity's own fields for stray Contexts. Runs BEFORE
            // super.callActivityOnCreate, so onCreate() -> getDelegate() -> findViewById()
            // -> ensureSubDecor() sees the wrapper everywhere.
            runCatching {
                val wrapper = activity.baseContext as? TargetActivityContext
                if (wrapper == null) {
                    HookDiagnostics.record(activity, "appcompat: wrapper null, skipped")
                } else {
                    val getDelegateM = runCatching { activity.javaClass.getMethod("getDelegate") }.getOrNull()
                    if (getDelegateM == null) {
                        HookDiagnostics.record(activity, "appcompat: getDelegate() not found, skipped")
                    } else {
                        val delegate = getDelegateM.invoke(activity)
                        if (delegate == null) {
                            HookDiagnostics.record(activity, "appcompat: delegate null, skipped")
                        } else {
                            // Redirect EVERY Context-typed field in the delegate hierarchy.
                            var cls: Class<*>? = delegate.javaClass
                            var count = 0
                            val redirectedFields = mutableListOf<String>()
                            while (cls != null) {
                                for (f in cls.declaredFields) {
                                    if (android.content.Context::class.java.isAssignableFrom(f.type)) {
                                        runCatching {
                                            f.isAccessible = true
                                            val oldVal = f.get(delegate)
                                            if (oldVal !== wrapper && oldVal is android.content.Context) {
                                                f.set(delegate, wrapper)
                                                count++
                                                redirectedFields.add(cls.name + "." + f.name)
                                            }
                                        }
                                    }
                                }
                                cls = cls.superclass
                                if (cls == Any::class.java) break
                            }
                            HookDiagnostics.record(activity, "appcompat: redirected " + count +
                                " Context fields to wrapper: " + redirectedFields.joinToString(", "))

                            // ALSO: scan the Activity's own fields for stray Contexts
                            // (not the activity itself, only OTHER contexts).
                            var acls: Class<*>? = activity.javaClass
                            var acount = 0
                            while (acls != null && acls != android.app.Activity::class.java) {
                                for (f in acls.declaredFields) {
                                    if (android.content.Context::class.java.isAssignableFrom(f.type)) {
                                        runCatching {
                                            f.isAccessible = true
                                            val oldVal = f.get(activity)
                                            if (oldVal !== wrapper && oldVal is android.content.Context && oldVal !== activity) {
                                                f.set(activity, wrapper)
                                                acount++
                                            }
                                        }
                                    }
                                }
                                acls = acls.superclass
                                if (acls == Any::class.java) break
                            }
                            if (acount > 0) {
                                HookDiagnostics.record(activity, "appcompat: redirected " + acount +
                                    " additional Context fields in Activity")
                            }
                        }
                    }
                }
            }.onFailure {
                HookDiagnostics.record(activity, "appcompat: redirect FAILED: " + it.javaClass.simpleName + ": " + it.message)
            }
            super.callActivityOnCreate(activity, icicle)
        }
    }
}
