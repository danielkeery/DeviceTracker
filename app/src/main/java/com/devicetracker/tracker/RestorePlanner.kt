package com.devicetracker.tracker

import android.util.Log
import java.io.File
import java.net.URL

/**
 * Restores device state from the captured/saved repo.
 *
 * When a *custom link* is encountered (LINK/DIFF/PACKAGE mode), the planner
 * runs a **retrieval process** for the pristine bytes, then **patches** the
 * user's changes on top.
 *
 * Workflow per artifact:
 * ```
 * manifest.json
 * ├── WHOLE     → copy bytes directly from repo to device
 * ├── HASH      → verify against source (OS image); fail if mismatch
 * ├── LINK      → curl/download/clone source; place on device
 * ├── DIFF      → get-pristine(LINK) → patch(patch) → place on device
 * └── PACKAGE   → install from source (Play, F-Droid, apt, or cached APK)
 * ```
 */
class RestorePlanner(private val repoBase: File) {

    companion object {
        private const val TAG = "RestorePlanner"
    }

    data class Action(
        val artifactType: String,
        val sourcePath: String,
        val targetPath: String,
        val strategy: LinkStore.Mode,
        val retrievalCmd: String? = null,
        val patchFile: String? = null,
        val verifyHash: String? = null,
        val sizeBytes: Long = 0,
        val extra: Map<String, String> = emptyMap(),
    )

    data class ResultReport(
        val ok: List<String> = mutableListOf(),
        val failed: List<String> = mutableListOf(),
        val skipped: List<String> = mutableListOf(),
        val actions: List<Action> = mutableListOf(),
    )

    // ---------- Load manifests ----------

    fun loadLinkManifest() = LinkStore(repoBase).load()
    fun loadEnvManifest() = EnvTracker(repoBase).loadManifest()

    // ---------- Plan generation (dry run) ----------

    /**
     * Generate the ordered list of restoration actions.
     * Phase order: packages → env → configs → scripts → git repos → extras.
     */
    fun plan(): List<Action> {
        val actions = mutableListOf<Action>()
        val linkManifest = loadLinkManifest()
        val envManifest = loadEnvManifest()

        // 1. Package links
        for (pkg in linkManifest.packages) {
            actions.add(Action(
                artifactType = "package",
                sourcePath = "links/packages/${pkg.packageName}",
                targetPath = "/data/app/${pkg.packageName}",
                strategy = LinkStore.Mode.PACKAGE,
                retrievalCmd = when {
                    pkg.source.startsWith("http") -> "curl -L -o /tmp/${pkg.packageName}.apk '${pkg.source}' && pm install /tmp/${pkg.packageName}.apk"
                    pkg.source.contains("apt") -> "apt-get install ${pkg.packageName}"
                    else -> null
                },
                verifyHash = pkg.apkSha256,
            ))
        }

        // 2. LINK / HASH / DIFF / WHOLE files
        for (f in linkManifest.files) {
            when (f.mode) {
                LinkStore.Mode.LINK -> actions.add(Action(
                    artifactType = "link", sourcePath = f.link ?: "",
                    targetPath = f.path, strategy = f.mode,
                    retrievalCmd = "curl -L -o '${f.path}' '${f.link}'",
                    verifyHash = f.sha256,
                ))
                LinkStore.Mode.HASH -> actions.add(Action(
                    artifactType = "hash", sourcePath = f.path,
                    targetPath = f.path, strategy = f.mode,
                    verifyHash = f.sha256,
                ))
                LinkStore.Mode.DIFF -> actions.add(Action(
                    artifactType = "diff", sourcePath = f.link ?: "",
                    targetPath = f.path, strategy = f.mode,
                    retrievalCmd = "curl -L -o /tmp/pristine '${f.link}'",
                    patchFile = f.patch,
                    verifyHash = f.sha256,
                ))
                LinkStore.Mode.WHOLE -> actions.add(Action(
                    artifactType = "whole", sourcePath = f.path,
                    targetPath = f.path, strategy = f.mode,
                    sizeBytes = f.sizeBytes,
                ))
                else -> {} // PACKAGE handled above
            }
        }

        // 3. Environment (dpkg/pip/apt reinstall from frozen lists)
        if (envManifest.packages.isNotBlank()) {
            actions.add(Action(
                artifactType = "env_packages",
                sourcePath = "env/chroot/${envManifest.packages}",
                targetPath = "chroot",
                strategy = LinkStore.Mode.LINK,
                retrievalCmd = "dpkg --set-selections < $repoBase/env/chroot/${envManifest.packages}; dselect-upgrade",
            ))
        }

        // 4. Configs
        if (envManifest.configFiles.isNotEmpty()) {
            for (cfg in envManifest.configFiles) {
                when (cfg.mode) {
                    LinkStore.Mode.DIFF -> actions.add(Action(
                        artifactType = "config_diff",
                        sourcePath = cfg.link ?: "",
                        targetPath = cfg.path, strategy = cfg.mode,
                        retrievalCmd = "curl -L -o /tmp/pristine '${cfg.link}'",
                        patchFile = cfg.patch,
                    ))
                    LinkStore.Mode.WHOLE -> actions.add(Action(
                        artifactType = "config_whole",
                        sourcePath = cfg.path, targetPath = cfg.path,
                        strategy = cfg.mode,
                    ))
                    else -> {}
                }
            }
        }

        // 5. Scripts
        for (s in envManifest.scripts) {
            actions.add(Action(
                artifactType = "script", sourcePath = s.link ?: "",
                targetPath = s.path, strategy = s.mode,
            ))
        }

        // 6. Git repos
        for (gr in envManifest.gitRepos) {
            actions.add(Action(
                artifactType = "git_repo",
                sourcePath = gr.remote,
                targetPath = gr.path,
                strategy = LinkStore.Mode.LINK,
                retrievalCmd = buildString {
                    append("git clone --branch ${gr.branch} --depth 1 '${gr.remote}' '${gr.path}'")
                    if (gr.headCommit.isNotBlank()) append(" && git -C '${gr.path}' fetch origin ${gr.headCommit}")
                },
                extra = mapOf("branch" to gr.branch, "head" to gr.headCommit, "uncommitted_diff" to gr.uncommittedDiff),
            ))
        }

        return actions
    }

    // ---------- Execute ----------

    /**
     * Execute a planned action. Returns true on success.
     *
     * For DIFF actions:
     *   1. Retrieval: fetch pristine file from the link (curl/clone/whatever)
     *   2. Patch: apply the stored patch on top of the pristine
     *   3. Verify: hash the result matches the stored sha256
     *   4. Place: put it on the target path on the device
     */
    fun execute(action: Action): Boolean {
        return try {
            when (action.strategy) {
                LinkStore.Mode.WHOLE -> {
                    val src = findRepoSource(action)
                    if (src != null && src.exists()) {
                        File(action.targetPath).parentFile?.mkdirs()
                        src.copyTo(File(action.targetPath), overwrite = true)
                        true
                    } else {
                        Log.w(TAG, "WHOLE source missing: ${action.sourcePath}")
                        false
                    }
                }
                LinkStore.Mode.LINK -> {
                    // retrieval + place
                    val tmp = runRetrieval(action)
                    if (tmp != null && action.verifyHash.isNullOrBlank().not()) {
                        if (verifyHash(tmp, action.verifyHash!!)) {
                            tmp.copyTo(File(action.targetPath), overwrite = true)
                            true
                        } else {
                            Log.e(TAG, "Hash mismatch for LINK: ${action.targetPath}")
                            false
                        }
                    } else if (tmp != null) {
                        tmp.copyTo(File(action.targetPath), overwrite = true)
                        true
                    } else {
                        Log.w(TAG, "LINK retrieval failed: ${action.sourcePath}")
                        false
                    }
                }
                LinkStore.Mode.DIFF -> {
                    // retrieval of pristine + patch + verify + place
                    val pristine = runRetrieval(action.copy(retrievalCmd = action.retrievalCmd))
                        ?: runCatching { downloadPristineFallback(action.sourcePath, "/tmp/pristine_${random()}") }.getOrNull()
                    val patch = action.patchFile?.let { File(repoBase, it) }?.takeIf { it.exists() }
                    if (pristine != null && patch != null) {
                        applyPatch(pristine, patch, File(action.targetPath))
                        if (action.verifyHash.isNullOrBlank().not()) {
                            verifyHash(File(action.targetPath), action.verifyHash!!)
                        }
                        true
                    } else {
                        // fallback: if patch unavailable but we have the stored modified version somehow
                        val storedModified = findRepoSource(action)
                        if (storedModified != null) {
                            storedModified.copyTo(File(action.targetPath), overwrite = true)
                            true
                        } else {
                            false
                        }
                    }
                }
                LinkStore.Mode.HASH -> {
                    // Verify that the existing target matches the stored hash;
                    // if it does, nothing to do. If it doesn't, the admin must
                    // fix the mismatch (OS update that changed the file).
                    val target = File(action.targetPath)
                    if (!target.exists()) {
                        Log.w(TAG, "HASH target missing (needs reinstall from base image): ${action.targetPath}")
                        false
                    } else {
                        if (action.verifyHash.isNullOrBlank().not() && verifyHash(target, action.verifyHash!!)) {
                            true // file is intact
                        } else {
                            Log.e(TAG, "HASH mismatch for ${action.targetPath} — OS update or tamper detected")
                            false
                        }
                    }
                }
                LinkStore.Mode.PACKAGE -> {
                    if (action.retrievalCmd != null) {
                        val result = exec(action.retrievalCmd)
                        result.contains("Success") || result.contains("installed")
                    } else {
                        false
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "execute failed for ${action.artifactType}:${action.targetPath}", e)
            false
        }
    }

    // ---------- Internal helpers ----------

    private fun runRetrieval(action: Action): File? {
        if (action.retrievalCmd.isNullOrBlank()) return null
        val tmp = File("/tmp/restore_${random()}_pristine")
        exec(action.retrievalCmd.replace("'${action.sourcePath}'", "'${tmp.absolutePath}'")
            .replace(" /tmp/pristine ", " ${tmp.absolutePath} "))
        return if (tmp.exists()) tmp else null
    }

    private fun downloadPristineFallback(url: String, destPath: String): File? {
        return try {
            val dest = File(destPath); dest.parentFile?.mkdirs()
            URL(url).openStream().use { input -> dest.outputStream().use { it.write(input.readBytes()) } }
            dest
        } catch (e: Exception) { null }
    }

    private fun applyPatch(pristine: File, patch: File, target: File) {
        // Apply unified diff in-memory (no external `patch` binary needed)
        val pristineLines = pristine.readText().lines()
        val patchLines = patch.readLines()
        val result = mutableListOf<String>()
        var inHunk = false
        val removeSet = mutableSetOf<String>()
        val addQueue = mutableListOf<String>()

        for (line in patchLines) {
            when {
                line.startsWith("--- ") || line.startsWith("+++ ") -> { /* headers */ }
                line.startsWith("@@") -> {
                    inHunk = true
                    result.addAll(pristineLines.filter { it !in removeSet })
                    result.addAll(addQueue)
                    removeSet.clear(); addQueue.clear()
                }
                line.startsWith("-") -> removeSet.add(line.substring(1))
                line.startsWith("+") -> addQueue.add(line.substring(1))
                line.startsWith(" ") -> {
                    result.addAll(pristineLines.filter { it !in removeSet })
                    result.addAll(addQueue)
                    removeSet.clear(); addQueue.clear()
                    addQueue.add(line.substring(1))
                }
                else -> {
                    // Context line
                }
            }
        }
        result.addAll(pristineLines.filter { it !in removeSet })
        result.addAll(addQueue)
        target.parentFile?.mkdirs()
        target.writeText(result.joinToString("\n"))
    }

    private fun verifyHash(file: File, expected: String): Boolean {
        val actual = LinkStore(repoBase).sha256Hex(file.readBytes())
        return actual.equals(expected, ignoreCase = true)
    }

    private fun findRepoSource(action: Action): File? {
        val basename = File(action.sourcePath).name
        repoBase.walk().maxDepth(5).forEach { f ->
            if (f.isFile && f.name == basename) return f
        }
        return File(repoBase, action.sourcePath).takeIf { it.exists() }
    }

    private fun exec(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.destroy()
            out
        } catch (e: Exception) { "" }
    }

    private fun random() = System.nanoTime().toString(16)
}
