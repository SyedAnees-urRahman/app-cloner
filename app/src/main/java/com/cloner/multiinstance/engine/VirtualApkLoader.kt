package com.cloner.multiinstance.engine

import dalvik.system.PathClassLoader

object VirtualApkLoader {
    private val cache = mutableMapOf<String, ClassLoader>()

    @Synchronized
    fun get(apkPath: String): ClassLoader =
        cache.getOrPut(apkPath) {
            PathClassLoader(apkPath, ClassLoader.getSystemClassLoader())
        }
}
