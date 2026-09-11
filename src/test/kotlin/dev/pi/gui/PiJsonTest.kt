package dev.pi.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.rpc.PiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiJsonTest {

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    @Test
    fun `user message with string content`() {
        val m = PiJson.parseMessage(json("""{"role":"user","content":"hi"}""")) as PiMessage.User
        assertEquals("hi", m.text)
    }

    @Test
    fun `user message with block content joins text and counts images`() {
        val m = PiJson.parseMessage(
            json("""{"role":"user","content":[{"type":"text","text":"a"},{"type":"image"},{"type":"text","text":"b"}]}""")
        ) as PiMessage.User
        assertEquals("a\nb", m.text)
        assertEquals(1, m.imageCount)
    }

    /** Session files use {id,name,arguments}; the RPC wire uses {toolCallId,toolName,input}. */
    @Test
    fun `tool call is normalized from the session-file spelling`() {
        val m = PiJson.parseMessage(
            json("""{"role":"assistant","content":[{"type":"toolCall","id":"x1","name":"bash","arguments":{"command":"ls"}}]}""")
        ) as PiMessage.Assistant
        val call = m.blocks[0] as ContentBlock.ToolCall
        assertEquals("x1", call.toolCallId)
        assertEquals("bash", call.toolName)
        assertTrue(call.argumentsText().contains("ls"))
    }

    @Test
    fun `tool call is normalized from the wire spelling`() {
        val m = PiJson.parseMessage(
            json("""{"role":"assistant","content":[{"type":"toolCall","toolCallId":"y1","toolName":"read","input":{"path":"f"}}]}""")
        ) as PiMessage.Assistant
        val call = m.blocks[0] as ContentBlock.ToolCall
        assertEquals("y1", call.toolCallId)
        assertEquals("read", call.toolName)
        assertTrue(call.argumentsText().contains("f"))
    }

    @Test
    fun `assistant error message and usage are captured`() {
        val m = PiJson.parseMessage(
            json(
                """{"role":"assistant","content":[],"model":"m","provider":"p","errorMessage":"429 limit",
                   "usage":{"input":10,"output":5,"cacheRead":1,"cacheWrite":2,"cost":{"total":0.5}}}"""
            )
        ) as PiMessage.Assistant
        assertEquals("429 limit", m.errorMessage)
        assertEquals(18L, m.usage!!.totalTokens())
        assertEquals(0.5, m.usage!!.costTotal, 0.0001)
    }

    @Test
    fun `tool result collects text and error flag`() {
        val m = PiJson.parseMessage(
            json("""{"role":"toolResult","toolCallId":"t","toolName":"bash","isError":true,"content":[{"type":"text","text":"boom"}]}""")
        ) as PiMessage.ToolResult
        assertEquals("boom", m.text)
        assertTrue(m.isError)
        assertEquals("bash", m.toolName)
    }

    @Test
    fun `non-displayable custom messages are dropped`() {
        assertNull(PiJson.parseMessage(json("""{"role":"custom","customType":"x","display":false,"content":"hidden"}""")))
    }

    @Test
    fun `unknown roles are dropped`() {
        assertNull(PiJson.parseMessage(json("""{"role":"mystery","content":"?"}""")))
    }

    @Test
    fun `thinking blocks are parsed`() {
        val m = PiJson.parseMessage(
            json("""{"role":"assistant","content":[{"type":"thinking","thinking":"pondering"}]}""")
        ) as PiMessage.Assistant
        assertEquals("pondering", (m.blocks[0] as ContentBlock.Thinking).thinking)
    }
}
