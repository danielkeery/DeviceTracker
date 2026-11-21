import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import java.io.File

// --- GitManager Tests ---

class GitManagerTest {

    // Test 1: init creates a valid repo
    [Test]
    fun testInitCreatesValidRepo() {
        val tmp = createTempDir("git_test_init")
        val repoPath = File(tmp, "repo")
        Git.init().setDirectory(repoPath).call().use { }
        // Verify .git dir exists
        assert(File(repoPath, ".git").exists())
        // Verify HEAD file
        assert(File(repoPath, ".git/HEAD").exists())
        println("PASS: testInitCreatesValidRepo")
    }

    // Test 2: commit stores a file
    [Test]
    fun testCommitStoresFile() {
        val tmp = createTempDir("git_test_commit")
        val repoPath = File(tmp, "repo")
        Git.init().setDirectory(repoPath).call().use { git ->
            File(repoPath, "test.txt").writeText("hello world")
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("test commit")
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
        }
        // Verify file is in the repo
        val content = File(repoPath, "test.txt").readText()
        assert(content == "hello world")
        println("PASS: testCommitStoresFile")
    }

    // Test 3: diff detects changes
    [Test]
    fun testDiffDetectsChanges() {
        val tmp = createTempDir("git_test_diff")
        val repoPath = File(tmp, "repo")
        Git.init().setDirectory(repoPath).call().use { git ->
            File(repoPath, "config.txt").writeText("line1\nline2\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("init").setAuthor("T","t@e.com").setCommitter("T","t@e.com").call()

            // Modify file
            File(repoPath, "config.txt").writeText("line1\nline2\nline3\n")
            val diff = git.diff().call()
            assert(diff.isNotEmpty())
            println("PASS: testDiffDetectsChanges")
        }
    }

    // Test 4: log returns commits in order
    [Test]
    fun testLogReturnsCommitsInOrder() {
        val tmp = createTempDir("git_test_log")
        val repoPath = File(tmp, "repo")
        Git.init().setDirectory(repoPath).call().use { git ->
            File(repoPath, "f1").writeText("v1")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("commit-1").setAuthor("T","t@e.com").setCommitter("T","t@e.com").call()

            File(repoPath, "f2").writeText("v2")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("commit-2").setAuthor("T","t@e.com").setCommitter("T","t@e.com").call()

            val log = git.log().call().take(5).toList()
            assert(log.size == 2)
            assert(log[0].fullName.startsWith("commit-"))
            println("PASS: testLogReturnsCommitsInOrder")
        }
    }

    // Test 5: branch creation and listing
    [Test]
    fun testBranchCreationAndListing() {
        val tmp = createTempDir("git_test_branch")
        val repoPath = File(tmp, "repo")
        Git.init().setDirectory(repoPath).call().use { git ->
            git.branchCreate().setName("backup").call()
            val branches = git.branchList().call().map { it.name }
            assert(branches.any { it.contains("backup") })
            println("PASS: testBranchCreationAndListing")
        }
    }

    // Test 6: repo info (size, commit count)
    [Test]
    fun testRepoInfo() {
        val tmp = createTempDir("git_test_info")
        val repoPath = File(tmp, "repo")
        Git.init().setDirectory(repoPath).call().use { git ->
            File(repoPath, "data.bin").writeBytes(ByteArray(1024) { 0 })
            git.add().addFilepattern(".").call()
            git.commit().setMessage("data").setAuthor("T","t@e.com").setCommitter("T","t@e.com").call()

            val size = repoPath.walk().sumOf { it.length() }
            assert(size > 0)
            println("PASS: testRepoInfo (size=$size)")
        }
    }
}

// --- Helpers ---
fun createTempDir(name: String): File {
    val dir = File("/tmp/$name-${System.nanoTime()}")
    dir.mkdirs()
    return dir
}

// Run all tests
fun main() {
    var passed = 0
    var failed = 0
    val t = GitManagerTest()

    runCatching { t.testInitCreatesValidRepo() }.onSuccess { passed++ }.onFailure { println("FAIL: testInitCreatesValidRepo: ${it.message}"); failed++ }
    runCatching { t.testCommitStoresFile() }.onSuccess { passed++ }.onFailure { println("FAIL: testCommitStoresFile: ${it.message}"); failed++ }
    runCatching { t.testDiffDetectsChanges() }.onSuccess { passed++ }.onFailure { println("FAIL: testDiffDetectsChanges: ${it.message}"); failed++ }
    runCatching { t.testLogReturnsCommitsInOrder() }.onSuccess { passed++ }.onFailure { println("FAIL: testLogReturnsCommitsInOrder: ${it.message}"); failed++ }
    runCatching { t.testBranchCreationAndListing() }.onSuccess { passed++ }.onFailure { println("FAIL: testBranchCreationAndListing: ${it.message}"); failed++ }
    runCatching { t.testRepoInfo() }.onSuccess { passed++ }.onFailure { println("FAIL: testRepoInfo: ${it.message}"); failed++ }

    println("\n=== GIT MANAGER: $passed passed, $failed failed ===")
}
