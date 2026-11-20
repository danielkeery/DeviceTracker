package com.devicetracker.tracker

import android.content.Context
import android.util.Log
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.io.NullOutputStream
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages the git repository that stores all device snapshots.
 *
 * The repo lives at a configurable path (default: external storage/DeviceTrackerBackup).
 * It acts as a versioned datastore — each snapshot writes a complete state snapshot
 * and commits it. That gives us full history for free via git.
 */
class GitManager(private val context: Context) {

    companion object {
        private const val TAG = "GitManager"
        private const val DEFAULT_REPO_DIR = "DeviceTrackerBackup"
        const val SNAPSHOTS_DIR = "snapshots"
        const val PACKAGES_FILE = "packages/current.json"
        const val CONFIG_FILE = "config/device_config.json"
        const val FILES_MANIFEST = "files/manifest.json"
        const val CHANGELOG = "CHANGELOG.md"
        const val RESTORE_SCRIPT = "restore.sh"
    }

    private var git: Git? = null
    private var repoDir: File? = null

    data class RepoInfo(
        val repoPath: String,
        val sizeBytes: Long,
        val commitCount: Int,
        val lastCommitHash: String?,
        val lastCommitTime: Long?,
    )

    /**
     * Initialize or open the git repository at the given path.
     * If it doesn't exist, initializes a new bare-like repo (but with working dir).
     */
    fun initRepo(repoPath: String? = null): File {
        val baseDir = repoPath?.let { File(it) }
            ?: File(context.getExternalFilesDir(null), DEFAULT_REPO_DIR)
        baseDir.mkdirs()

        val gitDir = File(baseDir, ".git")
        if (!gitDir.exists()) {
            Log.i(TAG, "Initializing new git repository at $baseDir")
            git = Git.init().setDirectory(baseDir).call()
            // Create initial structure
            File(baseDir, "packages").mkdirs()
            File(baseDir, "config").mkdirs()
            File(baseDir, "files").mkdirs()
            File(baseDir, "snapshots").mkdirs()

            // Write .gitignore
            File(baseDir, ".gitignore").writeText(
                """
# Ignore large APK backups — we only track metadata
*.apk
*.aab
*.obb
# Ignore system image files
*.img
# Ignore temporary files
*.tmp
*.temp
.DS_Store
"""
            )

            val initialChangelog = File(baseDir, CHANGELOG)
            if (!initialChangelog.exists()) {
                initialChangelog.writeText("# DeviceTracker Backup\n\nInitialized: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date())}\n")
            }

            // First commit
            git?.add()?.addFilepattern(".")?.call()
            git?.commit()
                ?.setMessage("Initialize DeviceTracker backup repository")
                ?.call()
        } else {
            Log.i(TAG, "Opening existing repository at $baseDir")
            val builder = FileRepositoryBuilder()
            builder.setGitDir(gitDir)
            builder.readEnvironment()
            builder.findGitDir()
            val repo = builder.build()
            git = Git(repo)
        }

        repoDir = baseDir
        return baseDir
    }

    fun getRepoDir(): File? = repoDir

    /**
     * Write a file relative to the repo root, optionally appending.
     */
    fun writeFile(relativePath: String, content: String, append: Boolean = false): File {
        val target = File(repoDir, relativePath)
        target.parentFile?.mkdirs()
        if (append && target.exists()) {
            target.appendText(content)
        } else {
            target.writeText(content)
        }
        return target
    }

    /**
     * Copy a file from source to a relative path in the repo.
     */
    fun copyFileToRepo(sourceFile: File, relativePath: String): File {
        val target = File(repoDir, relativePath)
        target.parentFile?.mkdirs()
        sourceFile.copyTo(target, overwrite = true)
        return target
    }

    /**
     * Commit all staged changes and return the commit hash.
     */
    fun commit(message: String, authorName: String = "DeviceTracker"): String? {
        val g = git ?: return null
        try {
            g.add().addFilepattern(".").call()
            val commit = g.commit()
                .setMessage(message)
                .setAuthor(authorName, "devicetracker@device.local")
                .call()
            val hash = commit.name
            Log.i(TAG, "Committed: $hash — $message")
            return hash
        } catch (e: Exception) {
            Log.e(TAG, "Commit failed", e)
            return null
        }
    }

    /**
     * Get the diff between the previous commit (or parent) and the current state
     * for a specific file path.
     */
    fun getDiffForFile(relativePath: String): String {
        val g = git ?: return ""
        try {
            val repo = g.repository
            val headRef = repo.findRef(Constants.HEAD)
            if (headRef?.objectId == null) return "(no previous commits)"

            val walk = RevWalk(repo)
            val headCommit = walk.parseCommit(headRef.objectId)
            val parent = headCommit.parents.firstOrNull() ?: return "(initial commit)"
            val parentCommit = walk.parseCommit(parent)

            val reader = repo.newObjectReader()
            val oldTree = CanonicalTreeParser()
            oldTree.reset(reader, parentCommit.tree)

            val newTree = CanonicalTreeParser()
            newTree.reset(reader, headCommit.tree)

            val output = ByteArrayOutputStream()
            val formatter = DiffFormatter(output)
            formatter.setRepository(repo)
            formatter.setPathFilter(PathFilter.create(relativePath))
            formatter.format(oldTree, newTree)
            formatter.close()

            return output.toString("UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "Diff failed for $relativePath", e)
            return "(diff unavailable: ${e.message})"
        }
    }

    /**
     * Get repo information.
     */
    fun getRepoInfo(): RepoInfo {
        val g = git ?: return RepoInfo("", 0, 0, null, null)
        return try {
            val repo = g.repository
            val log = g.log().call()
            val count = log.count()

            var lastHash: String? = null
            var lastTime: Long? = null
            if (count > 0) {
                val latest = log.first()
                lastHash = latest.name
                lastTime = latest.commitTime.toLong() * 1000
            }

            val size = repoDir?.walk()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
            RepoInfo(repoDir?.absolutePath ?: "", size, count, lastHash, lastTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get repo info", e)
            RepoInfo(repoDir?.absolutePath ?: "", repoDir?.length() ?: 0, 0, null, null)
        }
    }

    /**
     * Push to remote if configured.
     */
    fun pushToRemote(remoteUrl: String): Result<String> {
        val g = git ?: return Result.failure(Exception("Git not initialized"))
        return try {
            g.push()
                .setRemote(remoteUrl)
                .call()
            Result.success("Push successful")
        } catch (e: Exception) {
            Log.e(TAG, "Push failed", e)
            Result.failure(e)
        }
    }

    /**
     * Close git resources.
     */
    fun close() {
        git?.close()
        git = null
    }
}
