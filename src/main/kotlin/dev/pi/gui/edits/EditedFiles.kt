package dev.pi.gui.edits

import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import java.io.File

data class EditedFile(
    val absolutePath: String,
    /** Path relative to the project root when it lives inside it. */
    val displayPath: String,
    /** How many successful write/edit calls touched it. */
    val editCount: Int,
)

/**
 * Works out which files a conversation actually changed.
 *
 * Evidence is only ever a `write`/`edit` tool call **whose result came back without an error** —
 * never the assistant's prose. A model saying "I updated config.kt" is not proof that anything
 * was written, and a failed call wrote nothing.
 */
object EditedFiles {

    fun isWriteTool(name: String): Boolean = name.lowercase().let {
        it == "write" || it.startsWith("write_") || it.endsWith(".write") || it.endsWith("_write")
    }

    fun isEditTool(name: String): Boolean = name.lowercase().let {
        it == "edit" || it.startsWith("edit_") || it.endsWith(".edit") || it.endsWith("_edit") ||
            it.contains("str_replace") || it.contains("replace_editor")
    }

    private fun isFileWritingTool(name: String) = isWriteTool(name) || isEditTool(name)

    /** Distinct files written during [messages], in first-seen order. */
    fun collect(messages: List<PiMessage>, projectPath: String?): List<EditedFile> {
        // A tool call is only trustworthy once its result arrives, and results follow the call.
        val failedOrMissing = HashSet<String>()
        val succeeded = HashSet<String>()
        messages.forEach { message ->
            if (message is PiMessage.ToolResult) {
                if (message.isError) failedOrMissing.add(message.toolCallId)
                else succeeded.add(message.toolCallId)
            }
        }

        val counts = LinkedHashMap<String, Int>()
        messages.forEach { message ->
            if (message !is PiMessage.Assistant) return@forEach
            message.blocks.forEach { block ->
                if (block !is ContentBlock.ToolCall) return@forEach
                if (!isFileWritingTool(block.toolName)) return@forEach
                if (block.toolCallId !in succeeded) return@forEach

                val raw = pathArgument(block.argumentsText()) ?: return@forEach
                val resolved = resolve(raw, projectPath) ?: return@forEach
                counts[resolved] = (counts[resolved] ?: 0) + 1
            }
        }

        return counts.map { (path, count) ->
            EditedFile(
                absolutePath = path,
                displayPath = relativize(path, projectPath),
                editCount = count,
            )
        }
    }

    /**
     * Pulls `file_path` (or `path`) out of the tool arguments.
     * Arguments arrive as pretty-printed JSON, so a small scan beats a full parse here.
     */
    fun pathArgument(argumentsJson: String): String? {
        listOf("file_path", "path").forEach { key ->
            val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(argumentsJson)
            if (match != null) {
                val value = unescapeJson(match.groupValues[1])
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun unescapeJson(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

    private fun resolve(rawPath: String, projectPath: String?): String? {
        val file = File(rawPath)
        val resolved = when {
            file.isAbsolute -> file
            projectPath != null -> File(projectPath, rawPath)
            else -> return null
        }
        return try { resolved.canonicalPath } catch (e: Exception) { resolved.absolutePath }
    }

    private fun relativize(path: String, projectPath: String?): String {
        if (projectPath == null) return path
        val base = try { File(projectPath).canonicalPath } catch (e: Exception) { projectPath }
        val normalized = path.replace('\\', '/')
        val prefix = base.replace('\\', '/').trimEnd('/') + "/"
        return if (normalized.startsWith(prefix)) normalized.removePrefix(prefix) else path
    }
}
