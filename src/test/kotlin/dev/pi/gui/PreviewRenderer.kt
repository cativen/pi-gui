package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.Usage
import dev.pi.gui.ui.ChatPanel
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the chat panel offscreen to PNGs so the visual design can be reviewed without
 * launching a full IDE. Not an assertion test — it exists to produce artifacts.
 */
class PreviewRenderer : BasePlatformTestCase() {

    private fun sampleMessages(): List<PiMessage> = listOf(
        PiMessage.User("重构一下这个函数，把重复的部分抽出来 @src/db/query.kt"),
        PiMessage.Assistant(
            blocks = mutableListOf(
                ContentBlock.Text(
                    "我先看一下这个文件的结构。这里有 **三处**重复的分页逻辑，可以抽成一个 `paginate()` 帮助函数。"
                ),
                ContentBlock.Thinking("The user wants deduplication of the pagination code."),
                ContentBlock.ToolCall("t1", "read", """{"path":"src/db/query.kt"}"""),
            ),
        ),
        PiMessage.ToolResult("t1", "read", "fun findUsers(page: Int) { ... }\nfun findOrders(page: Int) { ... }", false),
        PiMessage.Assistant(
            blocks = mutableListOf(
                ContentBlock.Text(
                    "抽出来之后是这样：\n\n" +
                        "```kotlin\n" +
                        "fun <T> paginate(page: Int, size: Int, query: (Int, Int) -> List<T>): Page<T> {\n" +
                        "    val offset = (page - 1) * size\n" +
                        "    return Page(query(offset, size), page)\n" +
                        "}\n" +
                        "```\n\n" +
                        "改动要点：\n\n" +
                        "- 三个查询方法共用同一套 offset 计算\n" +
                        "- 返回类型统一成 `Page<T>`\n"
                ),
            ),
            model = "glm-4.6v",
            provider = "zai-coding-cn",
            // Shape mirrors a real pi entry: most of the prompt served from cache.
            usage = Usage(input = 743, output = 380, cacheRead = 9408, costTotal = 0.0031),
            durationMs = 4230,
        ),
    )

    /**
     * Lays out the whole tree by hand. Offscreen components never become displayable, so
     * `validate()` alone leaves nested children at zero size and the panel paints empty.
     */
    private fun layoutTree(component: java.awt.Component, passes: Int = 3) {
        repeat(passes) {
            deepLayout(component)
        }
    }

    private fun deepLayout(component: java.awt.Component) {
        if (component !is java.awt.Container) return
        component.doLayout()
        component.components.forEach { child ->
            if (child.isVisible) deepLayout(child)
        }
    }

    /** A 2x2 PNG, enough for the thumbnail path to actually decode something. */
    private fun tinyPngBase64(): String {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, 2, 2); dispose() }
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun render(dark: Boolean, file: File) {
        JBColor.setDark(dark)
        // Force the plugin's own theme override too, so the preview exercises the real path.
        dev.pi.gui.settings.PiSettings.getInstance().themeMode =
            if (dark) dev.pi.gui.settings.ThemeMode.DARK else dev.pi.gui.settings.ThemeMode.LIGHT
        val panel = ChatPanel(project)
        val frame = javax.swing.JFrame()
        try {
            panel.setTranscriptForPreview(sampleMessages())
            panel.addAttachmentForTest(
                dev.pi.gui.model.Attachment.Image(
                    "screenshot.png", "image/png", tinyPngBase64(), 4096, null,
                )
            )
            panel.addAttachmentForTest(
                dev.pi.gui.model.Attachment.FileRef(
                    "server.log", "/tmp/server.log", "logs/server.log", false, 20480,
                )
            )
            panel.addAttachmentForTest(
                dev.pi.gui.model.Attachment.FileRef(
                    "report.xlsx", "/tmp/report.xlsx", "report.xlsx", false, 51200,
                )
            )

            // A real (never shown) frame makes the hierarchy displayable, which is what gets
            // scroll panes and the card layout to size their children properly.
            frame.isUndecorated = true
            frame.contentPane.add(panel)
            frame.size = Dimension(760, 900)
            frame.pack()
            frame.setSize(760, 900)
            frame.validate()
            layoutTree(frame)

            val image = BufferedImage(panel.width.coerceAtLeast(1), panel.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
                panel.printAll(g)
            } finally {
                g.dispose()
            }
            file.parentFile.mkdirs()
            ImageIO.write(image, "png", file)
            println("PREVIEW WRITTEN: ${file.absolutePath} (${panel.width}x${panel.height})")
        } finally {
            frame.dispose()
            panel.dispose()
        }
    }

    fun testWritePreviews() {
        render(true, File("/tmp/pi-gui-preview-dark.png"))
        render(false, File("/tmp/pi-gui-preview-light.png"))
        JBColor.setDark(false)
    }
}
