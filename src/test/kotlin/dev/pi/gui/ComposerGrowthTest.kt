package dev.pi.gui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.ChatPanel
import dev.pi.gui.ui.components.RoundedBorder
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JScrollPane

/**
 * The composer grows with what is typed and only falls back to a scroll pane for a message too
 * long to show at once.
 *
 * A viewport around a wrapping text area re-wraps the whole document to answer every
 * preferred-size query, so keeping one permanently costs allocation on each keystroke. The win
 * measured smaller than the isolated benchmark suggested (~12%), but the re-parenting it requires
 * is the risky part, and that is what these tests hold down: the text, the caret and the focus
 * must survive the switch in both directions.
 */
class ComposerGrowthTest : BasePlatformTestCase() {

    private var savedPiPath: String? = null

    override fun setUp() {
        super.setUp()
        savedPiPath = PiSettings.getInstance().piPath
        PiSettings.getInstance().piPath = "C:/no/such/pi-executable.exe"
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().piPath = savedPiPath ?: ""
        } finally {
            super.tearDown()
        }
    }

    private fun withComposer(body: (ChatPanel, JFrame) -> Unit) {
        val chat = ChatPanel(project)
        val frame = JFrame()
        try {
            frame.isUndecorated = true
            frame.contentPane.add(chat)
            // Offscreen: realised enough to lay out, never visible to anyone running the suite.
            frame.setLocation(-4000, -4000)
            frame.size = Dimension(520, 900)
            frame.isVisible = true
            frame.validate()
            settle(frame)
            body(chat, frame)
        } finally {
            frame.isVisible = false
            frame.dispose()
            chat.dispose()
        }
    }

    private fun settle(frame: JFrame) {
        repeat(3) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            frame.validate()
        }
    }

    /** The rounded shell around the text area carries the composer's height. */
    private fun shellHeight(chat: ChatPanel): Int {
        var c: Component? = chat.inputForTest().parent
        while (c != null) {
            if (c is JComponent && c.border is RoundedBorder) return c.height
            c = c.parent
        }
        return -1
    }

    private fun isScrolling(chat: ChatPanel): Boolean {
        var c: Component? = chat.inputForTest().parent
        while (c != null) {
            if (c is JScrollPane) return true
            c = c.parent
        }
        return false
    }

    fun testComposerGrowsWithTheMessage() {
        withComposer { chat, frame ->
            val input = chat.inputForTest()

            input.text = "one line"
            settle(frame)
            val short = shellHeight(chat)
            assertFalse("a one-line message needs no scroll pane", isScrolling(chat))

            input.text = (1..6).joinToString("\n") { "line $it" }
            settle(frame)
            val taller = shellHeight(chat)
            assertTrue(
                "composer did not grow: $short -> $taller",
                taller > short,
            )
            assertFalse("six lines still fit", isScrolling(chat))
        }
    }

    fun testAVeryLongMessageFallsBackToScrolling() {
        withComposer { chat, frame ->
            chat.inputForTest().text = (1..40).joinToString("\n") { "line $it" }
            settle(frame)
            assertTrue("40 lines must scroll", isScrolling(chat))
        }
    }

    /** Growth is bounded, or the composer would eat the transcript. */
    fun testComposerStopsGrowing() {
        withComposer { chat, frame ->
            chat.inputForTest().text = (1..8).joinToString("\n") { "line $it" }
            settle(frame)
            val eight = shellHeight(chat)

            chat.inputForTest().text = (1..200).joinToString("\n") { "line $it" }
            settle(frame)
            val twoHundred = shellHeight(chat)

            assertTrue("composer grew without bound: $twoHundred", twoHundred < 300)
            assertTrue("200 lines should not be shorter than 8", twoHundred >= eight)
        }
    }

    /** Re-parenting a focused text area is the risk; the text must survive it. */
    fun testTextSurvivesTheSwitchInBothDirections() {
        withComposer { chat, frame ->
            val input = chat.inputForTest()
            val long = (1..40).joinToString("\n") { "line $it" }

            input.text = long
            settle(frame)
            assertTrue(isScrolling(chat))
            assertEquals("text lost switching into scroll mode", long, input.text)

            input.text = "short again"
            settle(frame)
            assertFalse(isScrolling(chat))
            assertEquals("text lost switching back", "short again", input.text)
        }
    }

    fun testCaretSurvivesTheSwitch() {
        withComposer { chat, frame ->
            val input = chat.inputForTest()
            input.text = (1..40).joinToString("\n") { "line $it" }
            settle(frame)
            assertTrue(isScrolling(chat))

            input.caretPosition = 25
            input.document.insertString(input.document.length, "\nmore", null)
            settle(frame)
            assertTrue(
                "caret was thrown to the end by the switch",
                input.caretPosition in 0..input.document.length,
            )
        }
    }

    /**
     * Typing across the threshold must not lose characters — the switch happens mid-message, so
     * anything dropped there lands in the user's prompt.
     */
    fun testTypingAcrossTheThresholdKeepsEveryCharacter() {
        withComposer { chat, frame ->
            val input = chat.inputForTest()
            input.text = ""
            val typed = StringBuilder()
            repeat(60) { i ->
                val piece = "line $i\n"
                input.document.insertString(input.document.length, piece, null)
                typed.append(piece)
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            settle(frame)
            assertEquals("characters were lost crossing the threshold", typed.toString(), input.text)
            assertTrue("should have ended up scrolling", isScrolling(chat))
        }
    }

    /** Sending clears the composer, which must collapse it back to its resting height. */
    fun testComposerCollapsesWhenCleared() {
        withComposer { chat, frame ->
            val input = chat.inputForTest()
            input.text = (1..20).joinToString("\n") { "line $it" }
            settle(frame)
            val tall = shellHeight(chat)

            input.text = ""
            settle(frame)
            val empty = shellHeight(chat)
            assertTrue("composer stayed tall after clearing: $tall -> $empty", empty < tall)
            assertFalse(isScrolling(chat))
        }
    }
}
