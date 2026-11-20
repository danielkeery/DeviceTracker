package com.devicetracker.tracker

import android.content.Context
import android.util.Log
import java.io.*
import java.util.concurrent.*
import kotlin.concurrent.thread

/**
 * Root-powered filesystem watcher.
 *
 * Uses `su` to run filesystem monitoring commands (find, stat, inotify)
 * and commits changes to the git repo. This gives us full filesystem visibility.
 *
 * On a rooted device, this can track changes across the entire filesystem.
 * On unrooted devices, it falls back to the app's private scope.
 */
class RootFileWatcher(
    private val context: Context,
    private val gitManager: GitManager,
) {

    companion object {
        private const val TAG = "RootFileWatcher"
        private const val WATCH_INTERVAL_MS = 300_000L // 5 minutes between full scans

        /** Directories to monitor on the full filesystem (root-privileged) */
        val ROOT_WATCH_PATHS = listOf(
            "/data/app/",                    // installed APK dirs
            "/data/data/",                    // app data directories
            "/data/system/",                  // system configs
            "/system/etc/",                   // system config files
            "/vendor/etc/",                   // vendor configs
            "/data/misc/",                    // misc system data
            "/data/local/tmp/",              // local temp
            "/sdcard/",                       // shared storage
            "/cache/",                         // cache
        )

        /** File patterns to track for important configs */
        val IMPORTANT_CONFIG_PATTERNS = listOf(
            "*.conf",
            "*.json",
            "*.xml",
            "*.properties",
            "*.prop",
            "*.key",
            "*.keystore",
            "*.jks",
            "*.pem",
            "*.crt",
            "*.ini",
            "*.cfg",
            "*.yml",
            "*.yaml",
            "*.toml",
            "gradle.properties",
            "local.properties",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "AndroidManifest.xml",
        )
    }

    private var running = false
    private var watcherThread: Thread? = null
    private var lastScanResults: Map<String, FileSnapshot> = emptyMap()

    data class FileSnapshot(
        val path: String,
        val size: Long,
        val lastModified: Long,
        val permissions: String?,
        val owner: String?,
    )

    data class FileChange(
        val path: String,
        val type: ChangeType,
        val oldSnapshot: FileSnapshot?,
        val newSnapshot: FileSnapshot?,
    )

    enum class ChangeType {
        ADDED, MODIFIED, DELETED
    }

    /**
     * Check if root is available.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c 'echo root_available'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            line == "root_available"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Snapshot the current state of all watched paths using root.
     */
    fun snapshotWatchedPaths(rootPaths: List<String> = ROOT_WATCH_PATHS): Map<String, FileSnapshot> {
        val result = mutableMapOf<String, FileSnapshot>()

        if (!isRootAvailable()) {
            Log.w(TAG, "Root not available, scanning only accessible paths")
            scanDirectory(context.getExternalFilesDir(null)?.parentFile, result)
            scanDirectory(File("/data/local/tmp"), result)
            return result
        }

        // Use root to stat files
        for (rootPath in rootPaths) {
            try {
                val cmd = "su -c 'find \"$rootPath\" -maxdepth 3 -type f -exec stat -c \"%n|%s|%Y|%a|%U\" {} \\; 2>/dev/null'"
                val process = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { l ->
                        val parts = l.split("|", limit = 5)
                        if (parts.size >= 3) {
                            result[parts[0]] = FileSnapshot(
                                path = parts[0],
                                size = parts.getOrElse(1) { "0" }.toLongOrNull() ?: 0,
                                lastModified = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0,
                                permissions = parts.getOrElse(3) { "" },
                                owner = parts.getOrElse(4) { "" },
                            )
                        }
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to scan $rootPath: ${e.message}")
            }
        }

        return result
    }

    /**
     * Find config files that match important patterns.
     */
    fun findImportantConfigs(targetDir: String = "/"): List<String> {
        val configs = mutableListOf<String>()
        val patterns = IMPORTANT_CONFIG_PATTERNS.joinToString(" -o ") { "-name \"$it\"" }
        val cmd = "su -c 'find \"$targetDir\" -maxdepth 5 \\( $patterns \\) -type f 2>/dev/null'"

        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val results = reader.readText().lines().filter { it.isNotBlank() }
            process.waitFor()
            results.also { Log.i(TAG, "Found ${it.size} important config files") }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find configs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Detect changes between two scans.
     */
    fun detectChanges(
        previous: Map<String, FileSnapshot>,
        current: Map<String, FileSnapshot>
    ): List<FileChange> {
        val changes = mutableListOf<FileChange>()

        // Deleted files
        for ((path, snap) in previous) {
            if (path !in current) {
                changes.add(FileChange(path, ChangeType.DELETED, snap, null))
            }
        }

        // Added or modified
        for ((path, snap) in current) {
            val prev = previous[path]
            when {
                prev == null -> {
                    changes.add(FileChange(path, ChangeType.ADDED, null, snap))
                }
                prev.lastModified != snap.lastModified || prev.size != snap.size -> {
                    changes.add(FileChange(path, ChangeType.MODIFIED, prev, snap))
                }
            }
        }

        return changes
    }

    /**
     * Copy important changed files to the git repo.
     */
    fun backupChangedFiles(
        changes: List<FileChange>,
        repoDir: File,
    ): Int {
        var count = 0
        for (change in changes) {
            if (change.type == ChangeType.DELETED) continue
            try {
                val relPath = sanitizePath(change.path)
                val target = File(repoDir, "filesystem/$relPath")
                target.parentFile?.mkdirs()

                // Use root to copy
                if (isRootAvailable()) {
                    val cmd = "su -c 'cp \"${change.path}\" \"${target.absolutePath}\" 2>/dev/null'"
                    Runtime.getRuntime().exec(cmd).waitFor()
                } else {
                    val src = File(change.path)
                    if (src.canRead() && src.isFile) {
                        src.copyTo(target, overwrite = true)
                    }
                }
                count++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backup ${change.path}: ${e.message}")
            }
        }
        return count
    }

    /**
     * Run one full scan-and-commit cycle.
     */
    fun scanAndCommit(): Int {
        val repoDir = gitManager.getRepoDir() ?: return 0
        val current = snapshotWatchedPaths()
        val changes = detectChanges(lastScanResults, current)
        val backedUp = backupChangedFiles(changes, repoDir)
        lastScanResults = current

        if (changes.isNotEmpty()) {
            gitManager.commit("Filesystem changes: ${changes.size} files changed")
        }

        return changes.size
    }

    /**
     * Start the periodic watcher loop.
     */
    fun startWatching(intervalMs: Long = WATCH_INTERVAL_MS) {
        if (running) return
        running = true

        watcherThread = thread(name = "root-watcher") {
            Log.i(TAG, "File watcher started")
            while (running) {
                try {
                    val count = scanAndCommit()
                    if (count > 0) {
                        Log.i(TAG, "Detected $count filesystem changes")
                    }
                    Thread.sleep(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Watcher error", e)
                }
            }
        }
    }

    fun stopWatching() {
        running = false
        watcherThread?.join(2000)
        watcherThread = null
    }

    /**
     * Sanitize a path for use as a relative path in the repo.
     */
    private fun sanitizePath(absolutePath: String): String {
        return absolutePath
            .trimStart('/')
            .replace("//", "/")
            .replace("/", "_")
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .let { if (it.length > 200) it.take(200) else it }
    }

    private fun scanDirectory(dir: File?, result: MutableMap<String, FileSnapshot>) {
        if (dir?.exists() != true) return
        dir.walk().filter { it.isFile }.forEach { file ->
            result[file.absolutePath] = FileSnapshot(
                path = file.absolutePath,
                size = file.length(),
                lastModified = file.lastModified(),
                permissions = null,
                owner = null,
            )
        }
    }
}
