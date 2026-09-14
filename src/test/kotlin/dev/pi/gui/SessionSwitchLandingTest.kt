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
 * Switching sessions must land on the newest message *without travelling there*.
 *
 * `JBScrollBar.setValue` runs the IDE's smooth-scrolling interpolator, so assigning
 * `verticalScrollBar.value` animated the viewport down the entire transcript — tens of thousands
 * of pixels of travel, repainting the component tree every frame, to reach a position it could
 * jump to in one step. The viewport is therefore moved directly, and these tests pin both halves:
 * the landing position, and that the scrollbar is not the thing being driven.
 */
class SessionSwitchLandingTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private var savedPiPath: String? = null

    override fun setUp() {
        super.setUp()
        // Drives the Swing transcript's own scroll pane and viewport, so it has to run against
        // the Swing surface rather than whichever one the IDE would pick.
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
        // Never let a switch boot a real agent: a test-spawned pi outlives the JVM.
        savedPiPath = PiSettings.getInstance().piPath
        PiSettings.getInstance().piPath = "C:/no/such/pi-executable.exe"
        dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("pi-landing", "")
    }

    override fun tearDown() {
        try {
            System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
            PiSettings.getInstance().piPath = savedPiPath ?: ""
            com.intellij.openapi.util.io.FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun sessionFile(name: String, turns: Int, codeLines: Int = 200): File {
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
            val code = (1..codeLines).joinToString("\\n") {
                "val result$it = compute($turn, $it) // a long line so the block has real height"
            }
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

    private fun pumpUntil(timeoutMs: Int = 15_000, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        return condition()
    }

    private fun findScroll(c: Component): JScrollPane? {
        if (c is JScrollPane) return c
        if (c is Container) c.components.forEach { child -> findScroll(child)?.let { return it } }
        return null
    }

    /**
     * `doLayout()` only lays out one level, so the scroll pane's viewport would keep a zero
     * extent and the landing position would be meaningless.
     */
    private fun layoutTree(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach { layoutTree(it) }
        }
    }

    private fun settle(chat: ChatPanel) {
        repeat(3) {
            layoutTree(chat)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    fun testSwitchingSessionsLandsAtTheNewestMessage() {
        val a = SessionStore.readSessionInfo(sessionFile("a", turns = 40))!!
        val b = SessionStore.readSessionInfo(sessionFile("b", turns = 40))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(700, 900)
            layoutTree(chat)
            val scroll = findScroll(chat) ?: error("no scroll pane")

            listOf(a, b, a).forEach { session ->
                chat.loadSession(session)
                assertTrue(
                    "switch to ${session.id} never landed",
                    pumpUntil {
                        chat.loadedSessionPathForTest() == session.filePath &&
                            chat.transcriptChildCountForTest() > 1
                    },
                )
                settle(chat)

                val viewport = scroll.viewport
                val view = viewport.view!!
                val bottom = (view.height - viewport.extentSize.height).coerceAtLeast(0)
                assertEquals(
                    "session ${session.id} did not land on the newest message",
                    bottom,
                    viewport.viewPosition.y,
                )
            }
        } finally {
            chat.dispose()
        }
    }

    /**
     * The landing must be a single jump. Driving `verticalScrollBar.value` would hand the move to
     * the smooth-scrolling interpolator, so this asserts the viewport is already at its final
     * position by the time the switch has settled — nothing left to animate.
     */
    fun testLandingIsAJumpRatherThanAnAnimatedGlide() {
        val long = SessionStore.readSessionInfo(sessionFile("long", turns = 60, codeLines = 400))!!
        val chat = ChatPanel(project)
        try {
            chat.size = Dimension(700, 900)
            layoutTree(chat)
            val scroll = findScroll(chat) ?: error("no scroll pane")

            chat.loadSession(long)
            assertTrue("session never loaded", pumpUntil { chat.transcriptChildCountForTest() > 1 })
            settle(chat)

            val viewport = scroll.viewport
            val view = viewport.view!!
            val travel = view.height - viewport.extentSize.height
            // Guard the premise: a transcript short enough to fit would prove nothing.
            assertTrue("transcript too short to be meaningful ($travel px)", travel > 2_000)

            val settled = viewport.viewPosition.y
            // One more round of events must not move it — an interpolated scroll would still be
            // stepping toward the target here.
            repeat(3) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
            assertEquals("the viewport was still travelling", settled, viewport.viewPosition.y)
            assertEquals("did not reach the newest message in one step", travel, settled)
        } finally {
            chat.dispose()
        }
    }
}
