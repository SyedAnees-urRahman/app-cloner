package com.cloner.multiinstance.engine

/** One shared ClassLoader per target APK. REQUIRED: statics (e.g. Facebook's AppContext)
 *  are per-ClassLoader — the target Application and its activities must share one loader,
 *  otherwise set() on one copy is invisible to the other. */
object TargetClassLoaders {
    private val cache = mutableMapOf<String, ClassLoader>()

    @Synchronized
    fun of(apkPath: String, parent: ClassLoader?): ClassLoader =
        cache.getOrPut(apkPath) { dalvik.system.PathClassLoader(apkPath, parent) }
}
