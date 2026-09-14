package dev.pi.gui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.ChatPanel
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.io.File
import javax.swing.JScrollPane

/**
 * Switching sessions must not block the EDT.
 *
 * Every message costs a fixed ~2.5ms to build — an HTML parse and document construction, near
 * enough independent of its size — so materialising the whole 60-message window in one go put
 * 150-270ms on the EDT for each switch while the viewport showed about three messages. Only a
 * screenful is rendered synchronously now; the rest arrives a chunk per event-queue turn.
 *
 * These tests pin the shape of that: what lands immediately, that it still all arrives, and that
 * quick switching does not leave stale fills running.
 */
class TranscriptFillTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private var savedPiPath: String? = null

    override fun setUp() {
        super.setUp()
        // A switch eagerly boots pi; a test-spawned agent outlives the JVM.
        savedPiPath = PiSettings.getInstance().piPath
        PiSettings.getInstance().piPath = "C:/no/such/pi-executable.exe"
        dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("pi-fill", "")
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().piPath = savedPiPath ?: ""
            com.intellij.openapi.util.io.FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    /** `turns` × (user, assistant with a code block) — the shape of a real conversation. */
    private fun sessionFile(name: String, turns: Int, codeLines: Int = 40): File {
        val f = File(dir, "$name.jsonl")
        val out = StringBuilder()
        out.appendLine(
            """{"type":"session","version":3,"id":"$name",""" +
                """"timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}"""
        )
        var parent: String? = null
        fun entry(id: String, message: String) {
            val p = parent?.let { "\"$it\"" } ?: "null"
            out.appendLine("""{"type":"message","id":"$id","parentId":$p,"message":$message}""")
            parent = id
        }
        repeat(turns) { turn ->
            entry("$name-u$turn", """{"role":"user","content":"question $turn"}""")
            val code = (1..codeLines).joinToString("\\n") { "val r$it = compute($turn, $it)" }
            entry(
                "$name-a$turn",
                """{"role":"assistant","content":[{"type":"text",""" +
                    """"text":"## Answer $turn\n\n```kotlin\n$code\n```"}],"model":"m",""" +
                    """"usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"cost":0.0}}"""
            )
        }
        f.writeText(out.toString())
        return f
    }

    private fun pumpUntil(timeoutMs: Int = 20_000, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(2)
        }
        return condition()
    }

    private fun findScroll(c: Component): JScrollPane? {
        if (c is JScrollPane) return c
        if (c is Container) c.components.forEach { child -> findScroll(child)?.let { return it } }
        return null
    }

    private fun layoutTree(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach { layoutTree(it) }
        }
    }

    /** The first thing on screen must be a screenful, not the whole window. */
    fun testOnlyAScreenfulIsRenderedSynchronously() {
        val session = SessionStore.readSessionInfo(sessionFile("big", turns = 80))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(900, 1000)
            layoutTree(chat)

            chat.loadSession(session)
            // Wait only for the transcript to appear, then look before letting the fill proceed.
            assertTrue("transcript never appeared", pumpUntil { chat.transcriptChildCountForTest() > 0 })

            // Not `transcriptChildCountForTest`: draining the event queue to observe it would
            // also run the fill, so the count is read from the synchronous pass itself.
            val immediate = chat.eagerComponentCountForTest()
            assertTrue("nothing was rendered at all", immediate > 0)
            assertTrue(
                "synchronous render produced $immediate components — the whole window again",
                immediate <= 16,
            )
        } finally {
            chat.dispose()
        }
    }

    /** The deferred chunks must still deliver the full window, or scrollback silently shrinks. */
    fun testTheRestOfTheWindowArrivesAfterwards() {
        val session = SessionStore.readSessionInfo(sessionFile("big", turns = 80))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(900, 1000)
            layoutTree(chat)

            chat.loadSession(session)
            assertTrue(
                "the fill never completed the window",
                pumpUntil { chat.transcriptChildCountForTest() >= 60 },
            )
        } finally {
            chat.dispose()
        }
    }

    /** A 160-message session still only shows a window, with a "load earlier" row above it. */
    fun testWindowStaysBoundedAndKeepsTheLoadEarlierRow() {
        val session = SessionStore.readSessionInfo(sessionFile("big", turns = 80))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(900, 1000)
            layoutTree(chat)
            chat.loadSession(session)
            assertTrue(pumpUntil { chat.transcriptChildCountForTest() >= 60 })
            // Let any remaining chunks drain.
            repeat(50) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(2) }

            val count = chat.transcriptChildCountForTest()
            assertEquals("160 messages must still be windowed", 160, chat.messagesForTest().size)
            assertTrue(
                "window grew unbounded: $count components",
                count in 60..62,
            )
        } finally {
            chat.dispose()
        }
    }

    /**
     * Clicking quickly through sessions must not leave earlier fills running: a stale chunk would
     * prepend the previous conversation's messages into the current transcript.
     */
    fun testRapidSwitchingDropsStaleFills() {
        val a = SessionStore.readSessionInfo(sessionFile("a", turns = 80))!!
        val b = SessionStore.readSessionInfo(sessionFile("b", turns = 30))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(900, 1000)
            layoutTree(chat)

            // Switch away before a's fill can finish.
            chat.loadSession(a)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            chat.loadSession(b)

            assertTrue(
                "b never loaded",
                pumpUntil { chat.messagesForTest().size == 60 && chat.loadedSessionPathForTest() == b.filePath },
            )
            repeat(80) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(2) }

            // b has 60 messages, so the whole conversation fits: no "load earlier" row, and no
            // leftovers from a.
            assertEquals(60, chat.messagesForTest().size)
            assertTrue(
                "transcript holds ${chat.transcriptChildCountForTest()} components for a " +
                    "60-message session — a stale fill leaked in",
                chat.transcriptChildCountForTest() <= 60,
            )
        } finally {
            chat.dispose()
        }
    }

    /** Chunks are prepended above the viewport; the view must stay on the newest message. */
    fun testTheViewStaysAtTheNewestMessageWhileTheFillRuns() {
        val session = SessionStore.readSessionInfo(sessionFile("big", turns = 80))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(900, 1000)
            layoutTree(chat)
            val scroll = findScroll(chat) ?: error("no scroll pane")

            chat.loadSession(session)
            assertTrue(pumpUntil { chat.transcriptChildCountForTest() >= 60 })
            repeat(50) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                layoutTree(chat)
                Thread.sleep(2)
            }

            val viewport = scroll.viewport
            val view = viewport.view!!
            val bottom = (view.height - viewport.extentSize.height).coerceAtLeast(0)
            assertTrue("nothing to scroll — test proves nothing", bottom > 0)
            assertEquals(
                "the fill pushed the view off the newest message",
                bottom,
                viewport.viewPosition.y,
            )
        } finally {
            chat.dispose()
        }
    }
}
