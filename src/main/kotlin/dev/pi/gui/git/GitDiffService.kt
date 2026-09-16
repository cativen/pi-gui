package dev.pi.gui.git

import com.intellij.openapi.diagnostic.Logger
import dev.pi.gui.PiLocator
import java.io.File
import java.util.concurrent.TimeUnit

data class DiffStat(val added: Int, val removed: Int) {
    fun label(): String = buildString {
        if (added > 0) append("+").append(added)
        if (added > 0 && removed > 0) append(" ")
        if (removed > 0) append("−").append(removed)
    }

    val hasChanges: Boolean get() = added > 0 || removed > 0
}

data class CommitDiff(
    val text: String,
    val fileCount: Int,
    val truncated: Boolean,
)

/**
 * Thin wrapper over the `git` CLI for showing what a conversation changed.
 *
 * Uses the CLI rather than the Git4Idea plugin API so Pi GUI keeps working in IDEs where the Git
 * plugin is disabled, matching how [GitInfo] reads the branch.
 */
object GitDiffService {

    private val LOG = Logger.getInstance(GitDiffService::class.java)

    /** Content of [absolutePath] at HEAD, or null when the file is untracked or unreadable. */
    fun contentAtHead(absolutePath: String, repoRoot: File): String? {
        val relative = relativeTo(absolutePath, repoRoot) ?: return null
        val result = run(listOf("git", "show", "HEAD:$relative"), repoRoot) ?: return null
        // A new file has no HEAD revision; git exits non-zero and that is expected, not an error.
        return if (result.exitCode == 0) result.output else null
    }

    /** Added/removed line counts for the working tree vs HEAD. */
    fun statFor(absolutePath: String, repoRoot: File): DiffStat? {
        val relative = relativeTo(absolutePath, repoRoot) ?: return null
        // --no-index would ignore staging; HEAD compares against the last commit, which is what
        // "what did this conversation change" means to a reader.
        val result = run(listOf("git", "diff", "--numstat", "HEAD", "--", relative), repoRoot)
            ?: return null
        if (result.exitCode != 0) return null

        val line = result.output.lineSequence().firstOrNull { it.isNotBlank() } ?: return DiffStat(0, 0)
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return DiffStat(0, 0)
        // Binary files report "-" instead of a count.
        val added = parts[0].toIntOrNull() ?: 0
        val removed = parts[1].toIntOrNull() ?: 0
        return DiffStat(added, removed)
    }

    /** Untracked files have no HEAD side, so the diff is "everything added". */
    fun isTracked(absolutePath: String, repoRoot: File): Boolean {
        val relative = relativeTo(absolutePath, repoRoot) ?: return false
        val result = run(listOf("git", "ls-files", "--error-unmatch", "--", relative), repoRoot)
        return result?.exitCode == 0
    }

    /** Repository root containing [path], or null when it is not in a work tree. */
    fun repoRootFor(path: String?): File? {
        val start = path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() } ?: return null
        val dir = if (start.isDirectory) start else start.parentFile ?: return null
        val result = run(listOf("git", "rev-parse", "--show-toplevel"), dir) ?: return null
        if (result.exitCode != 0) return null
        return result.output.trim().takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
    }

    /**
     * Unified diff for exactly the paths included in the IDE Commit workflow.
     *
     * Tracked files are compared with HEAD so staged and unstaged content is represented by the
     * same snapshot the user sees in the Commit tool window. Untracked files need a separate
     * `--no-index` invocation because regular `git diff` intentionally omits them.
     */
    fun commitDiff(
        absolutePaths: Collection<String>,
        projectPath: String? = null,
        maxChars: Int = MAX_COMMIT_DIFF_CHARS,
    ): CommitDiff {
        val paths = absolutePaths.filter { it.isNotBlank() }.distinct()
        if (paths.isEmpty()) return CommitDiff("", 0, false)

        val projectRoot = repoRootFor(projectPath)
        val grouped = linkedMapOf<File, MutableList<String>>()
        paths.forEach { absolutePath ->
            val file = File(absolutePath)
            val root = repoRootFor(absolutePath)
                ?: repoRootFor(file.parentFile?.path)
                ?: projectRoot
                ?: return@forEach
            relativeTo(absolutePath, root)?.let { relative ->
                grouped.getOrPut(root) { mutableListOf() }.add(relative)
            }
        }

        val output = StringBuilder()
        var truncated = false

        fun append(text: String) {
            if (text.isBlank() || truncated) return
            val separator = if (output.isEmpty()) "" else "\n"
            val available = maxChars - output.length - separator.length
            if (available <= 0) {
                truncated = true
                return
            }
            output.append(separator)
            if (text.length <= available) {
                output.append(text.trimEnd())
            } else {
                output.append(text.take(available).trimEnd())
                truncated = true
            }
        }

        grouped.forEach { (root, relatives) ->
            val tracked = relatives.filter { relative ->
                run(listOf("git", "ls-files", "--error-unmatch", "--", relative), root)?.exitCode == 0
            }
            tracked.chunked(100).forEach { chunk ->
                val result = run(
                    listOf("git", "diff", "--no-color", "--no-ext-diff", "--unified=3", "HEAD", "--") + chunk,
                    root,
                )
                if (result?.exitCode == 0) append(result.output)
            }

            relatives.filterNot { it in tracked }.forEach { relative ->
                val result = run(
                    listOf("git", "diff", "--no-color", "--no-ext-diff", "--no-index", "--", "/dev/null", relative),
                    root,
                )
                // `git diff --no-index` returns 1 when it successfully finds differences.
                if (result != null && result.exitCode in 0..1) append(result.output)
            }
        }

        if (truncated) output.append("\n\n[Diff truncated by Pi GUI]")
        return CommitDiff(output.toString(), paths.size, truncated)
    }

    private fun relativeTo(absolutePath: String, repoRoot: File): String? {
        val file = try { File(absolutePath).canonicalFile } catch (e: Exception) { File(absolutePath) }
        val root = try { repoRoot.canonicalFile } catch (e: Exception) { repoRoot }
        val filePath = file.path.replace('\\', '/')
        val rootPath = root.path.replace('\\', '/').trimEnd('/')
        return if (filePath.startsWith("$rootPath/")) filePath.removePrefix("$rootPath/") else null
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun run(command: List<String>, workingDir: File): CommandResult? = try {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)
            .also { it.environment().putAll(PiLocator.shellEnvironment()) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            CommandResult(process.exitValue(), output)
        }
    } catch (e: Exception) {
        LOG.debug("git command failed: ${command.joinToString(" ")}", e)
        null
    }

    private const val MAX_COMMIT_DIFF_CHARS = 120_000
}
