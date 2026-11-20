package com.devicetracker.tracker

import android.content.Context
import android.util.Log
import com.devicetracker.data.model.BackedUpFile
import java.io.File
import java.security.MessageDigest

/**
 * Tracks and backs up specified files/directories.
 * Whole files are stored for small configs and critical dev files.
 * Large directories can be tracked by path (symlink/reference) to save space.
 */
class FileTracker(private val context: Context) {

    companion object {
        private const val TAG = "FileTracker"

        /** Default dev files to track */
        val DEFAULT_TRACKED_PATHS = listOf(
            // Android development critical files
            "~/.android/debug.keystore",
            "~/.android/avd",
            "~/.gradle/gradle.properties",
            "~/.gradle/caches/journal",
            // Platform tools
            // Custom scripts
        )

        /** Maximum file size to store whole (bytes) — 10 MB */
        private const val MAX_WHOLE_FILE_SIZE = 10 * 1024 * 1024L
    }

    data class FileBackupResult(
        val backedUp: List<BackedUpFile>,
        val referenced: List<BackedUpFile>, // tracked by path reference only
    )

    /**
     * Back up a specific file or directory to the repo directory.
     *
     * @param sourcePath Absolute path to the file/directory to back up
     * @param repoBasePath The repo's base directory
     * @param relativeTarget Relative path within the repo where to store the backup
     * @param storeWhole If true, copy whole file; if false, just record metadata
     * @return BackedUpFile record
     */
    fun backupFile(
        sourcePath: String,
        repoBasePath: String,
        relativeTarget: String,
        storeWhole: Boolean = true,
    ): BackedUpFile? {
        try {
            val source = File(sourcePath)
            if (!source.exists()) return null

            val resolvedPath = resolvePath(sourcePath)
            val target = File(repoBasePath, relativeTarget)

            if (storeWhole && source.length() <= MAX_WHOLE_FILE_SIZE && !source.isDirectory) {
                // Copy the actual file
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                return BackedUpFile(
                    relativePath = relativeTarget,
                    sizeBytes = source.length(),
                    sha256 = sha256File(source),
                    lastModified = source.lastModified(),
                    isSymlink = false,
                )
            } else if (source.isDirectory) {
                // For directories, we store a manifest of contents
                val manifest = buildDirectoryManifest(source)
                val manifestFile = File(repoBasePath, "$relativeTarget/MANIFEST.txt")
                manifestFile.parentFile?.mkdirs()
                manifestFile.writeText(manifest)
                return BackedUpFile(
                    relativePath = "$relativeTarget/MANIFEST.txt",
                    sizeBytes = manifestFile.length(),
                    sha256 = sha256File(manifestFile),
                    lastModified = source.lastModified(),
                    isSymlink = false,
                )
            } else {
                // Reference-only: store metadata, not the large file
                return BackedUpFile(
                    relativePath = relativeTarget,
                    sizeBytes = source.length(),
                    sha256 = sha256File(source),
                    lastModified = source.lastModified(),
                    isSymlink = true,
                    targetPath = sourcePath,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to backup $sourcePath", e)
            return null
        }
    }

    /**
     * Back up Android Studio / developer config files from standard locations.
     */
    fun backupDeveloperFiles(repoBasePath: String): List<BackedUpFile> {
        val results = mutableListOf<BackedUpFile>()

        // Debug keystore
        val keystorePath = "${System.getProperty("user.home") ?: "/data/media/0"}/.android/debug.keystore"
        backupFile(
            sourcePath = keystorePath,
            repoBasePath = repoBasePath,
            relativeTarget = "dev_files/debug.keystore",
            storeWhole = true,
        )?.let { results.add(it) }

        // ADB keys
        val adbKeyDir = "${System.getProperty("user.home") ?: "/data/media/0"}/.android"
        backupFile(
            sourcePath = "$adbKeyDir/adbkey",
            repoBasePath = repoBasePath,
            relativeTarget = "dev_files/adbkey",
            storeWhole = true,
        )?.let { results.add(it) }

        return results
    }

    /**
     * Backup build.prop and other critical system configs.
     */
    fun backupSystemConfigs(repoBasePath: String): List<BackedUpFile> {
        val results = mutableListOf<BackedUpFile>()

        val configSources = listOf(
            "/system/build.prop" to "config/build.prop",
            "/vendor/build.prop" to "config/vendor_build.prop",
        )

        for ((src, dest) in configSources) {
            backupFile(src, repoBasePath, dest, storeWhole = true)?.let { results.add(it) }
        }

        return results
    }

    private fun buildDirectoryManifest(dir: File): String {
        val sb = StringBuilder()
        sb.appendLine("Directory: ${dir.absolutePath}")
        sb.appendLine("Last modified: ${dir.lastModified()}")
        sb.appendLine("---")
        dir.walk().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir)
            sb.appendLine("${relative.path}\t${file.length()}\t${file.lastModified()}")
        }
        return sb.toString()
    }

    private fun resolvePath(path: String): File {
        return if (path.startsWith("~/")) {
            File(System.getProperty("user.home") ?: "/data/media/0", path.removePrefix("~/"))
        } else {
            File(path)
        }
    }

    private fun sha256File(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }
}
