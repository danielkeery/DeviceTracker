package com.devicetracker.tracker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Space-saving manifest system.
 *
 * Instead of storing every file's contents in the git repo, the tracker stores:
 *  1. LINK     — "repo link instead of contents"
 *  2. HASH     — "file hash for known files"
 *  3. DIFF     — "diffs for config changes" against a pristine baseline
 *  4. PACKAGE  — "package link instead of contents"
 */
class LinkStore(private val repoBase: File) {

    companion object {
        const val MANIFEST_NAME = "links/manifest.json"
        const val PATCH_DIR = "links/patches"
        const val HASH_DIR = "links/hashes"

        val KNOWN_BASELINES: Map<String, String> = mapOf(
            "/system/build.prop" to "vendor-image",
            "/vendor/build.prop" to "vendor-image",
        )
    }

    enum class Mode { LINK, HASH, DIFF, PACKAGE, WHOLE }

    data class PackageLink(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val source: String,
        val apkSha256: String,
        val installedAsSystem: Boolean,
    )

    data class FileLink(
        val path: String,
        var mode: Mode = Mode.WHOLE,
        var link: String? = null,
        var sha256: String = "",
        var sizeBytes: Long = 0,
        var patch: String? = null,
        var extra: MutableMap<String, String> = mutableMapOf(),
    )

    data class Manifest(
        var revision: Int = 1,
        var files: MutableList<FileLink> = mutableListOf(),
        var packages: MutableList<PackageLink> = mutableListOf(),
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val manifestFile = File(repoBase, MANIFEST_NAME)

    fun load(): Manifest {
        if (!manifestFile.exists()) return Manifest()
        return try {
            val type = object : TypeToken<Manifest>() {}.type
            gson.fromJson(manifestFile.readText(), type) ?: Manifest()
        } catch (e: Exception) { Manifest() }
    }

    fun save(manifest: Manifest) {
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(gson.toJson(manifest))
    }

    fun recordLink(path: String, link: String, manifest: Manifest, sha256: String = "", sizeBytes: Long = 0): FileLink {
        val entry = upsert(manifest, path)
        entry.mode = Mode.LINK
        entry.link = link
        entry.sha256 = sha256
        entry.sizeBytes = sizeBytes
        return entry
    }

    fun recordHash(path: String, manifest: Manifest, sha256: String, sizeBytes: Long): FileLink {
        val entry = upsert(manifest, path)
        entry.mode = Mode.HASH
        entry.sha256 = sha256
        entry.sizeBytes = sizeBytes
        return entry
    }

    fun recordDiff(path: String, manifest: Manifest, link: String,
        baselinePristine: ByteArray?, modifiedBytes: ByteArray): FileLink {
        val entry = upsert(manifest, path)
        entry.mode = Mode.DIFF
        entry.link = link
        entry.sha256 = sha256Hex(modifiedBytes)
        entry.sizeBytes = modifiedBytes.size.toLong()
        val patchText = unifiedDiff("pristine", "modified",
            baselinePristine?.decodeToString() ?: "", modifiedBytes.decodeToString())
        if (patchText.isNotBlank()) {
            val patchRel = "$PATCH_DIR/${sanitize(path)}.patch"
            val patchFile = File(repoBase, patchRel)
            patchFile.parentFile?.mkdirs()
            patchFile.writeText(patchText)
            entry.patch = patchRel
        } else {
            entry.patch = null
        }
        return entry
    }

    fun recordPackage(manifest: Manifest, pkg: PackageLink) {
        val i = manifest.packages.indexOfFirst { it.packageName == pkg.packageName }
        if (i >= 0) manifest.packages[i] = pkg else manifest.packages.add(pkg)
    }

    fun recordWhole(path: String, manifest: Manifest, sha256: String, sizeBytes: Long): FileLink {
        val entry = upsert(manifest, path)
        entry.mode = Mode.WHOLE
        entry.sha256 = sha256
        entry.sizeBytes = sizeBytes
        return entry
    }

    private fun upsert(manifest: Manifest, path: String): FileLink {
        val existing = manifest.files.find { it.path == path }
        return if (existing != null) existing else FileLink(path = path, mode = Mode.WHOLE).also { manifest.files.add(it) }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) d.update(buf, 0, n)
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    fun unifiedDiff(oldName: String, newName: String, old: String, new: String): String {
        val oldLines = old.lines()
        val newLines = new.lines()
        if (oldLines == newLines) return ""
        val sb = StringBuilder()
        sb.appendLine("--- $oldName")
        sb.appendLine("+++ $newName")
        sb.appendLine("@@ -1,${oldLines.size} +1,${newLines.size} @@")
        for (i in 0 until oldLines.size) sb.appendLine("-${oldLines[i]}")
        for (i in 0 until newLines.size) sb.appendLine("+${newLines[i]}")
        return sb.toString().trimEnd()
    }

    private fun sanitize(p: String): String = p.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
