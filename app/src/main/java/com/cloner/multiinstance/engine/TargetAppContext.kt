package com.cloner.multiinstance.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * Wraps the target app's Application context: resources/packageName/etc. stay the
 * target's, but ALL file storage is redirected into our per-clone sandbox.
 *
 * Our :clone process runs as com.cloner.multiinstance's UID and cannot write to
 * /data/user/0/<targetPkg>/ — without this, the target Application's
 * attachBaseContext crashes (e.g. Facebook's AuraLacrimaConfig writing
 * app_errorreporting/.../state.txt -> FileNotFoundException ENOENT).
 *
 * Uses the same sandbox root as CloneContext (StorageSandbox) so the Application
 * and its activities see consistent storage.
 */
class TargetAppContext(
    targetCtx: Context,
    private val hostCtx: Context,
    private val cloneId: String
) : ContextWrapper(targetCtx) {

    private val root: File = StorageSandbox.rootOf(hostCtx, cloneId)

    private fun dir(name: String): File = File(root, name).apply { mkdirs() }

    override fun getDataDir(): File = dir("data")
    override fun getFilesDir(): File = dir("files")
    override fun getNoBackupFilesDir(): File = dir("no_backup")
    override fun getCacheDir(): File = dir("cache")
    override fun getCodeCacheDir(): File = dir("code_cache")
    override fun getDatabasePath(name: String): File = File(dir("databases"), name)
    override fun getExternalFilesDir(type: String?): File? = dir("external_files")
    override fun getExternalCacheDir(): File? = dir("external_cache")

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        // Host context's dataDir is accessible to our UID; prefix isolates per clone.
        hostCtx.getSharedPreferences("clone_${cloneId}_$name", mode)

    companion object {
        /** Our host package — for system-service attribution (see below). */
        const val HOST_PACKAGE = "com.cloner.multiinstance"
    }

    /**
     * System-service attribution (AppOps, Settings.Global etc.) must use the HOST
     * package. getPackageName() still returns the TARGET (for app logic), but the
     * system checks UID ownership on Settings/ContentProvider calls and throws
     * "Package <target> does not belong to <uid>" otherwise (e.g. PhoneWindow
     * reading Settings.Global during Activity.attach()).
     */
    override fun getOpPackageName(): String = HOST_PACKAGE

    init {
        HookDiagnostics.record(
            hostCtx,
            "storage: TargetAppContext for " + targetCtx.packageName +
                ", dataDir=" + dir("data").absolutePath
        )
    }
}
