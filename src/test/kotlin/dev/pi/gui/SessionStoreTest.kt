package dev.pi.gui

import dev.pi.gui.model.PiMessage
import dev.pi.gui.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sessionFile(vararg lines: String): File {
        val f = tmp.newFile("session.jsonl")
        f.writeText(lines.joinToString("\n") + "\n")
        return f
    }

    @Test
    fun `reads a linear transcript in order`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"message","id":"a2","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"reply"}]}}""",
        )
        val messages = SessionStore.readTranscript(f)
        assertEquals(2, messages.size)
        assertEquals("first", (messages[0] as PiMessage.User).text)
        assertTrue(messages[1] is PiMessage.Assistant)
    }

    /**
     * Sessions are trees. Only the branch ending at the last written entry should render,
     * so an abandoned branch must not leak into the transcript.
     */
    @Test
    fun `only the active branch is returned`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"root"}}""",
            """{"type":"message","id":"b1","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"ABANDONED"}]}}""",
            """{"type":"message","id":"c1","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"KEPT"}]}}""",
        )
        val messages = SessionStore.readTranscript(f)
        assertEquals(2, messages.size)
        assertEquals("root", (messages[0] as PiMessage.User).text)
        val rendered = (messages[1] as PiMessage.Assistant).blocks
            .filterIsInstance<dev.pi.gui.model.ContentBlock.Text>().first().text
        assertEquals("KEPT", rendered)
    }

    @Test
    fun `compaction entries become notices`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"hi"}}""",
            """{"type":"compaction","id":"k1","parentId":"a1","summary":"s","tokensBefore":12000}""",
        )
        val messages = SessionStore.readTranscript(f)
        val notice = messages.last() as PiMessage.Notice
        assertTrue(notice.text, notice.text.contains("compacted"))
        assertTrue(notice.text, notice.text.contains("12.0k"))
    }

    @Test
    fun `malformed lines are skipped`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """not json at all""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"survived"}}""",
        )
        val messages = SessionStore.readTranscript(f)
        assertEquals(1, messages.size)
        assertEquals("survived", (messages[0] as PiMessage.User).text)
    }

    @Test
    fun `cycles in parent links do not hang`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":"a2","message":{"role":"user","content":"one"}}""",
            """{"type":"message","id":"a2","parentId":"a1","message":{"role":"user","content":"two"}}""",
        )
        val messages = SessionStore.readTranscript(f)
        assertTrue(messages.size <= 2)
    }

    @Test
    fun `session header is read`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"abc","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/proj"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"preview text"}}""",
            """{"type":"session_info","id":"n1","parentId":"a1","name":"My Session"}""",
        )
        val info = SessionStore.readSessionInfo(f)
        assertNotNull(info)
        assertEquals("abc", info!!.id)
        assertEquals("/tmp/proj", info.cwd)
        assertEquals("My Session", info.name)
        assertEquals("My Session", info.displayTitle())
        assertEquals(1, info.messageCount)
    }

    @Test
    fun `title falls back to first user message`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"abc","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/proj"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"do the thing"}}""",
        )
        assertEquals("do the thing", SessionStore.readSessionInfo(f)!!.displayTitle())
    }

    @Test
    fun `file without a session header is rejected`() {
        val f = sessionFile("""{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"x"}}""")
        assertEquals(null, SessionStore.readSessionInfo(f))
    }

    /** History has no recorded duration, so it is inferred from the gap between entries. */
    @Test
    fun `assistant duration is inferred from timestamps`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"go","timestamp":1000}}""",
            """{"type":"message","id":"a2","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"ok"}],"timestamp":3500}}""",
        )
        val assistant = SessionStore.readTranscript(f).last() as PiMessage.Assistant
        assertEquals(2500L, assistant.durationMs)
    }

    /** A session resumed the next day must not claim the model thought for 14 hours. */
    @Test
    fun `implausibly long gaps are not reported as duration`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"go","timestamp":1000}}""",
            """{"type":"message","id":"a2","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"ok"}],"timestamp":50000000}}""",
        )
        val assistant = SessionStore.readTranscript(f).last() as PiMessage.Assistant
        assertNull(assistant.durationMs)
    }

    @Test
    fun `missing timestamps leave duration unset`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"go"}}""",
            """{"type":"message","id":"a2","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}""",
        )
        val assistant = SessionStore.readTranscript(f).last() as PiMessage.Assistant
        assertNull(assistant.durationMs)
    }

    /** The gap is measured from the tool result, so it reflects that step's model latency. */
    @Test
    fun `duration is measured from the preceding entry not the first user message`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"go","timestamp":1000}}""",
            """{"type":"message","id":"a2","parentId":"a1","message":{"role":"assistant","content":[{"type":"toolCall","id":"t","name":"read","arguments":{}}],"timestamp":2000}}""",
            """{"type":"message","id":"a3","parentId":"a2","message":{"role":"toolResult","toolCallId":"t","content":[{"type":"text","text":"x"}],"timestamp":9000}}""",
            """{"type":"message","id":"a4","parentId":"a3","message":{"role":"assistant","content":[{"type":"text","text":"done"}],"timestamp":9600}}""",
        )
        val assistant = SessionStore.readTranscript(f).last() as PiMessage.Assistant
        assertEquals("should exclude the 7s spent running the tool", 600L, assistant.durationMs)
    }

    @Test
    fun `token formatting is human readable`() {
        assertEquals("999", SessionStore.formatTokens(999))
        assertEquals("1.5k", SessionStore.formatTokens(1500))
        assertEquals("2.0M", SessionStore.formatTokens(2_000_000))
    }

    // -------------------------------------------------------------- renaming

    /** Renaming appends a `session_info` entry; readers take the last one, so it wins. */
    @Test
    fun `rename appends a session_info entry that wins`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"do it"}}""",
        )
        assertTrue(SessionStore.renameSession(f, "Bug hunt"))
        assertEquals("Bug hunt", SessionStore.readSessionInfo(f)!!.displayTitle())

        // Renaming again just writes a newer entry.
        assertTrue(SessionStore.renameSession(f, "Bug hunt, part 2"))
        assertEquals("Bug hunt, part 2", SessionStore.readSessionInfo(f)!!.displayTitle())

        // The appended entry must not corrupt the transcript.
        assertEquals(1, SessionStore.readTranscript(f).size)
    }

    @Test
    fun `rename collapses whitespace and rejects blank names or missing files`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
        )
        assertFalse(SessionStore.renameSession(f, "   "))
        assertTrue(SessionStore.renameSession(f, "  multi   line  name  "))
        assertEquals("multi line name", SessionStore.readSessionInfo(f)!!.name)
        assertFalse(SessionStore.renameSession(File(tmp.root, "missing.jsonl"), "x"))
    }

    /**
     * The header cache is what keeps sidebar refreshes cheap; it must never serve a stale
     * header after the file changed, and a forced drop must also re-read.
     */
    @Test
    fun `cached headers follow file changes`() {
        val f = sessionFile(
            """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
            """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"do it"}}""",
        )
        assertEquals("do it", SessionStore.readSessionInfo(f)!!.displayTitle())
        assertEquals("do it", SessionStore.readSessionInfo(f)!!.displayTitle()) // served from cache

        assertTrue(SessionStore.renameSession(f, "Named"))
        assertEquals("Named", SessionStore.readSessionInfo(f)!!.displayTitle())

        // Rewriting the file behind the cache's back: a changed length must invalidate it.
        f.appendText(
            """{"type":"session_info","id":"n9","parentId":"a1","name":"Manual edit"}""" + "\n",
        )
        assertEquals("Manual edit", SessionStore.readSessionInfo(f)!!.displayTitle())

        // Same length, same mtime — only an explicit drop re-reads. (Tests rewrite files fast.)
        SessionStore.dropHeaderCache()
        assertEquals("Manual edit", SessionStore.readSessionInfo(f)!!.displayTitle())
    }
}
