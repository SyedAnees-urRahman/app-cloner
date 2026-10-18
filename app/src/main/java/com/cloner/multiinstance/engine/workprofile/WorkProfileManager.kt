package com.cloner.multiinstance.engine.workprofile

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.content.pm.LauncherApps
import com.cloner.multiinstance.MainActivity
import com.cloner.multiinstance.model.InstalledApp
import java.io.File
import java.io.FileInputStream

/**
 * Work Profile mode (Island/Shelter method): real app installs inside a
 * managed work profile, fully isolated by Android itself. No hooking.
 *
 * Setup (one-time, needs a PC):
 *   adb shell dpm set-profile-owner com.cloner.multiinstance/.engine.workprofile.CloneDeviceAdminReceiver
 */
object WorkProfileManager {

    fun adbCommand(): String =
        "adb shell dpm set-profile-owner com.cloner.multiinstance/.engine.workprofile.CloneDeviceAdminReceiver"

    /** True if this app is the profile owner of a managed profile. */
    fun isProvisioned(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        dpm.isProfileOwnerApp(context.packageName)
    }.getOrDefault(false)

    /** The managed (work) profile's user handle, or null if not provisioned. */
    fun getWorkProfileUser(context: Context): UserHandle? = runCatching {
        if (!isProvisioned(context)) return null
        val um = context.getSystemService(UserManager::class.java) ?: return null
        val myUser = Process.myUserHandle()
        um.userProfiles.firstOrNull { it != myUser }
    }.getOrNull()

    fun onProvisioningComplete(context: Context) {
        // Hook point: could enable launcher icon etc. Nothing required.
    }

    /** Context bound to the work-profile user (for PackageInstaller/queries). */
    private fun workContext(context: Context): Context? {
        val workUser = getWorkProfileUser(context) ?: return null
        return runCatching<Context> {
            // createPackageContextAsUser is @hide in the SDK — call via reflection.
            val m = Context::class.java.getMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                UserHandle::class.java
            )
            m.invoke(context, context.packageName, 0, workUser) as Context
        }.getOrNull()
    }

    /**
     * Installs an APK file into the work profile via PackageInstaller.
     * Calls [onResult] on completion (true = commit accepted; verify via
     * [getWorkProfileApps] since commit is async).
     */
    fun installApkToWorkProfile(
        context: Context,
        apkPath: String,
        onResult: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val wctx = workContext(context)
                    ?: return@Thread onResult(false, "Work profile not ready")
                val installer = wctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                try {
                    FileInputStream(apkPath).use { input ->
                        session.openWrite("app", 0, -1).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val callbackIntent = Intent(wctx, MainActivity::class.java)
                    val pi = PendingIntent.getActivity(
                        wctx, sessionId, callbackIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    session.commit(pi.intentSender)
                    onResult(true, "Install started — check the work tab launcher")
                } finally {
                    runCatching { session.close() }
                }
            } catch (t: Throwable) {
                onResult(false, t.javaClass.simpleName + ": " + (t.message ?: ""))
            }
        }.apply { isDaemon = true }.start()
    }

    /** Apps currently installed in the work profile (user apps). */
    fun getWorkProfileApps(context: Context): List<InstalledApp> = runCatching {
        val wctx = workContext(context) ?: return emptyList()
        val pm = wctx.packageManager
        @Suppress("DEPRECATION")
        val pkgs = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        pkgs.filter { pi ->
            val ai = pi.applicationInfo ?: return@filter false
            // User-installed apps only (not system), and not ourselves
            ai.packageName != context.packageName &&
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }.map { pi ->
            val ai = pi.applicationInfo!!
            InstalledApp(
                appName = runCatching { pm.getApplicationLabel(ai).toString() }
                    .getOrDefault(ai.packageName),
                packageName = ai.packageName
            )
        }.sortedBy { it.appName.lowercase() }
    }.getOrDefault(emptyList())

    /** Launches an app inside the work profile. */
    fun launchWorkApp(context: Context, packageName: String): Boolean = runCatching {
        val workUser = getWorkProfileUser(context) ?: return false
        val wctx = workContext(context) ?: return false
        val launchIntent = wctx.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        val launcherApps = context.getSystemService(LauncherApps::class.java)
            ?: return false
        val component = launchIntent.component ?: return false
        launcherApps.startMainActivity(component, workUser, null, null)
        true
    }.getOrDefault(false)

    /** Uninstalls an app from the work profile. */
    fun uninstallWorkApp(context: Context, packageName: String, onResult: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                val wctx = workContext(context) ?: return@runCatching false
                val installer = wctx.packageManager.packageInstaller
                val callbackIntent = Intent(wctx, MainActivity::class.java)
                val pi = PendingIntent.getActivity(
                    wctx, 0, callbackIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                installer.uninstall(packageName, pi.intentSender)
                true
            }.getOrDefault(false)
            onResult(ok)
        }.apply { isDaemon = true }.start()
    }

    /** APK path (base.apk) of an installed app, for cloning into the profile. */
    fun apkPathOf(context: Context, packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(packageName, 0).sourceDir
    }.getOrNull()?.takeIf { File(it).exists() }
}
