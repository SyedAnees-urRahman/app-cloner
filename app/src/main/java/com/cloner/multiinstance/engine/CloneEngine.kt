package com.cloner.multiinstance.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import com.cloner.multiinstance.data.CloneRepository
import com.cloner.multiinstance.engine.hook.VirtualInstrumentation
import com.cloner.multiinstance.model.CloneEntity
import java.util.UUID

object CloneEngine {

    /**
     * Launches a clone by starting the manifest-declared stub activity
     * (which runs in the isolated :clone process) with the real target
     * intent tucked inside. VirtualInstrumentation.newActivity() then
     * instantiates the target app's activity with its own ClassLoader.
     */
    fun launchClone(context: Context, clone: CloneEntity) {
        val pm = context.packageManager
        val targetIntent = pm.getLaunchIntentForPackage(clone.sourcePackage) ?: return
        val launchMode = runCatching {
            pm.getActivityInfo(targetIntent.component!!, 0).launchMode
        }.getOrDefault(ActivityInfo.LAUNCH_MULTIPLE)
        val stubClass = when (launchMode) {
            ActivityInfo.LAUNCH_SINGLE_TOP -> StubSingleTopActivity::class.java
            ActivityInfo.LAUNCH_SINGLE_TASK -> StubSingleTaskActivity::class.java
            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> StubSingleInstanceActivity::class.java
            else -> StubActivity::class.java
        }
        // Clone metadata MUST live on the target intent nested inside EXTRA_TARGET:
        // newActivity()/callActivityOnCreate read them from the nested intent,
        // not from the stub intent itself.
        targetIntent.putExtra(VirtualInstrumentation.EXTRA_CLONE_ID, clone.id)
        targetIntent.putExtra(VirtualInstrumentation.EXTRA_APK_PATH, clone.apkPath)
        val stubIntent = Intent(context, stubClass).apply {
            putExtra(VirtualInstrumentation.EXTRA_TARGET, targetIntent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingCloneStore.set(context, clone.id, clone.sourcePackage)
        context.startActivity(stubIntent)
    }

    suspend fun createClone(
        context: Context,
        repo: CloneRepository,
        packageName: String,
        appName: String,
        displayName: String,
        badgeColor: Long
    ): CloneEntity {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        val cloneId = UUID.randomUUID().toString()
        val number = repo.countForPackage(packageName) + 1
        StorageSandbox.rootOf(context, cloneId)
        return CloneEntity(
            id = cloneId,
            sourcePackage = packageName,
            appName = appName,
            displayName = displayName.ifBlank { "$appName #$number" },
            badgeNumber = number,
            badgeColor = badgeColor,
            sandboxRoot = "virtual/$cloneId",
            apkPath = appInfo.sourceDir
        ).also { repo.insert(it) }
    }

    suspend fun deleteClone(context: Context, repo: CloneRepository, clone: CloneEntity) {
        StorageSandbox.wipe(context, clone.id)
        repo.delete(clone)
    }

    suspend fun clearCloneData(context: Context, repo: CloneRepository, clone: CloneEntity) {
        StorageSandbox.wipe(context, clone.id)
        StorageSandbox.rootOf(context, clone.id)
        repo.update(clone.copy(launchCount = 0))
    }
}
