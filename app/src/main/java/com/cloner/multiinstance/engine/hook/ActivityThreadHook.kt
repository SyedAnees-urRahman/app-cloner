package com.cloner.multiinstance.engine.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Message
import com.cloner.multiinstance.engine.HookDiagnostics

/**
 * Published by the H-hook when it swaps a LaunchActivityItem's ActivityInfo.
 * HookedInstrumentation.newApplication runs moments later on the same main
 * thread, in the same :clone process — this is how it learns the
 * targetPkg/cloneId even though the :clone process was forked fresh and
 * PendingCloneStore may be empty there.
 */
object PendingTarget {
    @Volatile
    var targetPkg: String? = null

    @Volatile
    var cloneId: String? = null
}

/**
 * VirtualApp/DroidPlugin-style interception: hooks ActivityThread's H-handler
 * (mH) with a Handler.Callback and intercepts EXECUTE_TRANSACTION (159) on
 * Android 9+. The msg.obj is a android.app.servertransaction.ClientTransaction;
 * its getCallbacks() list contains the LaunchActivityItem with private fields
 * mIntent and mInfo (ActivityInfo). Swapping mInfo to the target's REAL
 * ActivityInfo BEFORE the framework handles the message means
 * Activity.attach() reads the correct theme at launch time — the primary
 * mechanism; VirtualInstrumentation's post-hoc themeFix stays as backstop.
 */
object ActivityThreadHook {
    private const val EXECUTE_TRANSACTION = 159
    const val HOST_PACKAGE = "com.cloner.multiinstance"

    @Volatile
    private var installed = false

    fun install(appContext: Context): Boolean {
        if (installed) return true
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val mH = atClass.getDeclaredField("mH").apply { isAccessible = true }
                .get(currentAt) as Handler
            val mCallbackF = Handler::class.java.getDeclaredField("mCallback")
                .apply { isAccessible = true }
            val existing = mCallbackF.get(mH) as? Handler.Callback
            val pm = appContext.packageManager
            mCallbackF.set(mH, Handler.Callback { msg: Message ->
                if (msg.what == EXECUTE_TRANSACTION) {
                    handleExecuteTransaction(msg.obj, pm, appContext)
                }
                existing?.handleMessage(msg) ?: false
            })
            installed = true
            HookDiagnostics.record(appContext, "H-hook installed: EXECUTE_TRANSACTION intercept active")
            true
        }.getOrDefault(false)
    }

    private fun handleExecuteTransaction(obj: Any?, pm: PackageManager, ctx: Context) {
        runCatching {
            // obj = android.app.servertransaction.ClientTransaction
            val callbacks = obj?.javaClass?.getMethod("getCallbacks")?.invoke(obj) as? List<*>
                ?: return
            for (item in callbacks) {
                val cls = item?.javaClass ?: continue
                if (!cls.name.endsWith("LaunchActivityItem")) continue
                val mIntentF = cls.getDeclaredField("mIntent").apply { isAccessible = true }
                val intent = mIntentF.get(item) as? android.content.Intent ?: continue
                @Suppress("DEPRECATION")
                val target = intent.getParcelableExtra<android.content.Intent>(
                    VirtualInstrumentation.EXTRA_TARGET
                ) ?: continue
                val component = target.component ?: continue
                val targetInfo: ActivityInfo = pm.getActivityInfo(component, 0)
                // Resolve activity alias: an <activity-alias> (e.g. com.opera.Opera)
                // has no loadable class of its own; ActivityInfo.targetActivity names
                // the real class (e.g. com.opera.android.BrowserActivity).
                val realClassName = targetInfo.targetActivity ?: targetInfo.name
                if (targetInfo.targetActivity != null) {
                    HookDiagnostics.record(
                        ctx,
                        "H-hook: activity alias " + targetInfo.name + " -> real " + realClassName
                    )
                }
                // Hybrid: keep the target's theme/config/behavior, but attribute
                // to the HOST package so the framework-built ContextImpl (created
                // during attach(), before our wrapper is installed) passes UID
                // checks for system-service calls (e.g. Settings in PhoneWindow).
                // Our TargetActivityContext (installed in callActivityOnCreate)
                // restores getPackageName()=target for app logic and
                // getTheme()=target theme for AppCompat.
                val frameworkInfo = ActivityInfo(targetInfo).apply {
                    name = realClassName
                    packageName = HOST_PACKAGE
                    val ai = applicationInfo
                    if (ai != null) {
                        applicationInfo = android.content.pm.ApplicationInfo(ai).apply {
                            packageName = HOST_PACKAGE
                        }
                    }
                }
                // Try mInfo, fall back to mActivityInfo (OEM variance)
                val mInfoF = runCatching { cls.getDeclaredField("mInfo") }
                    .getOrElse { cls.getDeclaredField("mActivityInfo") }
                    .apply { isAccessible = true }
                mInfoF.set(item, frameworkInfo)
                HookDiagnostics.record(
                    ctx,
                    "H-hook: swapped ActivityInfo to " + component.className +
                        " (theme=0x" + targetInfo.theme.toString(16) +
                        ", pkg=host for attribution)"
                )
                // Publish for newApplication (runs moments later, same thread).
                PendingTarget.targetPkg = component.packageName
                @Suppress("DEPRECATION")
                PendingTarget.cloneId = target.getStringExtra(
                    VirtualInstrumentation.EXTRA_CLONE_ID
                )
                HookDiagnostics.record(
                    ctx,
                    "H-hook: pending target=" + component.packageName +
                        " cloneId=" + PendingTarget.cloneId
                )
            }
        }.onFailure {
            HookDiagnostics.record(ctx, "H-hook: handle FAILED: " + it.javaClass.simpleName)
        }
    }
}
