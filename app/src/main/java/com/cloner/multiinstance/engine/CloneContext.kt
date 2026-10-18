package com.cloner.multiinstance.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * Redirects all file I/O of the wrapped context into the per-clone sandbox directory.
 * This is the actual storage-virtualization mechanism.
 */
open class CloneContext(
    base: Context,
    val cloneId: String
) : ContextWrapper(base) {

    private val root: File = StorageSandbox.rootOf(base, cloneId)

    private fun dir(name: String): File = File(root, name).apply { mkdirs() }

    override fun getDataDir(): File = dir("data")
    override fun getFilesDir(): File = dir("files")
    override fun getNoBackupFilesDir(): File = dir("no_backup")
    override fun getCacheDir(): File = dir("cache")
    override fun getCodeCacheDir(): File = dir("code_cache")
    override fun getDatabasePath(name: String): File = File(dir("databases"), name)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("clone_${cloneId}_$name", mode)
}
