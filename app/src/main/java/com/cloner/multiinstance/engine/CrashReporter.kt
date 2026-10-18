package com.cloner.multiinstance.engine

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Date

object CrashReporter {
    fun install(context: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val dir = File(context.filesDir, "crashes").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(
                    "time=${Date()}\nthread=${thread.name}\nprocess=${processName()}\n\n${Log.getStackTraceString(e)}"
                )
            }
            default?.uncaughtException(thread, e)
        }
    }
    fun latest(context: Context): File? =
        File(context.filesDir, "crashes").listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    fun clear(context: Context) { File(context.filesDir, "crashes").deleteRecursively() }
    private fun processName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "unknown"
    }.getOrDefault("unknown")
}
