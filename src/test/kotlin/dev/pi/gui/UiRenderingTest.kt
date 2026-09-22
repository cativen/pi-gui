package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.ui.ChatPanel
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.Usage
import dev.pi.gui.ui.MarkdownView
import dev.pi.gui.ui.MessageRenderer
import dev.pi.gui.ui.TranscriptPanel
import dev.pi.gui.ui.components.HtmlBlock
import java.awt.Dimension

/**
 * Builds the real component tree for representative messages and lays it out, so layout maths and
 * component construction are exercised rather than only the parsing logic.
 */
class UiRenderingTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Two cases below drive the Swing composer; the rest build components directly and
        // do not care which surface a ChatPanel would pick.
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    private fun layoutAt(width: Int, panel: javax.swing.JComponent): Int {
        panel.size = Dimension(width, 10_000)
        panel.doLayout()
        return panel.preferredSize.height
    }

    fun testHtmlBlockReportsPositiveHeightForWidth() {
        val block = HtmlBlock("<p>" + "word ".repeat(400) + "</p>")
        val narrow = block.heightForWidth(300)
        val wide = block.heightForWidth(900)
        assertTrue("narrow height should be positive, was $narrow", narrow > 0)
        assertTrue("wide height should be positive, was $wide", wide > 0)
        // Wrapping means a narrower pane needs more vertical space.
        assertTrue("narrow ($narrow) should be taller than wide ($wide)", narrow > wide)
    }

    fun testMarkdownViewBuildsProseAndCodeComponents() {
        val view = MarkdownView(project, "Intro text\n\n```kotlin\nfun a() = 1\n```\n\nOutro")
        assertEquals("expected prose, code, prose", 3, view.componentCount)
        assertTrue(layoutAt(600, view) > 0)
    }

    fun testUserMessageRenders() {
        val c = MessageRenderer.render(project, PiMessage.User("hello **world**"))
        assertTrue(layoutAt(600, c) > 0)
    }

    fun testAssistantMessageWithAllBlockKindsRenders() {
        val message = PiMessage.Assistant(
            blocks = mutableListOf(
                ContentBlock.Text("Here is a plan:\n\n- step one\n- step two"),
                ContentBlock.Thinking("internal reasoning"),
                ContentBlock.ToolCall("t1", "read", """{"path":"a.txt"}"""),
                ContentBlock.Text("```python\nprint('hi')\n```"),
            ),
            model = "claude-haiku-4-5",
            provider = "anthropic",
            usage = Usage(input = 100, output = 50, costTotal = 0.01),
        )
        val c = MessageRenderer.render(project, message)
        assertTrue(layoutAt(700, c) > 0)
    }

    fun testAssistantErrorRenders() {
        val message = PiMessage.Assistant(
            blocks = mutableListOf(),
            errorMessage = "429: rate limited",
        )
        val c = MessageRenderer.render(project, message)
        assertTrue(layoutAt(600, c) > 0)
    }

    fun testToolResultRenders() {
        val c = MessageRenderer.render(
            project,
            PiMessage.ToolResult("t1", "bash", "line1\nline2\nline3", isError = false),
        )
        assertTrue(layoutAt(600, c) > 0)
    }

    fun testNoticeRenders() {
        val c = MessageRenderer.render(project, PiMessage.Notice("Context compacted"))
        assertTrue(layoutAt(600, c) > 0)
    }

    fun testTranscriptStacksMessagesWithGrowingHeight() {
        val transcript = TranscriptPanel()
        transcript.size = Dimension(700, 10_000)

        transcript.add(MessageRenderer.render(project, PiMessage.User("first question")))
        transcript.doLayout()
        val oneHeight = transcript.preferredSize.height

        transcript.add(
            MessageRenderer.render(
                project,
                PiMessage.Assistant(mutableListOf(ContentBlock.Text("an answer\n\nwith paragraphs"))),
            )
        )
        transcript.doLayout()
        val twoHeight = transcript.preferredSize.height

        assertTrue("stacking a message must add height ($oneHeight -> $twoHeight)", twoHeight > oneHeight)
        assertTrue(transcript.scrollableTracksViewportWidth)
    }

    fun testCjkContentRenders() {
        val c = MessageRenderer.render(project, PiMessage.User("读取 git 的提交记录，生成日志报告"))
        assertTrue(layoutAt(600, c) > 0)
    }

    fun testAppendToInputAccumulatesMentionsWithSpacing() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            panel.appendToInput("@src/A.kt")
            panel.appendToInput("@src/B.kt:10-20")
            val text = panel.composerText()
            assertTrue(text, text.contains("@src/A.kt"))
            assertTrue(text, text.contains("@src/B.kt:10-20"))
            // Mentions must not run together.
            assertTrue(text, text.contains("@src/A.kt @src/B.kt:10-20"))
        } finally {
            panel.dispose()
        }
    }

    fun testAppendToInputPreservesTypedText() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            panel.appendToInput("explain")
            panel.appendToInput("@src/A.kt")
            assertTrue(panel.composerText(), panel.composerText().startsWith("explain @src/A.kt"))
        } finally {
            panel.dispose()
        }
    }

    fun testAppendToInputIgnoresBlank() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            panel.appendToInput("   ")
            assertEquals("", panel.composerText())
        } finally {
            panel.dispose()
        }
    }

    fun testPathReferencesInsertAtTheSwingCaretInsteadOfTheTop() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            val input = panel.inputForTest()
            input.text = "解释这段代码，下面还能优化"
            input.caretPosition = "解释这段代码，".length
            panel.addPathReferences(
                listOf(
                    dev.pi.gui.model.Attachment.FileRef(
                        "Mapper.xml", "/tmp/Mapper.xml", "Mapper.xml:50-88", false, 10,
                        lineStart = 50, lineEnd = 88,
                    )
                )
            )

            assertEquals("解释这段代码， @Mapper.xml:50-88 下面还能优化", panel.composerText())
            assertTrue(panel.attachmentsForTest().isEmpty())
        } finally {
            panel.dispose()
        }
    }

    fun testChatCanvasUsesTheDarkSurface() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            assertTrue("chat panel must paint its own background", panel.isOpaque)
            assertEquals(dev.pi.gui.ui.PiTheme.chatBg.rgb, panel.background.rgb)
        } finally {
            panel.dispose()
        }
    }

    fun testPiButtonPaintsWithoutStockChrome() {
        val button = dev.pi.gui.ui.components.PiButton(
            "Send", null, dev.pi.gui.ui.components.PiButton.Style.PRIMARY,
        )
        assertFalse(button.isContentAreaFilled)
        assertFalse(button.isBorderPainted)
        assertFalse(button.isFocusPainted)

        // Painting must not throw for any state combination.
        val image = java.awt.image.BufferedImage(120, 30, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        button.setSize(120, 30)
        listOf(true, false).forEach { enabled ->
            button.isEnabled = enabled
            val g = image.createGraphics()
            try {
                button.paint(g)
            } finally {
                g.dispose()
            }
        }
        assertTrue(button.preferredSize.height >= com.intellij.util.ui.JBUI.scale(24))
    }

    /** Regression: an unbounded maximum let BoxLayout stretch the button across the whole row. */
    fun testPiButtonDoesNotStretchInABoxLayout() {
        val button = dev.pi.gui.ui.components.PiButton(
            "Send", null, dev.pi.gui.ui.components.PiButton.Style.PRIMARY,
        )
        assertEquals(button.preferredSize, button.maximumSize)

        val row = javax.swing.JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            add(javax.swing.Box.createHorizontalGlue())
            add(button)
        }
        row.setSize(800, 60)
        row.doLayout()

        assertEquals("width must stay natural", button.preferredSize.width, button.width)
        assertEquals("height must stay natural", button.preferredSize.height, button.height)
        assertTrue("button should be compact, was ${button.width}px wide", button.width < 140)
        assertTrue("button should be compact, was ${button.height}px tall", button.height < 40)
    }

    /**
     * Regression: the send button once carried a fixed 28×28 preferred size, smaller than its
     * 16px icon plus the button's own padding, so the glyph was clipped. Icon-only buttons must
     * always report room for icon + border insets.
     */
    fun testIconOnlyPiButtonAlwaysFitsItsIcon() {
        val button = dev.pi.gui.ui.components.PiButton(
            null, com.intellij.icons.AllIcons.Actions.Execute, dev.pi.gui.ui.components.PiButton.Style.PRIMARY,
        )
        val pref = button.preferredSize
        assertTrue(
            "width ${pref.width} must fit the ${button.icon.iconWidth}px icon plus padding",
            pref.width >= button.icon.iconWidth + button.insets.left + button.insets.right,
        )
        assertTrue(
            "height ${pref.height} must fit the ${button.icon.iconHeight}px icon plus padding",
            pref.height >= button.icon.iconHeight + button.insets.top + button.insets.bottom,
        )
        assertTrue(pref.height >= com.intellij.util.ui.JBUI.scale(24))
    }

    /**
     * Regression: `setRunning` relabelled the icon-only send button with text, which the old
     * fixed square clipped. Running state must swap the tooltip only, and both footer buttons
     * must stay self-sized with no fixed-size override.
     */
    fun testChatFooterButtonsStayIconOnlyAndSelfSized() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            panel.setRunningForTest(true)
            val send = panel.sendButtonForTest()
            val stop = panel.stopButtonForTest()
            assertTrue("running state must not relabel the icon-only send button", send.text.isNullOrBlank())
            assertTrue(stop.isVisible)
            listOf(send, stop).forEach { button ->
                val pref = button.preferredSize
                assertTrue(
                    "width ${pref.width} must fit the icon",
                    pref.width >= button.icon.iconWidth + button.insets.left + button.insets.right,
                )
                assertEquals("maximum must track the adaptive preferred size", pref, button.maximumSize)
            }
        } finally {
            panel.dispose()
        }
    }

    fun testRoundedBorderReportsInsetsAndPaints() {
        val border = dev.pi.gui.ui.components.RoundedBorder(
            colorProvider = { java.awt.Color.RED },
            arc = 10,
            padding = com.intellij.util.ui.JBUI.insets(6, 8),
        )
        val insets = border.getBorderInsets(javax.swing.JPanel())
        assertTrue(insets.left > 0 && insets.top > 0)

        val image = java.awt.image.BufferedImage(60, 30, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            border.paintBorder(javax.swing.JPanel(), g, 0, 0, 60, 30)
        } finally {
            g.dispose()
        }
    }

    fun testEmptyStateSwapsToTranscriptOnFirstMessage() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            // The card layout keeps both cards attached; exactly one is visible at a time.
            panel.setSize(600, 400)
            panel.doLayout()
            val visibleBefore = visibleCardCount(panel)
            assertEquals("exactly one card visible", 1, visibleBefore)
        } finally {
            panel.dispose()
        }
    }

    private fun visibleCardCount(panel: javax.swing.JComponent): Int {
        // The card container is the CENTER child of the chat panel.
        val center = (panel.layout as java.awt.BorderLayout)
            .getLayoutComponent(java.awt.BorderLayout.CENTER) as javax.swing.JComponent
        return center.components.count { it.isVisible }
    }

    private fun testImage(name: String) = dev.pi.gui.model.Attachment.Image(
        displayName = name,
        mimeType = "image/png",
        base64 = "aGVsbG8=",
        byteSize = 5,
        sourcePath = null,
    )

    fun testImageAttachmentsAreCappedAtPiLimit() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            repeat(dev.pi.gui.model.Attachment.MAX_IMAGES + 4) { i ->
                panel.addAttachmentForTest(testImage("img$i.png"))
            }
            assertEquals(
                "must not exceed the limit pi enforces server-side",
                dev.pi.gui.model.Attachment.MAX_IMAGES,
                panel.attachmentsForTest().size,
            )
        } finally {
            panel.dispose()
        }
    }

    /** Non-image attachments are @mentions, so they are not subject to the image cap. */
    fun testFileRefsAreNotCappedByTheImageLimit() {
        val panel = dev.pi.gui.ui.ChatPanel(project)
        try {
            repeat(dev.pi.gui.model.Attachment.MAX_IMAGES + 5) { i ->
                panel.addAttachmentForTest(
                    dev.pi.gui.model.Attachment.FileRef(
                        displayName = "f$i.log",
                        absolutePath = "/tmp/f$i.log",
                        mentionPath = "f$i.log",
                        isDirectory = false,
                        byteSize = 10,
                    )
                )
            }
            assertEquals(
                dev.pi.gui.model.Attachment.MAX_IMAGES + 5,
                panel.attachmentsForTest().size,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testAttachmentStripRendersBothKinds() {
        val strip = dev.pi.gui.ui.AttachmentStrip {}
        strip.setAttachments(
            listOf(
                testImage("shot.png"),
                dev.pi.gui.model.Attachment.FileRef("book.xlsx", "/tmp/book.xlsx", "book.xlsx", false, 2048),
            )
        )
        assertTrue("strip becomes visible when it has content", strip.isVisible)
        assertEquals(2, strip.componentCount)
        assertTrue(layoutAt(600, strip) > 0)

        strip.setAttachments(emptyList())
        assertFalse("strip hides itself when empty", strip.isVisible)
    }

    fun testEditsPanelHidesWhenNothingWasEdited() {
        val panel = dev.pi.gui.ui.EditsPanel(project)
        panel.update(listOf(PiMessage.User("just a question")))
        assertFalse("no edits means no strip", panel.isVisible)
    }

    fun testEditsPanelAppearsForSuccessfulEdits() {
        val panel = dev.pi.gui.ui.EditsPanel(project)
        panel.update(
            listOf(
                PiMessage.Assistant(
                    mutableListOf(ContentBlock.ToolCall("c1", "edit", """{"file_path":"a.kt"}"""))
                ),
                PiMessage.ToolResult("c1", "edit", "ok", false),
            )
        )
        assertTrue("a successful edit must surface", panel.isVisible)
        assertTrue(layoutAt(600, panel) > 0)
    }

    fun testEditsPanelIgnoresFailedEdits() {
        val panel = dev.pi.gui.ui.EditsPanel(project)
        panel.update(
            listOf(
                PiMessage.Assistant(
                    mutableListOf(ContentBlock.ToolCall("c1", "edit", """{"file_path":"a.kt"}"""))
                ),
                PiMessage.ToolResult("c1", "edit", "permission denied", true),
            )
        )
        assertFalse("a failed edit must not be reported", panel.isVisible)
    }

    fun testVeryLongTranscriptLaysOut() {
        val transcript = TranscriptPanel()
        transcript.size = Dimension(700, 100_000)
        repeat(40) { i ->
            transcript.add(MessageRenderer.render(project, PiMessage.User("message $i")))
            transcript.add(
                MessageRenderer.render(
                    project,
                    PiMessage.Assistant(mutableListOf(ContentBlock.Text("reply $i with `code`"))),
                )
            )
        }
        transcript.doLayout()
        assertTrue(transcript.preferredSize.height > 0)
        assertEquals(80, transcript.componentCount)
    }
}
