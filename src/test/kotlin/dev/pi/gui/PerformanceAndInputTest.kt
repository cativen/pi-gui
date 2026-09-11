package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.ui.ChatPanel
import dev.pi.gui.ui.MessageRenderer
import java.awt.Dimension
import java.awt.datatransfer.StringSelection

/**
 * Guards the two defects found during the full-feature verification pass: clipboard paste being
 * swallowed by the file-drop handler, and unbounded content freezing the UI.
 */
class PerformanceAndInputTest : BasePlatformTestCase() {

    // ------------------------------------------------------------- clipboard

    /**
     * Regression: the attachment drop handler replaced the text area's TransferHandler, and
     * paste/copy/cut all route through it, so pasting text silently did nothing.
     */
    fun testPlainTextPasteStillWorks() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection("pasted text"), null)
            input.text = ""
            input.paste()
            assertEquals("pasted text", input.text)
        } finally {
            panel.dispose()
        }
    }

    fun testCopyFromInputStillWorks() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            input.text = "copy me"
            input.selectAll()
            input.copy()
            val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
            assertEquals("copy me", clip)
        } finally {
            panel.dispose()
        }
    }

    // ----------------------------------------------------------- performance

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it with some content" }

    /** Collapsed sections must not build their body until opened. */
    fun testCollapsedToolResultIsCheapRegardlessOfSize() {
        val huge = lines(10_000)
        val start = System.currentTimeMillis()
        val c = MessageRenderer.render(project, PiMessage.ToolResult("t", "bash", huge, false))
        c.size = Dimension(700, 100_000)
        c.doLayout()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("collapsed 10k-line result took ${elapsed}ms", elapsed < 400)
    }

    fun testHugeCodeBlockIsCapped() {
        val code = "```kotlin\n" + lines(20_000) + "\n```"
        val start = System.currentTimeMillis()
        val c = MessageRenderer.render(project, PiMessage.Assistant(mutableListOf(ContentBlock.Text(code))))
        c.size = Dimension(700, 200_000)
        c.doLayout()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("20k-line code block took ${elapsed}ms", elapsed < 4_000)
    }

    /** Opening a long session must stay responsive; only the tail is materialised. */
    fun testLongTranscriptOpensQuickly() {
        val messages = buildList {
            repeat(400) { i ->
                add(PiMessage.User("question $i"))
                add(PiMessage.Assistant(mutableListOf(ContentBlock.Text("answer $i with `code` and text"))))
            }
        }
        val panel = ChatPanel(project)
        try {
            panel.size = Dimension(700, 900)
            val start = System.currentTimeMillis()
            panel.setTranscriptForPreview(messages)
            panel.doLayout()
            val elapsed = System.currentTimeMillis() - start
            assertTrue("800-message transcript took ${elapsed}ms to open", elapsed < 1_500)
        } finally {
            panel.dispose()
        }
    }

    fun testWindowingRendersOnlyTheTailPlusLoadButton() {
        val messages = (1..300).map { PiMessage.User("message $it") as PiMessage }
        val panel = ChatPanel(project)
        try {
            panel.setTranscriptForPreview(messages)
            val rendered = panel.transcriptChildCountForTest()
            assertTrue(
                "expected a windowed transcript, rendered $rendered of ${messages.size}",
                rendered in 2..80,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testShortTranscriptRendersEverything() {
        val messages = (1..5).map { PiMessage.User("message $it") as PiMessage }
        val panel = ChatPanel(project)
        try {
            panel.setTranscriptForPreview(messages)
            assertEquals(5, panel.transcriptChildCountForTest())
        } finally {
            panel.dispose()
        }
    }
}
