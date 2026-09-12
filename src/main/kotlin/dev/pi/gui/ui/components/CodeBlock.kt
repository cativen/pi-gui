package dev.pi.gui.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.markdown.CodeHighlighter
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * A fenced code block: a language/copy header above a syntax-coloured, selectable body.
 *
 * Colouring comes from the IDE's lexer and colour scheme via [CodeHighlighter], so it matches the
 * editor without paying for an editor instance per snippet.
 */
class CodeBlock(
    project: Project?,
    private val language: String?,
    fullCode: String,
) : JPanel(BorderLayout()), WidthAware {

    /** What the copy button yields: always the whole snippet, even when the view is clipped. */
    private val code: String = fullCode

    /**
     * Highlighting and HTML layout are both linear in the input, so a runaway snippet is capped
     * before it ever reaches them. Copy still hands over the full text.
     */
    private val rendered: String = clip(fullCode)

    private val header: JPanel = buildHeader()
    private val body = HtmlBlock(
        CodeHighlighter.toHtml(project, language, rendered),
        monospaceText = rendered,
    )

    private val bodyHolder = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = PiTheme.codeBg()
                val arc = JBUI.scale(8)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = PiTheme.toolBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)

        bodyHolder.isOpaque = false
        bodyHolder.border = JBUI.Borders.empty(6, 8)
        bodyHolder.add(body, BorderLayout.CENTER)

        add(header, BorderLayout.NORTH)
        add(bodyHolder, BorderLayout.CENTER)
    }

    private fun buildHeader(): JPanel {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 2, 3, 2)
        }
        row.add(
            JBLabel(language?.lowercase() ?: "text").apply {
                foreground = PiTheme.mutedFg()
                font = PiTheme.monoFont().deriveFont(font.size2D - 1f)
            },
            BorderLayout.WEST,
        )
        row.add(
            JBLabel(AllIcons.Actions.Copy).apply {
                toolTipText = "Copy code"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        CopyPasteManager.getInstance().setContents(StringSelection(code))
                    }
                })
            },
            BorderLayout.EAST,
        )
        return row
    }

    private fun clip(text: String): String {
        val MAX_LINES = 800
        val MAX_CHARS = 120_000
        val lines = text.lineSequence().take(MAX_LINES + 1).toList()
        if (lines.size <= MAX_LINES && text.length <= MAX_CHARS) return text
        val head = lines.take(MAX_LINES).joinToString("\n").take(MAX_CHARS)
        val total = text.count { it == '\n' } + 1
        return head + "\n\n… " + dev.pi.gui.i18n.PiBundle.message("message.truncated", total)
    }

    override fun heightForWidth(width: Int): Int {
        val outer = insets
        val inner = (width - outer.left - outer.right).coerceAtLeast(20)
        val holderInsets = bodyHolder.insets
        val bodyWidth = (inner - holderInsets.left - holderInsets.right).coerceAtLeast(20)
        return outer.top + outer.bottom +
            header.preferredSize.height +
            holderInsets.top + holderInsets.bottom +
            body.heightForWidth(bodyWidth)
    }

    override fun getPreferredSize(): Dimension {
        val w = if (width > 0) width else 600
        return Dimension(w, heightForWidth(w))
    }
}
