package dev.pi.gui.rpc

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.Usage

/**
 * Conversions from pi's JSON shapes into the plugin's model types.
 *
 * Two spellings of a tool call exist in the wild and both are accepted here:
 *  - session files: `{type:"toolCall", id, name, arguments}`
 *  - RPC wire:      `{type:"toolCall", toolCallId, toolName, input}`
 */
object PiJson {
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    fun pretty(element: JsonElement): String = when {
        element.isJsonPrimitive -> element.asString
        else -> prettyGson.toJson(element)
    }

    /** Parse any `AgentMessage` into the renderable model, or null when not displayable. */
    fun parseMessage(message: JsonObject): PiMessage? {
        return when (message.get("role")?.asStringOrNull()) {
            "user" -> parseUserMessage(message)
            "assistant" -> parseAssistantMessage(message)
            "toolResult" -> parseToolResult(message)
            "custom" -> parseCustom(message)
            else -> null
        }
    }

    fun parseUserMessage(message: JsonObject): PiMessage.User? {
        val timestamp = message.get("timestamp")?.asLongOrNull()
        val content = message.get("content") ?: return null

        if (content.isJsonPrimitive) {
            return PiMessage.User(content.asString, 0, timestamp)
        }
        if (!content.isJsonArray) return null

        val sb = StringBuilder()
        var images = 0
        content.asJsonArray.forEach { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (obj.get("type")?.asStringOrNull()) {
                "text" -> obj.get("text")?.asStringOrNull()?.let {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(it)
                }
                "image" -> images++
            }
        }
        return PiMessage.User(sb.toString(), images, timestamp)
    }

    fun parseAssistantMessage(message: JsonObject): PiMessage.Assistant? {
        val blocks = mutableListOf<ContentBlock>()
        message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (obj.get("type")?.asStringOrNull()) {
                "text" -> obj.get("text")?.asStringOrNull()?.let { blocks.add(ContentBlock.Text(it)) }
                "thinking" -> obj.get("thinking")?.asStringOrNull()?.let { blocks.add(ContentBlock.Thinking(it)) }
                "toolCall" -> blocks.add(parseToolCallBlock(obj))
                "image" -> blocks.add(ContentBlock.Image(null))
            }
        }
        return PiMessage.Assistant(
            blocks = blocks,
            model = message.get("model")?.asStringOrNull(),
            provider = message.get("provider")?.asStringOrNull(),
            errorMessage = message.get("errorMessage")?.asStringOrNull(),
            usage = message.getAsJsonObjectOrNull("usage")?.let(::parseUsage),
            timestamp = message.get("timestamp")?.asLongOrNull(),
        )
    }

    private fun parseToolCallBlock(obj: JsonObject): ContentBlock.ToolCall {
        val id = obj.get("toolCallId")?.asStringOrNull() ?: obj.get("id")?.asStringOrNull() ?: ""
        val name = obj.get("toolName")?.asStringOrNull() ?: obj.get("name")?.asStringOrNull() ?: "tool"
        val args = obj.get("input") ?: obj.get("arguments")
        return ContentBlock.ToolCall(
            toolCallId = id,
            toolName = name,
            input = args?.let { pretty(it) },
        )
    }

    fun parseToolResult(message: JsonObject): PiMessage.ToolResult {
        val sb = StringBuilder()
        message.get("content")?.let { content ->
            when {
                content.isJsonPrimitive -> sb.append(content.asString)
                content.isJsonArray -> content.asJsonArray.forEach { el ->
                    val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    when (obj.get("type")?.asStringOrNull()) {
                        "text" -> obj.get("text")?.asStringOrNull()?.let {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(it)
                        }
                        "image" -> {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append("[image]")
                        }
                    }
                }
            }
        }
        return PiMessage.ToolResult(
            toolCallId = message.get("toolCallId")?.asStringOrNull() ?: "",
            toolName = message.get("toolName")?.asStringOrNull(),
            text = sb.toString(),
            isError = message.get("isError")?.asBooleanOrNull() ?: false,
            timestamp = message.get("timestamp")?.asLongOrNull(),
        )
    }

    private fun parseCustom(message: JsonObject): PiMessage? {
        // Only surface custom entries pi marks as displayable.
        if (message.get("display")?.asBooleanOrNull() == false) return null
        val kind = message.get("customType")?.asStringOrNull() ?: "custom"
        val content = message.get("content")
        val text = when {
            content == null -> ""
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.joinToString("\n") { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asStringOrNull() ?: ""
            }.trim()
            else -> ""
        }
        if (text.isBlank()) return null
        return PiMessage.Notice("[$kind] $text")
    }

    fun parseUsage(obj: JsonObject): Usage = Usage(
        input = obj.get("input")?.asLongOrNull() ?: 0,
        output = obj.get("output")?.asLongOrNull() ?: 0,
        cacheRead = obj.get("cacheRead")?.asLongOrNull() ?: 0,
        cacheWrite = obj.get("cacheWrite")?.asLongOrNull() ?: 0,
        costTotal = obj.getAsJsonObjectOrNull("cost")?.get("total")?.asDoubleOrNull() ?: 0.0,
    )

    fun asArray(element: JsonElement?): JsonArray? = element?.takeIf { it.isJsonArray }?.asJsonArray
}

internal fun JsonElement.asLongOrNull(): Long? =
    if (isJsonPrimitive && asJsonPrimitive.isNumber) asLong else null

internal fun JsonElement.asDoubleOrNull(): Double? =
    if (isJsonPrimitive && asJsonPrimitive.isNumber) asDouble else null

internal fun JsonElement.asBooleanOrNull(): Boolean? =
    if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null
