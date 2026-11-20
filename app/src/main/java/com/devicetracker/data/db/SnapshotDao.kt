package com.devicetracker.data.db

import androidx.room.*

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshot_log ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecentSnapshots(): List<SnapshotEntity>

    @Query("SELECT * FROM snapshot_log WHERE snapshotId = :snapshotId")
    suspend fun getSnapshot(snapshotId: String): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: SnapshotEntity)

    @Query("SELECT * FROM package_history ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentPackageChanges(): List<PackageHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPackageHistory(history: PackageHistoryEntity)

    @Query("SELECT MAX(timestamp) FROM snapshot_log WHERE status = 'success'")
    suspend fun getLastSuccessfulSnapshotTime(): Long?

    @Query("SELECT * FROM package_history WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun getPackageHistory(packageName: String): List<PackageHistoryEntity>

    @Query("DELETE FROM snapshot_log WHERE timestamp < :before")
    suspend fun deleteOldSnapshots(before: Long)
}
