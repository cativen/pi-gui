package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.PiMessage
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.MessageRenderer
import dev.pi.gui.ui.components.HtmlBlock
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * The user bubble must never clip its own text.
 *
 * This is checked in pixels on purpose. The bug it guards against was invisible to every
 * geometric assertion: `naturalWidth()`, `heightForWidth()` and the laid-out bounds all agreed
 * with each other, and all of them were wrong together — a view handed an allocation of exactly
 * its preferred span still wraps, so the bubble painted a line taller than it was measured and
 * cut the last line off. Only rendering and looking at the result catches that.
 */
class UserBubbleClippingTest : BasePlatformTestCase() {

    private var originalFontSize = 0

    override fun setUp() {
        super.setUp()
        originalFontSize = PiSettings.getInstance().chatFontSize
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().chatFontSize = originalFontSize
        } finally {
            super.tearDown()
        }
    }

    private val texts = listOf(
        "你好",
        "为什么我输入的内容被折叠了，我的天啊",
        "这是一句比较长的中文消息用来测试气泡换行是否会把最后一个字裁掉啊啊啊",
        "hello there",
        "hello there this is an english message long enough to wrap somewhere",
        "Mixed 中英文 message with numbers 12345 and punctuation!",
        "a",
    )

    fun testUserBubbleNeverClipsItsText() {
        // 0 means "follow the IDE font"; the rest are the slider's range.
        listOf(0, 12, 14, 16, 20, 24).forEach { fontSize ->
            PiSettings.getInstance().chatFontSize = fontSize
            texts.forEach { text -> assertNoClipping(text, fontSize) }
        }
    }

    private fun assertNoClipping(text: String, fontSize: Int) {
        val panel = MessageRenderer.render(project, PiMessage.User(text))
        val width = 700
        panel.setSize(width, panel.heightForWidth(width))
        layoutTree(panel)
        layoutTree(panel)

        val blocks = mutableListOf<HtmlBlock>()
        collectHtmlBlocks(panel, blocks)
        assertTrue("no rendered text found for '$text'", blocks.isNotEmpty())

        blocks.forEach { block ->
            val allottedWidth = block.width
            val allottedHeight = block.height
            assertTrue("block was never laid out for '$text'", allottedWidth > 0)

            val inkBottom = paintedInkBottom(block, allottedWidth)
            assertTrue(
                "user bubble clipped '$text' at font size $fontSize: " +
                    "ink reaches y=$inkBottom but only ${allottedHeight}px were allotted " +
                    "(width $allottedWidth)",
                inkBottom < allottedHeight,
            )
        }
    }

    /** Paints on a canvas far taller than the allotment and reports the lowest row with ink. */
    private fun paintedInkBottom(block: HtmlBlock, width: Int): Int {
        val canvasHeight = 400
        block.setSize(width, canvasHeight)
        block.doLayout()

        val image = BufferedImage(width, canvasHeight, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, canvasHeight)
            block.paint(g)
        } finally {
            g.dispose()
        }

        val background = Color.BLACK.rgb
        for (y in canvasHeight - 1 downTo 0) {
            for (x in 0 until width) {
                if (image.getRGB(x, y) != background) return y
            }
        }
        return -1
    }

    /**
     * The specific invariant behind the fix: a bubble sized to this width must not wrap, so it has
     * to be strictly wider than the span the view reports as preferred.
     */
    fun testNaturalWidthLeavesRoomForTheViewToNotWrap() {
        val block = HtmlBlock(
            dev.pi.gui.ui.markdown.Markdown.proseToHtml("为什么我输入的内容被折叠了，我的天啊"),
            dev.pi.gui.ui.PiTheme.userBubbleFg,
        )
        val natural = block.naturalWidth()
        val oneLineBottom = paintedInkBottom(block, natural + 80)
        assertTrue("reference render produced nothing", oneLineBottom > 0)
        assertEquals(
            "text must still be on one line at its natural width",
            oneLineBottom,
            paintedInkBottom(block, natural),
        )
    }

    private fun collectHtmlBlocks(c: java.awt.Component, out: MutableList<HtmlBlock>) {
        if (c is HtmlBlock) out.add(c)
        if (c is java.awt.Container) c.components.forEach { collectHtmlBlocks(it, out) }
    }

    private fun layoutTree(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.doLayout()
            c.components.forEach { layoutTree(it) }
        }
    }
}
