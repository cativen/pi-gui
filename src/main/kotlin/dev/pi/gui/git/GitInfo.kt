package dev.pi.gui.git

import java.io.File

/**
 * Reads the checked-out branch straight from `.git/HEAD`.
 *
 * Deliberately avoids the Git4Idea plugin API: depending on it would make Pi GUI require the
 * Git plugin to be installed and enabled, and all we need is one short file.
 */
object GitInfo {

    private data class Cached(val headPath: String, val mtime: Long, val branch: String?)

    @Volatile
    private var cache: Cached? = null

    /** Current branch name, a short SHA when detached, or null outside a work tree. */
    fun currentBranch(projectPath: String?): String? {
        val root = projectPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        val head = resolveHeadFile(root) ?: return null

        val mtime = head.lastModified()
        cache?.let { if (it.headPath == head.path && it.mtime == mtime) return it.branch }

        val branch = parseHead(head)
        cache = Cached(head.path, mtime, branch)
        return branch
    }

    /**
     * Walks up from the project directory looking for `.git`. A linked worktree or submodule has
     * `.git` as a *file* containing `gitdir: <path>`, which is followed here.
     */
    private fun resolveHeadFile(projectRoot: File): File? {
        var dir: File? = try { projectRoot.canonicalFile } catch (e: Exception) { projectRoot }
        while (dir != null) {
            val dotGit = File(dir, ".git")
            if (dotGit.isDirectory) {
                return File(dotGit, "HEAD").takeIf { it.isFile }
            }
            if (dotGit.isFile) {
                val pointer = try { dotGit.readText().trim() } catch (e: Exception) { "" }
                val target = pointer.removePrefix("gitdir:").trim()
                if (target.isNotEmpty()) {
                    val gitDir = File(target).let { if (it.isAbsolute) it else File(dir, target) }
                    return File(gitDir, "HEAD").takeIf { it.isFile }
                }
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun parseHead(head: File): String? {
        val text = try { head.readText().trim() } catch (e: Exception) { return null }
        if (text.isEmpty()) return null
        if (text.startsWith("ref:")) {
            // "ref: refs/heads/feature/x" -> "feature/x" (branch names may contain slashes)
            val ref = text.removePrefix("ref:").trim()
            return ref.removePrefix("refs/heads/").takeIf { it.isNotEmpty() }
        }
        // Detached HEAD: the file holds a raw commit SHA.
        return text.takeIf { it.length >= 7 }?.take(7)
    }

    /** Drops the cache; used by tests that rewrite HEAD within one millisecond. */
    fun invalidate() {
        cache = null
    }
}
