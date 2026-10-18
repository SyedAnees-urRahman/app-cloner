package com.cloner.multiinstance.engine

import android.content.Context
import java.io.File
import java.util.Date

/**
 * Append-only debug log for the virtualization hooks (runs in the :clone
 * process). Shown in MainActivity's crash dialog so the exact hook state
 * can be reported back.
 *
 * IMPORTANT: file() resolves against applicationInfo.dataDir, which is never
 * affected by our ContextWrapper redirection (unlike filesDir, which the
 * activity wrap deliberately redirects into the clone sandbox, and unlike
 * applicationContext.filesDir, which is fragile during attach).
 */
object HookDiagnostics {
    private fun file(context: Context): File {
        // applicationInfo.dataDir is never affected by our ContextWrapper redirection
        // (unlike filesDir, which the activity wrap deliberately redirects into the clone sandbox).
        val dir = File(context.applicationInfo.dataDir, "files").apply { mkdirs() }
        return File(dir, "clone_debug.log")
    }

    @Synchronized
    fun record(context: Context, msg: String) {
        runCatching {
            val f = file(context).apply { parentFile?.mkdirs() }
            if (f.exists() && f.length() > 32 * 1024) f.writeText("")
            f.appendText("[${Date()}] $msg\n")
        }
    }

    fun tail(context: Context, maxChars: Int = 3000): String? {
        val f = file(context)
        if (!f.exists()) return null
        val text = f.readText()
        if (text.isBlank()) return null
        return if (text.length <= maxChars) text else "…\n" + text.takeLast(maxChars)
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
