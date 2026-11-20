package com.devicetracker.tracker

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.ListenableWorker.Result
import com.devicetracker.App
import com.devicetracker.data.db.AppDatabase
import com.devicetracker.data.db.PackageHistoryEntity
import com.devicetracker.data.db.SnapshotEntity
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that performs periodic device snapshots.
 */
class DeviceSnapshotWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DeviceSnapshotWorker"
        const val WORK_NAME = "device_snapshot_worker"
        const val UNIQUE_WORK_NAME = "device_tracker_periodic"

        /**
         * Schedule periodic snapshots.
         */
        fun schedule(context: Context, intervalHours: Long = 1) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(false)
                .setRequiresStorageNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<DeviceSnapshotWorker>(
                intervalHours, TimeUnit.HOURS,
                // Flex interval for WorkManager batching
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.SECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
        }

        /**
         * Schedule a one-time snapshot now.
         */
        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeviceSnapshotWorker>()
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting device snapshot...")

        return try {
            val app = context.applicationContext as App
            val snapshotManager = app.snapshotManager

            // Get previous state for diffing
            val db = AppDatabase.getInstance(context)
            val lastSnapshotId = db.snapshotDao().getLastSuccessfulSnapshotTime()

            val lastPackages = try {
                val repoDir = snapshotManager.gitManager.getRepoDir()
                if (repoDir != null) {
                    val currentPkgFile = java.io.File(repoDir, "packages/current.json")
                    if (currentPkgFile.exists()) {
                        val json = currentPkgFile.readText()
                        val type = object : com.google.gson.reflect.TypeToken<List<com.devicetracker.data.model.AppInfo>>() {}.type
                        com.google.gson.Gson().fromJson<List<com.devicetracker.data.model.AppInfo>>(json, type)
                    } else null
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Could not load previous packages", e)
                null
            }

            val result = snapshotManager.takeSnapshot(
                previousPackages = lastPackages,
                previousConfig = null, // We could load previous config too
                includeSystemApps = false,
                notes = "Periodic auto-snapshot",
            )

            result.fold(
                onSuccess = { snapResult ->
                    db.snapshotDao().insertSnapshot(
                        SnapshotEntity(
                            snapshotId = snapResult.snapshot.snapshotId,
                            timestamp = snapResult.snapshot.createdAt,
                            packageCount = snapResult.snapshot.totalApkCount,
                            changedPackages = snapResult.changes.count { it.changeType != com.devicetracker.data.model.ChangeType.UNCHANGED },
                            repoSizeBytes = snapResult.repoSizeBytes,
                            status = "success",
                            errorMessage = null,
                            gitCommitHash = snapResult.commitHash,
                        )
                    )

                    // Log individual package changes
                    for (change in snapResult.changes) {
                        if (change.entityType == "package") {
                            db.snapshotDao().insertPackageHistory(
                                PackageHistoryEntity(
                                    packageName = change.entityId,
                                    versionCode = 0,
                                    versionName = change.newValue ?: change.oldValue ?: "",
                                    snapshotId = snapResult.snapshot.snapshotId,
                                    timestamp = snapResult.snapshot.createdAt,
                                    changeType = change.changeType.name,
                                )
                            )
                        }
                    }

                    Log.i(TAG, "Snapshot complete: ${snapResult.commitHash ?: "no commit"}")
                    Result.success()
                },
                onFailure = { error ->
                    db.snapshotDao().insertSnapshot(
                        SnapshotEntity(
                            snapshotId = "error_${System.currentTimeMillis()}",
                            timestamp = System.currentTimeMillis(),
                            packageCount = 0,
                            changedPackages = 0,
                            repoSizeBytes = 0,
                            status = "failed",
                            errorMessage = error.message,
                            gitCommitHash = null,
                        )
                    )
                    Log.e(TAG, "Snapshot failed", error)
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in snapshot worker", e)
            Result.failure()
        }
    }
}
