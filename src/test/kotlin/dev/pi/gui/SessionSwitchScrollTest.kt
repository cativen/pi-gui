package dev.pi.gui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.ChatPanel
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Reproduces the manual stress test: click through several different sessions, scroll up and
 * down inside each one, and repeat — while watching the EDT for stalls. An EDT stall is a
 * frozen IDE (the whole event queue waits); a slow paint is scroll jank confined to the tool
 * window. Both must stay bounded no matter how many sessions are switched through.
 */
class SessionSwitchScrollTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private var savedPiPath: String? = null

    override fun setUp() {
        super.setUp()
        // Drives the Swing transcript's own scroll pane and viewport, so it has to run against
        // the Swing surface rather than whichever one the IDE would pick.
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
        // This suite must never spawn a real agent: switching sessions eagerly boots pi, and a
        // test-spawned process outlives the JVM (leaking a whole pi tree) and locks the temp
        // directory on Windows.
        savedPiPath = PiSettings.getInstance().piPath
        PiSettings.getInstance().piPath = "C:/no/such/pi-executable.exe"
        dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("pi-switch", "")
    }

    override fun tearDown() {
        System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        PiSettings.getInstance().piPath = savedPiPath ?: ""
        com.intellij.openapi.util.io.FileUtil.delete(dir)
        super.tearDown()
    }

    // ------------------------------------------------------------- session data

    /** `turns` rounds of user question → assistant answer (markdown + code + tool call) → tool result. */
    private fun sessionFile(name: String, turns: Int): File = sessionFile(name, turns, 24)

    /** Same, but with a caller-chosen code-block size — real pi answers routinely paste whole files. */
    private fun sessionFile(name: String, turns: Int, codeLines: Int): File {
        val f = File(dir, "$name.jsonl")
        val out = StringBuilder()
        out.appendLine("""{"type":"session","version":3,"id":"$name","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""")
        var parent: String? = null
        fun entry(id: String, messageJson: String) {
            val p = parent?.let { """"$it"""" } ?: "null"
            out.appendLine("""{"type":"message","id":"$id","parentId":$p,"message":$messageJson}""")
            parent = id
        }
        repeat(turns) { turn ->
            entry("$name-u$turn", """{"role":"user","content":"question $turn about the build failing"}""")
            entry("$name-a$turn", assistantJson(turn, codeLines))
            entry("$name-r$turn", toolResultJson())
        }
        f.writeText(out.toString())
        return f
    }

    private fun assistantJson(turn: Int, codeLines: Int = 24): String {
        val text = (
            "## Analysis $turn\n\n" +
                "The stack trace points at `VerticalStackLayout`:\n\n" +
                "- first bullet with `code`\n- second bullet\n\n" +
                "| col a | col b |\n|---|---|\n| 1 | 2 |\n\n" +
                "```kotlin\n" + codeBody(turn, codeLines) + "\n```\n\n" +
                "Conclusion paragraph that wraps across a couple of lines at the usual tool-window " +
                "width, mentioning file paths like `src/main/kotlin/dev/pi/gui/ui/ChatPanel.kt`."
            ).replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
        return (
            """{"role":"assistant","content":[""" +
                """{"type":"text","text":"$text"},""" +
                """{"type":"toolCall","toolCallId":"c$turn","toolName":"bash","input":{"command":"gradlew test"}}""" +
                """],"model":"sonnet","usage":{"input":1200,"output":300,"cacheRead":800,"cacheWrite":100,"cost":0.01}}"""
            )
    }

    private fun toolResultJson(): String {
        val text = (
            "BUILD SUCCESSFUL in 12s\n\n14 actionable tasks: 2 executed, 12 up-to-date\n\n" +
                "All tests passed."
            ).replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
        return """{"role":"toolResult","content":"$text"}"""
    }

    private fun codeBody(turn: Int, lines: Int = 24): String =
        (1..lines).joinToString("\n") {
            "val result$it = compute($turn, $it) // a reasonably long line of Kotlin so the lexer has real work"
        }

    private fun info(file: File) = SessionStore.readSessionInfo(file)!!

    // ------------------------------------------------------------- helpers

    /** Processes queued EDT work while waiting for an async condition. */
    private fun pumpUntil(timeoutMs: Int = 10_000, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        return condition()
    }

    /** Posts one runnable on the EDT and blocks (pumping) until it has run. */
    private fun runOnEdtPumped(body: () -> Unit) {
        val done = AtomicBoolean(false)
        SwingUtilities.invokeLater {
            try { body() } finally { done.set(true) }
        }
        assertTrue("EDT task never ran", pumpUntil { done.get() })
    }

    /**
     * Posts no-op runnables from a side thread and records how long each waits in the queue.
     * Whatever the EDT is busy with when a heartbeat lands, the user is staring at a frozen IDE.
     */
    private class EdtHeartbeat {
        @Volatile private var running = true
        private val delaysMs = CopyOnWriteArrayList<Long>()
        private val thread = Thread {
            while (running) {
                val sent = System.nanoTime()
                SwingUtilities.invokeLater { delaysMs.add((System.nanoTime() - sent) / 1_000_000) }
                Thread.sleep(15)
            }
        }.apply { isDaemon = true }

        fun start() = thread.start()
        fun stop(): Pair<Long, Int> {
            running = false
            thread.join(2_000)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            return (delaysMs.maxOrNull() ?: 0L) to delaysMs.count { it > 400 }
        }
    }

    /**
     * Worst case a real conversation produces: every visible message pastes a whole file —
     * 600-line code blocks and 800-line tool outputs. The window (60 messages) is then heavy,
     * and this is exactly the shape that made switching feel frozen.
     */
    fun testHeavySessionSwitchAndScrollStaysUsable() {
        val heavy = info(sessionFile("heavy", turns = 60, codeLines = 600))
        val chat = ChatPanel(project)
        val heartbeat = EdtHeartbeat()
        try {
            chat.size = Dimension(700, 900)
            chat.doLayout()
            heartbeat.start()
            val t0 = System.currentTimeMillis()
            chat.loadSession(heavy)
            assertTrue("heavy session never loaded", pumpUntil { chat.transcriptChildCountForTest() > 1 })
            val loadMs = System.currentTimeMillis() - t0
            // The line budget must keep the window small no matter how big each message is.
            assertTrue(
                "line budget did not engage: ${chat.transcriptChildCountForTest()} components rendered",
                chat.transcriptChildCountForTest() in 2..16,
            )
            // Charge the post-load paint to the EDT too (this is the frame the user sees).
            val scroll0 = chat.findScrollPane() ?: error("no scroll pane in chat panel")
            val g0: Graphics2D = BufferedImage(700, 900, BufferedImage.TYPE_INT_RGB).createGraphics()
            runOnEdtPumped {
                chat.doLayout()
                scroll0.paint(g0)
            }
            g0.dispose()
            // Stop measuring before the scroll loop: it runs off the EDT without dispatching,
            // so queued heartbeats would only record harness delay, not real stalls.
            val (worstStallMs, longStalls) = heartbeat.stop()

            val scroll = chat.findScrollPane() ?: error("no scroll pane in chat panel")
            val g: Graphics2D = BufferedImage(700, 900, BufferedImage.TYPE_INT_RGB).createGraphics()
            runOnEdtPumped {
                chat.doLayout()
                scroll.paint(g)
            }
            val bar = scroll.verticalScrollBar
            val step = (bar.visibleAmount / 2).coerceAtLeast(1)
            var v = 0
            var worstFrame = 0L
            while (v < bar.maximum) {
                bar.value = v
                val t = System.currentTimeMillis()
                scroll.paint(g)
                worstFrame = maxOf(worstFrame, System.currentTimeMillis() - t)
                v += step
            }
            g.dispose()

            println("heavy session: load=${loadMs}ms worstEdtStall=${worstStallMs}ms worstScrollFrame=${worstFrame}ms")
            assertTrue("heavy session took ${loadMs}ms to become usable", loadMs < 3_000)
            assertTrue("worst scroll frame on the heavy session took ${worstFrame}ms", worstFrame < 250)
            assertTrue(
                "EDT stalled ${worstStallMs}ms switching to a heavy session ($longStalls stalls > 400ms)",
                worstStallMs < 600,
            )
        } finally {
            heartbeat.stop()
            chat.dispose()
        }
    }

    private fun ChatPanel.findScrollPane(): JScrollPane? {
        var found: JScrollPane? = null
        fun walk(c: Component) {
            if (found != null) return
            if (c is JScrollPane) { found = c; return }
            if (c is Container) c.components.forEach(::walk)
        }
        walk(this)
        return found
    }

    // ------------------------------------------------------------- the tests

    /**
     * Click through four real sessions back and forth. Every switch must land (file read +
     * transcript rebuild + layout + paint) without ever blocking the EDT for long.
     */
    fun testRapidSessionSwitchingNeverFreezesTheEdt() {
        val sessions = listOf("a", "b", "c", "d").map { info(sessionFile(it, turns = 90)) }
        val chat = ChatPanel(project)
        val heartbeat = EdtHeartbeat()
        try {
            chat.size = Dimension(700, 900)
            chat.doLayout()
            val scroll = chat.findScrollPane() ?: error("no scroll pane in chat panel")
            val g: Graphics2D = BufferedImage(700, 900, BufferedImage.TYPE_INT_RGB).createGraphics()
            heartbeat.start()

            var worstSwitchMs = 0L
            for (i in listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3)) {
                val t0 = System.currentTimeMillis()
                chat.loadSession(sessions[i])
                val landed = pumpUntil {
                    chat.loadedSessionPathForTest() == sessions[i].filePath &&
                        chat.transcriptChildCountForTest() > 1
                }
                assertTrue("switch to session $i never landed", landed)
                // Charge the visible part of the switch to the EDT, as a real repaint would.
                runOnEdtPumped {
                    chat.doLayout()
                    scroll.paint(g)
                }
                worstSwitchMs = maxOf(worstSwitchMs, System.currentTimeMillis() - t0)
            }

            val (worstStallMs, longStalls) = heartbeat.stop()
            g.dispose()
            println("switching: worstEdtStall=${worstStallMs}ms longStalls=$longStalls worstSwitch=${worstSwitchMs}ms")
            // 400ms was loose enough to pass while every switch visibly froze the IDE. A
            // switch now renders one screenful and fills the rest in chunks, so the longest
            // single stall is tens of milliseconds; 150 leaves room for a slow CI box without
            // letting the old behaviour back in.
            assertTrue(
                "EDT stalled ${worstStallMs}ms during session switching ($longStalls stalls > 400ms)",
                worstStallMs < 150,
            )
            assertTrue("a single session switch took ${worstSwitchMs}ms", worstSwitchMs < 4_000)
        } finally {
            heartbeat.stop()
            chat.dispose()
        }
    }

    /**
     * Scroll a long session from top to bottom and back, painting at every position, plus a
     * burst of wheel-sized unit steps. Each frame is one screen the user sees while dragging.
     */
    fun testScrollingLongTranscriptStaysSmooth() {
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(700, 900)
            chat.doLayout()
            chat.loadSession(info(sessionFile("long", turns = 150)))
            assertTrue("long session never loaded", pumpUntil { chat.transcriptChildCountForTest() > 1 })
            chat.doLayout()

            val scroll = chat.findScrollPane() ?: error("no scroll pane in chat panel")
            val g: Graphics2D = BufferedImage(700, 900, BufferedImage.TYPE_INT_RGB).createGraphics()
            val bar = scroll.verticalScrollBar
            val step = (bar.visibleAmount / 2).coerceAtLeast(1)
            val frameMs = mutableListOf<Long>()

            fun scrollTo(v: Int) {
                bar.value = v
                val t = System.currentTimeMillis()
                scroll.paint(g)
                frameMs.add(System.currentTimeMillis() - t)
            }

            var v = 0
            while (v < bar.maximum) { scrollTo(v); v += step }
            while (v > 0) { scrollTo(v); v -= step }
            repeat(60) { scrollTo((bar.maximum / 3 + it * 24).coerceAtMost(bar.maximum - 1)) }
            g.dispose()

            val worst = frameMs.maxOrNull() ?: 0
            println("scrolling: worstFrame=${worst}ms median=${frameMs.sorted()[frameMs.size / 2]}ms frames=${frameMs.size}")
            assertTrue(
                "worst scroll frame took ${worst}ms over ${frameMs.size} frames " +
                    "(median ${frameMs.sorted()[frameMs.size / 2]}ms)",
                worst < 120,
            )
        } finally {
            chat.dispose()
        }
    }

    /**
     * The exact user sequence: switch to a session, scroll it fully, switch to the next one,
     * scroll again — several rounds. Catches interactions between switching and scrolling
     * (stale scroll targets, accumulating components, growing layout cost).
     */
    fun testInterleavedSwitchAndScrollStaysBounded() {
        val sessions = listOf("x", "y", "z").map { info(sessionFile(it, turns = 60)) }
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(700, 900)
            chat.doLayout()
            val scroll = chat.findScrollPane() ?: error("no scroll pane in chat panel")
            val g: Graphics2D = BufferedImage(700, 900, BufferedImage.TYPE_INT_RGB).createGraphics()
            val bar = scroll.verticalScrollBar

            repeat(3) { round ->
                for (s in sessions) {
                    chat.loadSession(s)
                    assertTrue(
                        "round $round: switch to ${s.id} never landed",
                        pumpUntil { chat.transcriptChildCountForTest() > 1 },
                    )
                    chat.doLayout()
                    bar.value = 0
                    // Fully down and back up in viewport steps, painting like a drag would.
                    val step = (bar.visibleAmount / 2).coerceAtLeast(1)
                    var v = 0
                    while (v < bar.maximum) { bar.value = v; scroll.paint(g); v += step }
                    while (v > 0) { bar.value = v; scroll.paint(g); v -= step }
                    // Windowing must hold after every switch: the transcript never grows unbounded.
                    assertTrue(
                        "round $round: transcript grew to ${chat.transcriptChildCountForTest()} children",
                        chat.transcriptChildCountForTest() <= 81,
                    )
                }
            }
            g.dispose()
        } finally {
            chat.dispose()
        }
    }
}
