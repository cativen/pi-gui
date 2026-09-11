package dev.pi.gui

import dev.pi.gui.git.GitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitInfoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun clearCache() = GitInfo.invalidate()

    private fun repoWithHead(content: String): File {
        val root = tmp.newFolder("project")
        val gitDir = File(root, ".git").apply { mkdirs() }
        File(gitDir, "HEAD").writeText(content)
        return root
    }

    @Test
    fun `reads a simple branch name`() {
        val root = repoWithHead("ref: refs/heads/main\n")
        assertEquals("main", GitInfo.currentBranch(root.path))
    }

    /** Branch names legitimately contain slashes; only the refs/heads/ prefix may be stripped. */
    @Test
    fun `keeps slashes inside branch names`() {
        val root = repoWithHead("ref: refs/heads/feature/nested/thing\n")
        assertEquals("feature/nested/thing", GitInfo.currentBranch(root.path))
    }

    @Test
    fun `detached head shows a short sha`() {
        val root = repoWithHead("9f2c1ab7d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8\n")
        assertEquals("9f2c1ab", GitInfo.currentBranch(root.path))
    }

    @Test
    fun `finds the repository from a nested directory`() {
        val root = repoWithHead("ref: refs/heads/dev\n")
        val nested = File(root, "src/main/kotlin").apply { mkdirs() }
        assertEquals("dev", GitInfo.currentBranch(nested.path))
    }

    /** Linked worktrees and submodules store `.git` as a file pointing elsewhere. */
    @Test
    fun `follows a gitdir pointer file`() {
        val real = tmp.newFolder("realgit")
        File(real, "HEAD").writeText("ref: refs/heads/worktree-branch\n")

        val root = tmp.newFolder("linked")
        File(root, ".git").writeText("gitdir: ${real.absolutePath}\n")

        assertEquals("worktree-branch", GitInfo.currentBranch(root.path))
    }

    @Test
    fun `returns null outside a repository`() {
        assertNull(GitInfo.currentBranch(tmp.newFolder("plain").path))
    }

    @Test
    fun `returns null for a blank or missing path`() {
        assertNull(GitInfo.currentBranch(null))
        assertNull(GitInfo.currentBranch(""))
    }

    @Test
    fun `handles an empty HEAD without throwing`() {
        assertNull(GitInfo.currentBranch(repoWithHead("").path))
    }

    @Test
    fun `picks up a branch switch`() {
        val root = repoWithHead("ref: refs/heads/before\n")
        assertEquals("before", GitInfo.currentBranch(root.path))

        File(root, ".git/HEAD").writeText("ref: refs/heads/after\n")
        GitInfo.invalidate() // same-millisecond rewrite would otherwise hit the mtime cache
        assertEquals("after", GitInfo.currentBranch(root.path))
    }
}
