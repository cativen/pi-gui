package dev.pi.gui.rpc

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage

/**
 * Accumulates a streaming assistant message from `message_update` deltas.
 *
 * pi streams content per *content index*: each index is one block (text / thinking / tool call)
 * built up by `*_start`, `*_delta` and `*_end` events. `message_end` then delivers the
 * authoritative final message, which replaces whatever we accumulated.
 */
class StreamingAssistant {
    var current: PiMessage.Assistant? = null
        private set

    val isStreaming: Boolean get() = current != null

    fun begin(model: String? = null, provider: String? = null) {
        current = PiMessage.Assistant(model = model, provider = provider)
    }

    /** Seed from a `message_start` snapshot. */
    fun snapshot(message: JsonObject) {
        current = PiJson.parseAssistantMessage(message) ?: PiMessage.Assistant()
    }

    fun end() { current = null }

    /** Seeds a partial reply so the streaming render path can be driven without an agent. */
    @org.jetbrains.annotations.TestOnly
    fun seedForTest(message: PiMessage.Assistant) { current = message }

    /** Apply one `assistantMessageEvent`. Returns true when the UI should repaint. */
    fun applyDelta(event: JsonObject): Boolean {
        val msg = current ?: PiMessage.Assistant().also { current = it }
        val type = event.get("type")?.asStringOrNull() ?: return false
        val index = event.get("contentIndex")?.let { if (it.isJsonPrimitive) it.asInt else -1 } ?: -1
        if (index < 0) return false

        fun ensureSize(upTo: Int) {
            while (msg.blocks.size <= upTo) msg.blocks.add(ContentBlock.Text(""))
        }

        when (type) {
            "text_start" -> {
                ensureSize(index)
                if (msg.blocks[index] !is ContentBlock.Text) msg.blocks[index] = ContentBlock.Text("")
            }
            "text_delta" -> {
                ensureSize(index)
                val delta = event.get("delta")?.asStringOrNull() ?: return false
                val block = msg.blocks[index]
                if (block is ContentBlock.Text) block.text += delta
                else msg.blocks[index] = ContentBlock.Text(delta)
            }
            "text_end" -> {
                ensureSize(index)
                val content = event.get("content")?.asStringOrNull()
                if (content != null) msg.blocks[index] = ContentBlock.Text(content)
            }
            "thinking_start" -> {
                ensureSize(index)
                if (msg.blocks[index] !is ContentBlock.Thinking) msg.blocks[index] = ContentBlock.Thinking("")
            }
            "thinking_delta" -> {
                ensureSize(index)
                val delta = event.get("delta")?.asStringOrNull() ?: return false
                val block = msg.blocks[index]
                if (block is ContentBlock.Thinking) block.thinking += delta
                else msg.blocks[index] = ContentBlock.Thinking(delta)
            }
            "thinking_end" -> {
                ensureSize(index)
                val content = event.get("content")?.asStringOrNull()
                if (content != null) msg.blocks[index] = ContentBlock.Thinking(content)
            }
            "toolcall_start" -> {
                ensureSize(index)
                val name = event.get("toolName")?.asStringOrNull() ?: return false
                val id = event.get("id")?.asStringOrNull() ?: ""
                val existing = msg.blocks[index]
                if (existing is ContentBlock.ToolCall) {
                    existing.toolCallId = id.ifEmpty { existing.toolCallId }
                    existing.toolName = name
                } else {
                    msg.blocks[index] = ContentBlock.ToolCall(id, name)
                }
            }
            "toolcall_delta" -> {
                ensureSize(index)
                val delta = event.get("delta")?.asStringOrNull() ?: return false
                val block = msg.blocks[index]
                if (block is ContentBlock.ToolCall) {
                    block.rawInput.append(delta)
                    event.get("id")?.asStringOrNull()?.takeIf { it.isNotEmpty() }?.let { block.toolCallId = it }
                    event.get("toolName")?.asStringOrNull()?.takeIf { it.isNotEmpty() }?.let { block.toolName = it }
                } else return false
            }
            "toolcall_end" -> {
                ensureSize(index)
                val call = event.getAsJsonObjectOrNull("toolCall") ?: return false
                msg.blocks[index] = ContentBlock.ToolCall(
                    toolCallId = call.get("id")?.asStringOrNull() ?: "",
                    toolName = call.get("name")?.asStringOrNull() ?: "tool",
                    input = call.get("arguments")?.let { PiJson.pretty(it) },
                )
            }
            else -> return false
        }
        return true
    }
}

internal fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonElement.asStringOrNullSafe(): String? = if (isJsonPrimitive) asString else null
