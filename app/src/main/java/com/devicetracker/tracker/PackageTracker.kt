package com.devicetracker.tracker

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.devicetracker.data.model.AppInfo
import java.io.File
import java.security.MessageDigest

/**
 * Tracks installed packages and their versions.
 * Stores metadata (not APKs) to save space — APK tracking via hash/version is enough
 * for recovery awareness; actual APK recovery can re-download from Play Store or side-load.
 */
class PackageTracker(private val context: Context) {

    companion object {
        private const val TAG = "PackageTracker"
    }

    data class PackageSnapshot(
        val packages: List<AppInfo>,
        val timestamp: Long,
    )

    /**
     * Get all installed packages with metadata.
     */
    fun getInstalledPackages(includeSystemApps: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

        return packages
            .filter { includeSystemApps || !isSystemApp(it) }
            .mapNotNull { packageToAppInfo(pm, it) }
    }

    private fun isSystemApp(pkg: PackageInfo): Boolean {
        return pkg.applicationInfo != null &&
                (pkg.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun packageToAppInfo(pm: PackageManager, pkg: PackageInfo): AppInfo? {
        val appInfo = pkg.applicationInfo ?: return null
        return try {
            val sourceDirs = mutableListOf<String>()
            appInfo.sourceDir?.let { sourceDirs.add(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                appInfo.splitSourceDirs?.let { sourceDirs.addAll(it) }
            }

            val apkSize = try {
                File(appInfo.sourceDir ?: "").length()
            } catch (e: Exception) { 0L }

            val apkHash = try {
                appInfo.sourceDir?.let { sha256File(it) }
            } catch (e: Exception) { null }

            AppInfo(
                packageName = pkg.packageName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                },
                versionName = pkg.versionName ?: "",
                firstInstallTime = pkg.firstInstallTime,
                lastUpdateTime = pkg.lastUpdateTime,
                installerPackage = pm.getInstallerPackageName(pkg.packageName),
                isSystem = isSystemApp(pkg),
                isEnabled = appInfo.enabled,
                primaryAbi = Build.SUPPORTED_ABIS.firstOrNull(),
                secondaryAbi = Build.SUPPORTED_ABIS.getOrNull(1),
                sourceDirs = sourceDirs,
                apkSizeBytes = apkSize,
                apkSha256 = apkHash,
                splitSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    appInfo.splitSourceDirs?.toList() ?: emptyList()
                } else emptyList(),
                requestedPermissions = pkg.requestedPermissions?.toList() ?: emptyList(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get info for ${pkg.packageName}", e)
            null
        }
    }

    /**
     * Compute SHA-256 hash of a file.
     */
    private fun sha256File(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compare two lists of AppInfo and produce diffs.
     */
    fun diffPackages(
        previous: List<AppInfo>,
        current: List<AppInfo>
    ): List<com.devicetracker.data.model.SnapshotDiff> {
        val diffs = mutableListOf<com.devicetracker.data.model.SnapshotDiff>()
        val prevMap = previous.associateBy { it.packageName }
        val currMap = current.associateBy { it.packageName }

        // Removed packages
        for ((name, old) in prevMap) {
            if (name !in currMap) {
                diffs.add(com.devicetracker.data.model.SnapshotDiff(
                    entityType = "package",
                    entityId = name,
                    changeType = com.devicetracker.data.model.ChangeType.REMOVED,
                    oldValue = old.toJson(),
                    newValue = null,
                    diffText = null,
                ))
            }
        }

        // Added or modified packages
        for ((name, new) in currMap) {
            val old = prevMap[name]
            if (old == null) {
                diffs.add(com.devicetracker.data.model.SnapshotDiff(
                    entityType = "package",
                    entityId = name,
                    changeType = com.devicetracker.data.model.ChangeType.ADDED,
                    oldValue = null,
                    newValue = new.toJson(),
                    diffText = null,
                ))
            } else if (old.versionCode != new.versionCode || old.versionName != new.versionName) {
                diffs.add(com.devicetracker.data.model.SnapshotDiff(
                    entityType = "package",
                    entityId = name,
                    changeType = com.devicetracker.data.model.ChangeType.MODIFIED,
                    oldValue = old.toJson(),
                    newValue = new.toJson(),
                    diffText = generatePackageDiff(old, new),
                ))
            }
        }

        return diffs
    }

    private fun generatePackageDiff(old: AppInfo, new: AppInfo): String {
        return """
--- ${old.packageName} (${old.versionName} / ${old.versionCode})
+++ ${new.packageName} (${new.versionName} / ${new.versionCode})
@@ -1 +1 @@
- versionCode: ${old.versionCode}, versionName: ${old.versionName}
+ versionCode: ${new.versionCode}, versionName: ${new.versionName}
""".trimIndent()
    }
}
