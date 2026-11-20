package com.devicetracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "snapshot_log")
data class SnapshotEntity(
    @PrimaryKey
    val snapshotId: String,
    val timestamp: Long,
    val packageCount: Int,
    val changedPackages: Int,
    val repoSizeBytes: Long,
    val status: String,       // "success", "failed"
    val errorMessage: String?,
    val gitCommitHash: String?,
)

@Entity(tableName = "package_history")
data class PackageHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val snapshotId: String,
    val timestamp: Long,
    val changeType: String,   // "added", "modified", "removed", "unchanged"
)
