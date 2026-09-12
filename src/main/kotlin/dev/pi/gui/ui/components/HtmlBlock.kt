package dev.pi.gui.ui.components

import com.intellij.ide.BrowserUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.pi.gui.ui.PiTheme
import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.TextUI
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit

/**
 * Read-only, selectable HTML view that reports a correct height for a given width.
 *
 * Swing computes an HTML layout only once it has been given a size, so [heightForWidth] measures
 * through the root [View] rather than trusting `getPreferredSize`, which otherwise wants to lay
 * the whole paragraph out on a single line.
 */
class HtmlBlock(
    html: String = "",
    /** Overrides the body/link color — e.g. white text inside the filled user bubble. */
    private val fgOverride: java.awt.Color? = null,
) : JEditorPane(), WidthAware {

    private var lastWidth = -1
    private var lastHeight = -1

    init {
        editorKit = HTMLEditorKit()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        font = PiTheme.uiFont()
        // Let the caret exist for selection, but never show an insertion cursor.
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                e.url?.let { BrowserUtil.browse(it) }
                    ?: e.description?.takeIf { it.startsWith("http") }?.let { BrowserUtil.browse(it) }
            }
        }
        setHtml(html)
    }

    fun setHtml(bodyHtml: String) {
        text = wrap(bodyHtml)
        caretPosition = 0
        invalidateMeasurement()
    }

    private fun invalidateMeasurement() {
        lastWidth = -1
        lastHeight = -1
    }

    private fun wrap(body: String): String {
        val font = PiTheme.uiFont()
        val fg = PiTheme.toHex(fgOverride ?: PiTheme.textFg())
        val link = PiTheme.toHex(fgOverride ?: PiTheme.linkFg())
        val codeBg = PiTheme.toHex(if (fgOverride != null) PiTheme.userBubbleCodeBg else PiTheme.codeBg())
        val mono = PiTheme.monoFont().family
        val quoteBar = PiTheme.toHex(PiTheme.mutedFg())
        return """
            <html><head><style>
              body { font-family: '${font.family}'; font-size: ${font.size}pt; color: $fg; margin: 0; }
              p { margin: 0 0 6px 0; }
              h3, h4, h5, h6 { margin: 8px 0 4px 0; }
              ul, ol { margin: 2px 0 6px 0; padding-left: 22px; }
              li { margin: 1px 0; }
              code { font-family: '$mono'; font-size: ${font.size}pt; background-color: $codeBg; }
              a { color: $link; }
              blockquote { margin: 4px 0 6px 8px; padding-left: 8px; border-left: 2px solid $quoteBar; color: $quoteBar; }
              table { border-collapse: collapse; margin: 4px 0 8px 0; }
              th { text-align: left; }
              hr { border: 0; border-top: 1px solid $quoteBar; }
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    /**
     * The width the content wants when nothing forces it to wrap. Used by the user bubble to
     * hug short messages instead of stretching to the transcript's full width.
     */
    fun naturalWidth(): Int {
        return try {
            val root: View = (ui as TextUI).getRootView(this)
            root.setSize(Float.MAX_VALUE, Float.MAX_VALUE)
            Math.ceil(root.getPreferredSpan(View.X_AXIS).toDouble()).toInt() + insets.left + insets.right
        } catch (e: Exception) {
            preferredSize.width
        }
    }

    override fun heightForWidth(width: Int): Int {
        if (width <= 0) return preferredSize.height
        if (width == lastWidth && lastHeight >= 0) return lastHeight

        val insets = insets
        val inner = (width - insets.left - insets.right).coerceAtLeast(1)
        val height = try {
            // Give the pane a real width first: the root view measures against the current size.
            setSize(inner, Short.MAX_VALUE.toInt())
            // `ui` resolves to JComponent's ComponentUI field, so go through TextUI explicitly.
            val root: View = (ui as TextUI).getRootView(this)
            root.setSize(inner.toFloat(), Float.MAX_VALUE)
            Math.ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt()
        } catch (e: Exception) {
            preferredSize.height
        } + insets.top + insets.bottom

        lastWidth = width
        lastHeight = height
        return height
    }

    override fun getPreferredSize(): Dimension {
        val w = if (width > 0) width else 600
        return Dimension(w, heightForWidth(w))
    }

    override fun updateUI() {
        super.updateUI()
        // Theme switches change fonts and colors; drop the cached measurement.
        invalidateMeasurement()
    }

    companion object {
        fun textToHtml(text: String): String =
            dev.pi.gui.ui.markdown.Markdown.proseToHtml(text)

        /** Escape and preserve line breaks for content that is not Markdown. */
        fun plainToHtml(text: String): String =
            dev.pi.gui.ui.markdown.Markdown.escapeHtml(text)
                .replace("\n", "<br>")
                .let { "<p>$it</p>" }
    }
}

private const val HONOR_DISPLAY_PROPERTIES = "JEditorPane.honorDisplayProperties"

/** Kept for callers that only need UIUtil's default text antialiasing behavior. */
internal fun applyAntialiasing(pane: JEditorPane) {
    UIUtil.applyStyle(UIUtil.ComponentStyle.REGULAR, pane)
}
