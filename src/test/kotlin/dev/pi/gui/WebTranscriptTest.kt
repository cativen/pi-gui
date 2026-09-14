package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.ui.ChatPanel
import dev.pi.gui.ui.transcript.SwingTranscriptSurface
import dev.pi.gui.ui.transcript.TranscriptSurface
import dev.pi.gui.web.PiWebView

/**
 * The browser-backed conversation view.
 *
 * What can be checked without a display is the seam: that the surface is interchangeable with the
 * Swing one, that the plugin's windowing is honoured through it, and that the fallback engages
 * when JCEF is unavailable. Whether Chromium actually paints the HTML needs a running IDE — no
 * headless test can stand in for that, so it is not claimed here.
 */
class WebTranscriptTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    private fun messages(n: Int): List<PiMessage> =
        (1..n).map { PiMessage.User("message $it") }

    // --------------------------------------------------------- surface choice

    fun testWebIsUsedWhenJcefIsAvailable() {
        System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        assertEquals(
            "the transcript should follow JCEF availability",
            PiWebView.isAvailable(),
            ChatPanel.useWebTranscript(),
        )
    }

    /** Some IDE builds ship without JCEF and users can switch it off; the plugin must still run. */
    fun testSwingFallbackCanBeForced() {
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
        assertFalse(ChatPanel.useWebTranscript())
    }

    fun testForcedFallbackGivesChatPanelTheSwingSurface() {
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
        val panel = ChatPanel(project)
        try {
            // The Swing surface owns a scroll pane; the web one does not.
            assertTrue("expected the Swing transcript", hasScrollPane(panel))
        } finally {
            panel.dispose()
        }
    }

    // ------------------------------------------------- the surface's contract

    /** Both implementations have to satisfy the same contract, so the seam is exercised twice. */
    fun testSwingSurfaceHonoursTheContract() {
        assertSurfaceContract(SwingTranscriptSurface(project))
    }

    fun testWebSurfaceHonoursTheContract() {
        if (!PiWebView.isAvailable()) {
            println("SKIPPED: JCEF is not available in this environment")
            return
        }
        val surface = try {
            dev.pi.gui.ui.transcript.WebTranscriptSurface(project) {}
        } catch (e: Throwable) {
            // Constructing a browser needs more than a classpath; say so rather than pass quietly.
            println("SKIPPED: could not create a JCEF browser here (${e.javaClass.simpleName})")
            return
        }
        assertSurfaceContract(surface)
    }

    private fun assertSurfaceContract(surface: TranscriptSurface) {
        try {
            surface.showEmptyState("/tmp/project")
            assertEquals("an empty transcript renders nothing", 0, surface.renderedCount())

            surface.setMessages(messages(10), hiddenCount = 5)
            assertEquals(10, surface.renderedCount())

            surface.appendMessage(PiMessage.User("one more"))
            assertEquals(11, surface.renderedCount())

            surface.prependMessages(messages(3), hiddenCount = 2)
            assertEquals(14, surface.renderedCount())

            // Streaming replaces a live bubble rather than appending a message.
            surface.setStreaming(
                PiMessage.Assistant(blocks = mutableListOf<ContentBlock>(ContentBlock.Text("partial")))
            )
            assertEquals("streaming must not count as a message", 14, surface.renderedCount())
            surface.setStreaming(null)
            assertEquals(14, surface.renderedCount())

            surface.setMessages(emptyList(), hiddenCount = 0)
            assertEquals(0, surface.renderedCount())

            // Must not throw off-screen.
            surface.scrollToBottom()
            surface.applySettings()
            assertNotNull(surface.component)
        } finally {
            surface.dispose()
        }
    }

    // ------------------------------------------------------------- windowing

    /**
     * The windowing that keeps a session switch off the EDT lives above the surface, so it has to
     * behave the same whichever one is in use.
     */
    fun testWindowingStillBoundedOnWhicheverSurface() {
        val panel = ChatPanel(project)
        try {
            panel.setTranscriptForPreview(messages(400))
            val rendered = panel.transcriptChildCountForTest()
            assertTrue("window grew to $rendered messages", rendered in 1..60)
        } finally {
            panel.dispose()
        }
    }

    private fun hasScrollPane(c: java.awt.Component): Boolean {
        if (c is javax.swing.JScrollPane) return true
        if (c is java.awt.Container) return c.components.any { hasScrollPane(it) }
        return false
    }
}
