package com.devicetracker.tracker

import android.content.Context
import android.util.Log
import com.devicetracker.data.db.AppDatabase
import com.devicetracker.data.db.PackageHistoryEntity
import com.devicetracker.data.db.SnapshotEntity
import com.devicetracker.data.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Orchestrates the full snapshot process: capture state, diff, write to repo, commit.
 */
class SnapshotManager(
    private val context: Context,
    val gitManager: GitManager,
    private val packageTracker: PackageTracker,
    private val configTracker: ConfigTracker,
    private val fileTracker: FileTracker,
) {

    companion object {
        private const val TAG = "SnapshotManager"
    }

    data class SnapshotResult(
        val snapshot: Snapshot,
        val commitHash: String?,
        val changes: List<SnapshotDiff>,
        val repoSizeBytes: Long,
    )

    /**
     * Perform a full snapshot.
     */
    fun takeSnapshot(
        previousPackages: List<AppInfo>? = null,
        previousConfig: DeviceConfig? = null,
        includeSystemApps: Boolean = false,
        notes: String = "",
    ): Result<SnapshotResult> {
        return try {
            val timestamp = System.currentTimeMillis()
            val snapshotId = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(timestamp))

            val repoDir = gitManager.getRepoDir()
            if (repoDir == null) {
                return Result.failure(Exception("Git repository not initialized"))
            }

            // 1. Capture current state
            val packages = packageTracker.getInstalledPackages(includeSystemApps)
            val config = configTracker.captureConfig()

            // 2. Back up developer files
            val devFiles = fileTracker.backupDeveloperFiles(repoDir.absolutePath)
            val systemConfigs = fileTracker.backupSystemConfigs(repoDir.absolutePath)

            // 3. Compute diffs against previous state
            val pkgDiffs = previousPackages?.let { packageTracker.diffPackages(it, packages) } ?: emptyList()
            val configDiffs = previousConfig?.let { configTracker.diffConfigs(it, config) } ?: emptyList()

            // 4. Write snapshot data to repo
            val snapshotJson = GsonBuilder().setPrettyPrinting().create().toJson(
                Snapshot(
                    snapshotId = snapshotId,
                    createdAt = timestamp,
                    deviceId = config.deviceInfo.serial.ifEmpty { config.deviceInfo.fingerprint },
                    packages = packages,
                    config = config,
                    backedUpFiles = devFiles + systemConfigs,
                    totalApkCount = packages.size,
                    notes = notes,
                )
            )

            val snapshotFile = gitManager.writeFile(
                "snapshots/snapshot_$snapshotId.json",
                snapshotJson
            )
            gitManager.writeFile("snapshots/current.json", snapshotJson)

            // 5. Write packages summary
            gitManager.writeFile(
                "packages/current.json",
                GsonBuilder().setPrettyPrinting().create().toJson(packages)
            )

            // 6. Write config
            gitManager.writeFile(
                "config/device_config.json",
                configTracker.configToJson(config)
            )

            // 7. Update changelog
            val changelog = buildChangelog(snapshotId, packages.size, pkgDiffs + configDiffs)
            gitManager.writeFile("CHANGELOG.md", changelog, append = true)

            // 8. Count changes
            val allChanges = pkgDiffs + configDiffs

            // 9. Commit
            val commitMsg = buildCommitMessage(timestamp, packages.size, allChanges)
            val commitHash = gitManager.commit(commitMsg)

            val repoInfo = gitManager.getRepoInfo()

            Result.success(
                SnapshotResult(
                    snapshot = Snapshot(
                        snapshotId = snapshotId,
                        createdAt = timestamp,
                        deviceId = config.deviceInfo.serial.ifEmpty { config.deviceInfo.fingerprint },
                        packages = packages,
                        config = config,
                        backedUpFiles = devFiles + systemConfigs,
                        totalApkCount = packages.size,
                        notes = notes,
                    ),
                    commitHash = commitHash,
                    changes = allChanges,
                    repoSizeBytes = repoInfo.sizeBytes,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot failed", e)
            Result.failure(e)
        }
    }

    private fun buildChangelog(
        snapshotId: String,
        packageCount: Int,
        changes: List<SnapshotDiff>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("\n## Snapshot: $snapshotId")
        sb.appendLine("- **${changes.size}** changes detected")
        sb.appendLine("- **$packageCount** packages tracked")

        val byType = changes.groupBy { it.changeType }
        byType[ChangeType.ADDED]?.let { added ->
            sb.appendLine("- **${added.size}** added:")
            added.take(10).forEach { sb.appendLine("  - `${it.entityId}`") }
        }
        byType[ChangeType.MODIFIED]?.let { modified ->
            sb.appendLine("- **${modified.size}** modified:")
            modified.take(10).forEach { sb.appendLine("  - `${it.entityId}`: ${it.oldValue?.take(80)} → ${it.newValue?.take(80)}") }
        }
        byType[ChangeType.REMOVED]?.let { removed ->
            sb.appendLine("- **${removed.size}** removed:")
            removed.take(10).forEach { sb.appendLine("  - `${it.entityId}`") }
        }

        return sb.toString()
    }

    private fun buildCommitMessage(
        timestamp: Long,
        packageCount: Int,
        changes: List<SnapshotDiff>,
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        val changed = changes.count { it.changeType != ChangeType.UNCHANGED }
        return "Snapshot $dateStr | $packageCount packages | $changed changes"
    }
}
