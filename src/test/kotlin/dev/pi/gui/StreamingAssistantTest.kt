package dev.pi.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.rpc.StreamingAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the delta protocol pi emits inside `message_update.assistantMessageEvent`.
 * Event shapes follow the pi SDK's rpc/json event contract.
 */
class StreamingAssistantTest {

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    @Test
    fun `text deltas accumulate in order`() {
        val s = StreamingAssistant()
        s.begin()
        s.applyDelta(json("""{"type":"text_start","contentIndex":0}"""))
        s.applyDelta(json("""{"type":"text_delta","contentIndex":0,"delta":"Hel"}"""))
        s.applyDelta(json("""{"type":"text_delta","contentIndex":0,"delta":"lo"}"""))

        val block = s.current!!.blocks[0] as ContentBlock.Text
        assertEquals("Hello", block.text)
    }

    @Test
    fun `text_end replaces accumulated text with authoritative content`() {
        val s = StreamingAssistant()
        s.begin()
        s.applyDelta(json("""{"type":"text_start","contentIndex":0}"""))
        s.applyDelta(json("""{"type":"text_delta","contentIndex":0,"delta":"parti"}"""))
        s.applyDelta(json("""{"type":"text_end","contentIndex":0,"content":"complete"}"""))

        assertEquals("complete", (s.current!!.blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `thinking and text occupy separate content indexes`() {
        val s = StreamingAssistant()
        s.begin()
        s.applyDelta(json("""{"type":"thinking_start","contentIndex":0}"""))
        s.applyDelta(json("""{"type":"thinking_delta","contentIndex":0,"delta":"hmm"}"""))
        s.applyDelta(json("""{"type":"text_start","contentIndex":1}"""))
        s.applyDelta(json("""{"type":"text_delta","contentIndex":1,"delta":"answer"}"""))

        assertEquals(2, s.current!!.blocks.size)
        assertEquals("hmm", (s.current!!.blocks[0] as ContentBlock.Thinking).thinking)
        assertEquals("answer", (s.current!!.blocks[1] as ContentBlock.Text).text)
    }

    @Test
    fun `tool call arguments stream then finalize`() {
        val s = StreamingAssistant()
        s.begin()
        s.applyDelta(json("""{"type":"toolcall_start","contentIndex":0,"id":"c1","toolName":"read"}"""))
        s.applyDelta(json("""{"type":"toolcall_delta","contentIndex":0,"delta":"{\"path\":"}"""))
        s.applyDelta(json("""{"type":"toolcall_delta","contentIndex":0,"delta":"\"a.txt\"}"}"""))

        val partial = s.current!!.blocks[0] as ContentBlock.ToolCall
        assertEquals("read", partial.toolName)
        assertEquals("c1", partial.toolCallId)
        assertTrue(partial.argumentsText().contains("a.txt"))

        s.applyDelta(
            json("""{"type":"toolcall_end","contentIndex":0,"toolCall":{"id":"c1","name":"read","arguments":{"path":"a.txt"}}}""")
        )
        val done = s.current!!.blocks[0] as ContentBlock.ToolCall
        assertEquals("read", done.toolName)
        assertTrue(done.argumentsText().contains("a.txt"))
    }

    @Test
    fun `out-of-order index is filled without losing earlier blocks`() {
        val s = StreamingAssistant()
        s.begin()
        s.applyDelta(json("""{"type":"text_start","contentIndex":2}"""))
        s.applyDelta(json("""{"type":"text_delta","contentIndex":2,"delta":"third"}"""))
        assertEquals(3, s.current!!.blocks.size)
        assertEquals("third", (s.current!!.blocks[2] as ContentBlock.Text).text)
    }

    @Test
    fun `snapshot seeds from a message_start payload`() {
        val s = StreamingAssistant()
        s.snapshot(
            json("""{"role":"assistant","model":"m","provider":"p","content":[{"type":"text","text":"seed"}]}""")
        )
        assertNotNull(s.current)
        assertEquals("seed", (s.current!!.blocks[0] as ContentBlock.Text).text)
        assertEquals("m", s.current!!.model)
    }

    @Test
    fun `end clears streaming state`() {
        val s = StreamingAssistant()
        s.begin()
        assertTrue(s.isStreaming)
        s.end()
        assertNull(s.current)
        assertTrue(!s.isStreaming)
    }

    @Test
    fun `unknown events are ignored safely`() {
        val s = StreamingAssistant()
        s.begin()
        assertTrue(!s.applyDelta(json("""{"type":"something_new","contentIndex":0}""")))
        assertEquals(0, s.current!!.blocks.size)
    }

    @Test
    fun `missing content index is ignored`() {
        val s = StreamingAssistant()
        s.begin()
        assertTrue(!s.applyDelta(json("""{"type":"text_delta","delta":"x"}""")))
    }
}
