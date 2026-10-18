package com.cloner.multiinstance.engine

import android.content.Context
import java.io.File

/**
 * Hands the clone id + target package from the main process to the :clone
 * process, which starts fresh when a stub activity is launched.
 */
object PendingCloneStore {
    private fun file(context: Context) = File(context.filesDir, "virtual/.pending_clone")

    fun set(context: Context, cloneId: String, packageName: String) {
        file(context).apply { parentFile?.mkdirs() }.writeText("$cloneId|$packageName")
    }

    fun getCloneId(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.substringBefore("|")?.ifBlank { null }

    fun getPackageName(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.substringAfter("|", "")?.ifBlank { null }
}
