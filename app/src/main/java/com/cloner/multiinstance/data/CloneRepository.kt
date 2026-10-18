package com.cloner.multiinstance.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.cloner.multiinstance.model.CloneEntity
import com.cloner.multiinstance.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class CloneRepository(
    private val context: Context,
    private val dao: CloneDao
) {
    val clones: Flow<List<CloneEntity>> = dao.observeAll()

    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .filter { it.packageName != context.packageName }
            .map { app ->
                InstalledApp(
                    appName = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    suspend fun insert(clone: CloneEntity) = dao.insert(clone)
    suspend fun update(clone: CloneEntity) = dao.update(clone)
    suspend fun delete(clone: CloneEntity) = dao.delete(clone)
    suspend fun countForPackage(pkg: String): Int = dao.countForPackage(pkg)
}
