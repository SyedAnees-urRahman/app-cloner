package com.cloner.multiinstance.engine

import android.content.Context
import java.io.File

object StorageSandbox {
    fun rootOf(context: Context, cloneId: String): File =
        File(context.filesDir, "virtual/$cloneId").apply { mkdirs() }

    fun wipe(context: Context, cloneId: String): Boolean =
        File(context.filesDir, "virtual/$cloneId").deleteRecursively()

    fun sizeBytes(context: Context, cloneId: String): Long =
        File(context.filesDir, "virtual/$cloneId").walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
}
