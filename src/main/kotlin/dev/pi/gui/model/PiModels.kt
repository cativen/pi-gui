package dev.pi.gui.model

/**
 * Content blocks that can appear inside an assistant message.
 *
 * Mirrors pi's wire format. Note that pi's *session file* format spells tool calls
 * `{id, name, arguments}` while the *RPC wire* format spells them
 * `{toolCallId, toolName, input}`; both are normalized into [ToolCallBlock] on read.
 */
sealed class ContentBlock {
    data class Text(var text: String) : ContentBlock()

    data class Thinking(var thinking: String) : ContentBlock()

    data class ToolCall(
        var toolCallId: String,
        var toolName: String,
        /** Parsed arguments, when available. */
        var input: String? = null,
        /** Raw streamed JSON fragment, accumulated during streaming. */
        var rawInput: StringBuilder = StringBuilder(),
    ) : ContentBlock() {
        fun argumentsText(): String = input ?: rawInput.toString()
    }

    data class Image(val mimeType: String?) : ContentBlock()
}

/** One renderable message in the transcript. */
sealed class PiMessage {
    abstract val timestamp: Long?

    data class User(
        val text: String,
        val imageCount: Int = 0,
        override val timestamp: Long? = null,
    ) : PiMessage()

    data class Assistant(
        val blocks: MutableList<ContentBlock> = mutableListOf(),
        var model: String? = null,
        var provider: String? = null,
        var errorMessage: String? = null,
        var usage: Usage? = null,
        /** Wall-clock time this step took, when known. */
        var durationMs: Long? = null,
        override val timestamp: Long? = null,
    ) : PiMessage()

    data class ToolResult(
        val toolCallId: String,
        val toolName: String?,
        val text: String,
        val isError: Boolean,
        override val timestamp: Long? = null,
    ) : PiMessage()

    /** Compaction markers, session notices and other non-conversational entries. */
    data class Notice(
        val text: String,
        override val timestamp: Long? = null,
    ) : PiMessage()
}

data class Usage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val costTotal: Double = 0.0,
) {
    fun totalTokens(): Long = input + output + cacheRead + cacheWrite

    /** Prompt-side tokens: everything the model had to read for this step. */
    fun promptTokens(): Long = input + cacheRead + cacheWrite

    /**
     * Share of the prompt that was served from the provider's cache, in `0.0..1.0`.
     * Null when this step had no prompt tokens at all (nothing to hit or miss).
     */
    fun cacheHitRate(): Double? {
        val prompt = promptTokens()
        return if (prompt <= 0) null else cacheRead.toDouble() / prompt
    }
}

/** A pi session discovered on disk. */
data class SessionInfo(
    val id: String,
    val filePath: String,
    val cwd: String,
    val name: String?,
    val lastModified: Long,
    val createdAt: Long,
    /** First user message, used as a fallback title. */
    val preview: String?,
    val messageCount: Int,
) {
    fun displayTitle(): String {
        name?.takeIf { it.isNotBlank() }?.let { return it }
        preview?.takeIf { it.isNotBlank() }?.let { p ->
            val oneLine = p.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length > 80) oneLine.take(80) + "…" else oneLine
        }
        return "(empty session)"
    }
}

/** Model advertised by `get_available_models`. */
data class ModelOption(
    val provider: String,
    val id: String,
    val reasoning: Boolean = false,
) {
    val qualified: String get() = "$provider/$id"
    override fun toString(): String = id
}

/** Live agent state from `get_state`. */
data class AgentState(
    val sessionId: String?,
    val sessionFile: String?,
    val sessionName: String?,
    val provider: String?,
    val modelId: String?,
    val thinkingLevel: String?,
    val isStreaming: Boolean,
    val isCompacting: Boolean,
    val messageCount: Int,
)

/** What the agent is doing right now, surfaced in the status strip. */
sealed class AgentPhase {
    object Idle : AgentPhase()
    object WaitingModel : AgentPhase()
    data class RunningTools(val tools: List<String>) : AgentPhase()
    data class Compacting(val reason: String) : AgentPhase()
    data class Retrying(val attempt: Int, val maxAttempts: Int, val error: String) : AgentPhase()
}
