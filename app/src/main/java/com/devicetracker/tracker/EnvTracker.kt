package com.devicetracker.tracker

import android.util.Log
import java.io.File

/**
 * Environment / chroot snapshotter.
 *
 * Captures: package state, env vars, processes, sessions, git repos,
 * config files (DIFF vs pristine), scripts, cron, ssh.
 */
class EnvTracker(private val repoBase: File) {

    companion object {
        private const val TAG = "EnvTracker"
        const val ENV_DIR = "env"
        val CHROOT_CANDIDATES = listOf(
            "/chroot", "/opt/chroot", "/usr/local/chroot",
            "/dev/chroot", "/srv/chroot", "/root/chroot",
        )
    }

    data class SessionSnapshot(
        var shell: String = "",
        var cwd: String = "",
        var envFile: String = "",
        var historyTail: String = "",
        var openTtys: List<String> = emptyList(),
        var tmuxSessions: List<String> = emptyList(),
    )

    data class GitRepoState(
        var path: String = "",
        var remote: String = "",
        var branch: String = "",
        var headCommit: String = "",
        var statusClean: Boolean = true,
        var uncommittedDiff: String = "",
        var uncommittedDiffFile: String = "",
        var submodules: List<String> = emptyList(),
    )

    data class EnvManifest(
        var capturedAt: Long = 0,
        var chrootRoot: String = "",
        var packages: String = "",
        var envVars: String = "",
        var processes: String = "",
        var ports: String = "",
        var session: SessionSnapshot? = null,
        var gitRepos: MutableList<GitRepoState> = mutableListOf(),
        var configFiles: MutableList<LinkStore.FileLink> = mutableListOf(),
        var scripts: MutableList<LinkStore.FileLink> = mutableListOf(),
        var cron: String = "",
        var ssh: String = "",
        var notes: MutableList<String> = mutableListOf(),
    )

    private val linkStore = LinkStore(repoBase)
    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    private val manifestFile = File(repoBase, "$ENV_DIR/manifest.json")

    fun detectChrootRoot(): File? =
        CHROOT_CANDIDATES.map { File(it) }.firstOrNull { it.isDirectory && isChrootLike(it) }

    private fun isChrootLike(dir: File): Boolean {
        val markers = listOf("usr", "etc", "lib", "bin", "root", "home")
        return markers.count { File(dir, it).exists() } >= 3
    }

    fun captureAll(manifest: EnvManifest): EnvManifest {
        val root = manifest.chrootRoot.let { if (it.isBlank()) detectChrootRoot()?.absolutePath ?: "" else it }
        manifest.chrootRoot = root
        if (root.isBlank()) manifest.notes.add("No chroot detected; capturing host only")

        capturePackageState(manifest)
        captureEnvVars(manifest)
        captureProcessesAndPorts(manifest)
        captureSession(manifest)
        captureGitRepos(manifest, root)
        captureConfigs(manifest, root)
        captureScripts(manifest, root)
        captureCron(manifest, root)
        captureSsh(manifest, root)
        manifest.capturedAt = System.currentTimeMillis()
        persist(manifest)
        return manifest
    }

    fun loadManifest(): EnvManifest {
        if (!manifestFile.exists()) return EnvManifest()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<EnvManifest>() {}.type
            gson.fromJson(manifestFile.readText(), type) ?: EnvManifest()
        } catch (e: Exception) { EnvManifest() }
    }

    fun persist(manifest: EnvManifest) {
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(gson.toJson(manifest))
    }

    // ----- Package state (dpkg/pip/npm/apt) -----
    private fun capturePackageState(m: EnvManifest) {
        val root = m.chrootRoot
        val dir = File(repoBase, "$ENV_DIR/chroot").apply { mkdirs() }
        val cmds = mapOf(
            "dpkg-selections.txt" to listOf("dpkg", "--get-selections"),
            "pip-freeze.txt" to listOf("pip3", "freeze"),
            "apt-mark.txt" to listOf("apt-mark", "showmanual"),
            "npm-global.txt" to listOf("npm", "list", "-g", "--depth=0"),
            "installed-apks.txt" to listOf("pm", "list", "packages"),
        )
        for ((file, cmd) in cmds) {
            val out = runInChroot(root, cmd)
            val f = File(dir, file)
            f.writeText(out.ifBlank { "# empty\n" })
            if (m.packages.isBlank()) m.packages = file
        }
    }

    // ----- Env vars -----
    private fun captureEnvVars(m: EnvManifest) {
        val root = m.chrootRoot
        val dir = File(repoBase, "$ENV_DIR/chroot"); dir.mkdirs()
        val envOut = runInChroot(root, listOf("env"))
        File(dir, "env-vars.txt").writeText(envOut.ifBlank { "# empty\n" })
        m.envVars = "chroot/env-vars.txt"
        listOf("/etc/environment", "/etc/profile", "/etc/bash.bashrc", "/root/.bashrc", "/root/.profile")
            .forEach { p -> captureConfigFile(m, p, root) }
    }

    // ----- Processes & ports -----
    private fun captureProcessesAndPorts(m: EnvManifest) {
        val dir = File(repoBase, "$ENV_DIR/chroot"); dir.mkdirs()
        val root = m.chrootRoot
        File(dir, "processes.txt").writeText(
            runInChroot(root, listOf("ps", "auxww")).ifBlank { host("ps", "auxww").ifBlank { "# empty\n" } }
        )
        m.processes = "chroot/processes.txt"
        File(dir, "ports.txt").writeText(
            runInChroot(root, listOf("ss", "-tulnp")).ifBlank {
                host("netstat", "-tulnp").ifBlank { "# empty\n" }
            }
        )
        m.ports = "chroot/ports.txt"
    }

    // ----- Sessions -----
    private fun captureSession(m: EnvManifest) {
        val root = m.chrootRoot
        val dir = File(repoBase, "$ENV_DIR/chroot/sessions").apply { mkdirs() }
        val historyPath = resolvePath("/root/.bash_history", root)
        val historyTail = if (historyPath.exists()) historyPath.readLines().takeLast(500).joinToString("\n") else ""
        File(dir, "bash_history.sh").writeText(historyTail)
        File(dir, "open-ttys.txt").writeText(
            runInChroot(root, listOf("who", "-a")).ifBlank { "# empty\n" }
        )
        File(dir, "tmux-panes.txt").writeText(
            runInChroot(root, listOf("tmux", "list-sessions")).ifBlank { "# no tmux\n" }
        )
        m.session = SessionSnapshot(
            shell = System.getenv("SHELL") ?: "/system/bin/sh",
            cwd = System.getProperty("user.dir") ?: "/data/data/com.devicetracker/files",
            envFile = "chroot/env-vars.txt",
            historyTail = "",
            openTtys = listOf("chroot/sessions/open-ttys.txt"),
            tmuxSessions = listOf("chroot/sessions/tmux-panes.txt"),
        )
    }

    // ----- Git repos -----
    private fun captureGitRepos(m: EnvManifest, root: String) {
        val dirsToScan = linkedSetOf<String>()
        if (root.isNotBlank()) dirsToScan += root
        dirsToScan += listOf("/data/data/com.devicetracker/files", "/data/media/0/dev", "/data/media/0/projects")
        val found = mutableSetOf<String>()
        for (base in dirsToScan) {
            try {
                File(base).walkTopDown().maxDepth(4).forEach { f ->
                    if (f.name == ".git" && found.add(f.parentFile!!.absolutePath)) {
                        captureOneGitRepo(m, f.parentFile!!)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun captureOneGitRepo(m: EnvManifest, repoDir: File) {
        try {
            val statusOut = gitCmd(repoDir, "status", "--porcelain=v1", "--branch")
            val branch = gitCmd(repoDir, "rev-parse", "--abbrev-ref", "HEAD").trim()
            val head = gitCmd(repoDir, "rev-parse", "HEAD").trim()
            val remote = gitCmd(repoDir, "remote", "get-url", "origin").trim()
            val uncommitted = gitCmd(repoDir, "diff", "HEAD")
            var diffFile = ""
            var diffInline = uncommitted
            if (uncommitted.length > 4096) {
                diffFile = "git-diffs/${sanitize(repoDir.absolutePath)}.diff"
                val f = File(repoBase, diffFile); f.parentFile?.mkdirs(); f.writeText(uncommitted)
                diffInline = ""
            }
            m.gitRepos.add(GitRepoState(
                path = repoDir.absolutePath, remote = remote, branch = branch,
                headCommit = head, statusClean = statusOut.isBlank(),
                uncommittedDiff = diffInline, uncommittedDiffFile = diffFile,
            ))
        } catch (e: Exception) { Log.w(TAG, "git capture failed for ${repoDir}", e) }
    }

    // ----- Config files -----
    private fun captureConfigs(m: EnvManifest, root: String) {
        listOf("/root/.bashrc", "/root/.profile", "/root/.vimrc", "/root/.gitconfig",
            "/etc/fstab", "/etc/hosts", "/etc/resolv.conf", "/etc/hostname",
            "/etc/ssh/sshd_config", "/etc/sudoers",
            "/etc/apt/sources.list", "/etc/apt/sources.list.d/").forEach {
            captureConfigFile(m, it, root)
        }
    }

    private fun captureConfigFile(m: EnvManifest, path: String, root: String) {
        try {
            val file = resolvePath(path, root)
            if (!file.exists()) return
            val bytes = file.readBytes()
            val sha = linkStore.sha256Hex(bytes)
            val looksCustom = looksUserModified(file, bytes)
            val pristineLink = LinkStore.KNOWN_BASELINES[path] ?: "distro-baseline"
            if (looksCustom) {
                val lm = loadOrCreateLinkManifest()
                val entry = linkStore.recordDiff(file.absolutePath, lm, pristineLink, null, bytes)
                linkStore.save(lm)
                m.configFiles.add(entry)
            } else {
                val lm = loadOrCreateLinkManifest()
                linkStore.recordHash(file.absolutePath, lm, sha, bytes.size.toLong())
                linkStore.save(lm)
            }
        } catch (e: Exception) { Log.w(TAG, "config capture failed for $path", e) }
    }

    private fun loadOrCreateLinkManifest(): LinkStore.Manifest = linkStore.load()

    private fun looksUserModified(f: File, bytes: ByteArray): Boolean {
        if (f.lastModified() > System.currentTimeMillis() - 365L*24*3600*1000) return true
        val text = bytes.decodeToString().take(4096)
        return text.contains("# User", true) || text.contains("local.conf", true)
    }

    // ----- Scripts -----
    private fun captureScripts(m: EnvManifest, root: String) {
        val scriptDirs = listOf("/usr/local/bin", "/root/bin", "/root/scripts", "/usr/local/sbin")
        scriptDirs.forEach { d ->
            val base = resolvePath(d, root)
            if (!base.isDirectory) return@forEach
            base.listFiles()?.filter { it.isFile && it.canRead() }?.forEach { s ->
                val bytes = s.readBytes()
                if (bytes.size <= 64 * 1024) {
                    val rel = "scripts/${sanitize(base.absolutePath)}/${s.name}"
                    val target = File(repoBase, rel); target.parentFile?.mkdirs(); s.copyTo(target, true)
                    m.scripts.add(LinkStore.FileLink(
                        path = s.absolutePath, mode = LinkStore.Mode.WHOLE,
                        sha256 = linkStore.sha256Hex(bytes), sizeBytes = bytes.size.toLong(),
                        link = "repo:$rel",
                    ))
                } else {
                    m.scripts.add(LinkStore.FileLink(
                        path = s.absolutePath, mode = LinkStore.Mode.LINK,
                        sha256 = linkStore.sha256Hex(bytes), sizeBytes = bytes.size.toLong(),
                        link = "unknown-source",
                    ))
                }
            }
        }
    }

    // ----- Cron -----
    private fun captureCron(m: EnvManifest, root: String) {
        val dir = File(repoBase, "$ENV_DIR/cron").apply { mkdirs() }
        listOf("/etc/crontab", "/etc/cron.d/", "/var/spool/cron/crontabs/").forEach { p ->
            val f = resolvePath(p, root)
            if (f.isDirectory) f.listFiles()?.filter { it.isFile }?.forEach {
                File(dir, it.name).writeText(it.readText())
            } else if (f.exists()) {
                File(dir, f.name).writeText(f.readText())
            }
        }
        m.cron = "cron/"
    }

    // ----- SSH -----
    private fun captureSsh(m: EnvManifest, root: String) {
        val dir = File(repoBase, "$ENV_DIR/ssh").apply { mkdirs() }
        listOf("/root/.ssh/known_hosts", "/root/.ssh/config", "/root/.ssh/authorized_keys").forEach { p ->
            val f = resolvePath(p, root)
            if (f.exists()) File(dir, f.name).writeText(f.readText())
        }
        m.ssh = "ssh/"
    }

    // ----- Helpers -----
    private fun runInChroot(root: String, cmd: List<String>): String {
        if (root.isBlank()) return host(cmd.first(), *cmd.drop(1).toTypedArray())
        return try {
            host("chroot", root, *(cmd.toTypedArray()))
        } catch (e: Exception) { "" }
    }

    private fun host(cmd: String, vararg args: String): String {
        return try {
            val p = ProcessBuilder(listOf(cmd, *args)).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.destroy()
            out
        } catch (e: Exception) { "" }
    }

    private fun gitCmd(repo: File, vararg args: String): String {
        return try {
            val p = ProcessBuilder(listOf("git", *args)).directory(repo)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.destroy()
            out
        } catch (e: Exception) { "" }
    }

    private fun resolvePath(p: String, root: String): File {
        val f = File(p)
        if (root.isNotBlank() && File("$root$p").exists()) return File("$root$p")
        if (root.isNotBlank() && File(root, p).exists()) return File(root, p)
        return f
    }

    private fun sanitize(p: String): String = p.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
