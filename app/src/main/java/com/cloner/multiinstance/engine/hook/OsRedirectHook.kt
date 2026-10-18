package com.cloner.multiinstance.engine.hook

import android.content.Context
import com.cloner.multiinstance.engine.HookDiagnostics
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Java-level file I/O redirect (v2.13).
 *
 * Some apps build HARDCODED absolute paths (/data/user/0/<pkg>/...) and open
 * them directly, bypassing any ContextWrapper. Our :clone process runs as the
 * host UID and cannot touch the target's private data dir, so those opens
 * fail with ENOENT (e.g. Facebook's AuraLacrimaConfig writing
 * app_errorreporting/.../state.txt).
 *
 * Fix: replace the public static field libcore.io.Libcore.os (type
 * libcore.io.Os, an interface) with a Proxy. open()/openat() calls whose path
 * starts with a registered target data-dir prefix are rewritten to the
 * per-clone sandbox; everything else passes through untouched.
 */
object OsRedirectHook {
    private val lock = Any()
    private val prefixes = mutableMapOf<String, String>() // prefix -> replacement
    @Volatile private var installed = false

    fun install(hostCtx: Context, targetPkg: String, sandboxRoot: File): Boolean {
        synchronized(lock) {
            val repl = sandboxRoot.absolutePath + "/"
            prefixes["/data/user/0/$targetPkg/"] = repl
            prefixes["/data/data/$targetPkg/"] = repl
            prefixes["/data/user_de/0/$targetPkg/"] = repl
            if (installed) return true
            return runCatching {
                val libcore = Class.forName("libcore.io.Libcore")
                val osField = libcore.getDeclaredField("os").apply { isAccessible = true }
                val originalOs = osField.get(null) ?: return@runCatching false
                // Avoid double-wrapping if somehow installed twice.
                if (Proxy.isProxyClass(originalOs.javaClass)) {
                    installed = true
                    return@runCatching true
                }
                val osIface = Class.forName("libcore.io.Os")
                val proxy = Proxy.newProxyInstance(
                    osIface.classLoader,
                    arrayOf(osIface)
                ) { _, method, args ->
                    try {
                        if (args != null) {
                            @Suppress("UNCHECKED_CAST")
                            val a = args as Array<Any?>
                            val pathIdx = when (method.name) {
                                "open" -> 0
                                "openat" -> 1
                                else -> -1
                            }
                            if (pathIdx >= 0 && pathIdx < a.size) {
                                val path = a[pathIdx] as? String
                                if (path != null) {
                                    synchronized(lock) {
                                        for ((prefix, r) in prefixes) {
                                            if (path.startsWith(prefix)) {
                                                val redirected = r + path.substring(prefix.length)
                                                File(redirected).parentFile?.mkdirs()
                                                a[pathIdx] = redirected
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // Never break I/O because of the redirect logic itself.
                    }
                    try {
                        if (args != null) method.invoke(originalOs, *args)
                        else method.invoke(originalOs)
                    } catch (e: InvocationTargetException) {
                        // Unwrap so the original errno/exception type propagates.
                        throw e.targetException ?: e
                    }
                }
                osField.set(null, proxy)
                installed = true
                HookDiagnostics.record(
                    hostCtx,
                    "os-hook: Libcore.os replaced, redirecting /data/.../" + targetPkg +
                        "/ -> " + sandboxRoot.absolutePath
                )
                true
            }.onFailure {
                runCatching {
                    HookDiagnostics.record(
                        hostCtx,
                        "os-hook: FAILED " + it.javaClass.simpleName + ": " + it.message
                    )
                }
            }.getOrDefault(false)
        }
    }
}
