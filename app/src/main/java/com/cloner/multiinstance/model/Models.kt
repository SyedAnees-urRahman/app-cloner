package com.cloner.multiinstance.model

import androidx.room.Entity
import androidx.room.PrimaryKey

data class InstalledApp(
    val appName: String,
    val packageName: String
)

@Entity(tableName = "clones")
data class CloneEntity(
    @PrimaryKey val id: String,
    val sourcePackage: String,
    val appName: String,
    val displayName: String,
    val badgeNumber: Int,
    val badgeColor: Long,
    val sandboxRoot: String,
    val apkPath: String,
    val launchCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
