package com.devicetracker.tracker

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the space-saving LinkStore.
 * Covers all 5 modes: WHOLE, HASH, LINK, DIFF, PACKAGE.
 */
class LinkStoreUnitTest {

    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var repo: File
    private lateinit var store: LinkStore

    @Before
    fun setup() {
        repo = tmp.newFolder("repo")
        store = LinkStore(repo)
    }

    // --- load/save round-trip ---
    @Test
    fun test_LoadSaveRoundTrip() {
        val m = store.load()
        assertEquals(1, m.revision)
        assertTrue(m.files.isEmpty())

        store.save(m)
        assertTrue(File(repo, LinkStore.MANIFEST_NAME).exists())

        val m2 = store.load()
        assertEquals(m.revision, m2.revision)
    }

    // --- WHOLE mode: small critical files stored directly ---
    @Test
    fun test_Whole_RecordsShaAndSize() {
        val m = store.load()
        val entry = store.recordWhole("/root/.keystore", m, "deadbeef", 4096L)
        assertEquals(LinkStore.Mode.WHOLE, entry.mode)
        assertEquals("deadbeef", entry.sha256)
        assertEquals(4096, entry.sizeBytes)
        store.save(m)
    }

    // --- HASH mode: known file, verify on restore ---
    @Test
    fun test_Hash_RecordsHashNoBytes() {
        val m = store.load()
        val entry = store.recordHash("/system/build.prop", m, "sha256abc", 1234L)
        assertEquals(LinkStore.Mode.HASH, entry.mode)
        assertEquals("sha256abc", entry.sha256)
        assertNull(entry.link)
    }

    // --- LINK mode: repo link instead of contents ---
    @Test
    fun test_Link_RecordsURL() {
        val m = store.load()
        val url = "https://github.com/nvim/neovim/releases/download/v0.9/nvim.tar.gz"
        val entry = store.recordLink("/usr/local/bin/nvim", url, m, "abc", 1024L)
        assertEquals(LinkStore.Mode.LINK, entry.mode)
        assertEquals(url, entry.link)
    }

    // --- DIFF mode: config changes as patch vs pristine ---
    @Test
    fun test_Diff_GeneratesPatchFile() {
        val m = store.load()
        val pristine = "export PATH=/usr/bin\n".toByteArray()
        val modified = "export PATH=/usr/bin\nexport GOPATH=/root/go\n".toByteArray()

        val entry = store.recordDiff(
            "/root/.bashrc", m,
            link = "https://git.kernel.org/pub/scm/bash/bash.git",
            baselinePristine = pristine, modifiedBytes = modified,
        )
        assertEquals(LinkStore.Mode.DIFF, entry.mode)
        assertNotNull(entry.patch)

        // Patch file actually exists
        val patchFile = File(repo, entry.patch!!)
        assertTrue(patchFile.exists())
        val patchText = patchFile.readText()
        assertTrue(patchText.contains("---"))
        assertTrue(patchText.contains("+"))
    }

    @Test
    fun test_Diff_IdenticalFilesProducesNoPatch() {
        val m = store.load()
        val identical = "same content\n".toByteArray()
        val entry = store.recordDiff("/root/.same", m, "link", identical, identical)
        // unifiedDiff returns "" for identical
        assertNull(entry.patch)
    }

    // --- PACKAGE mode: package link instead of contents ---
    @Test
    fun test_Package_RecordsMetadata() {
        val m = store.load()
        val pkg = LinkStore.PackageLink(
            packageName = "com.termux",
            versionName = "0.118",
            versionCode = 118L,
            source = "https://f-droid.org/repo/com.termux_118.apk",
            apkSha256 = "pkg_sha",
            installedAsSystem = false,
        )
        store.recordPackage(m, pkg)
        assertEquals(1, m.packages.size)
        assertEquals("com.termux", m.packages[0].packageName)
        assertEquals("pkg_sha", m.packages[0].apkSha256)
    }

    // --- sha256 round-trip ---
    @Test
    fun test_Sha256RoundTrip() {
        val hello = "hello".toByteArray()
        val hex = store.sha256Hex(hello)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hex.lowercase())
    }

    // --- upsert replaces existing ---
    @Test
    fun test_UpsertReplacesExisting() {
        val m = store.load()
        store.recordHash("/a", m, "sha1", 100)
        store.recordHash("/a", m, "sha2", 200)  // should replace
        assertEquals(1, m.files.size)
        assertEquals("sha2", m.files[0].sha256)
    }

    // --- manifest saves patches dir ---
    @Test
    fun test_PatchDirCreated() {
        val m = store.load()
        store.recordDiff("/x", m, "l", "a".toByteArray(), "b".toByteArray())
        assertTrue(File(repo, LinkStore.PATCH_DIR).isDirectory)
    }
}
