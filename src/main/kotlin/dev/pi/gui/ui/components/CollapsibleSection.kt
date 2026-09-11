package dev.pi.gui.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pi.gui.ui.PiTheme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A header row that expands to reveal [content].
 *
 * Used for thinking blocks, tool calls and tool results, which are useful to have available but
 * would drown the conversation if always expanded.
 */
class CollapsibleSection(
    title: String,
    /**
     * Builds the body on first expand. A transcript is mostly collapsed sections, so building
     * their contents eagerly is the single biggest cost when opening a long session.
     */
    private val contentFactory: () -> JComponent,
    expanded: Boolean = false,
    private val accent: Color? = null,
    icon: Icon? = null,
) : JPanel(BorderLayout()), WidthAware {

    private var content: JComponent? = null

    private val arrow = JBLabel(if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight)
    private val titleLabel = JBLabel(title)
    private val header = JPanel(BorderLayout())
    private var isExpanded = expanded

    private val subtitleLabel = JBLabel("").apply {
        foreground = PiTheme.mutedFg()
        font = font.deriveFont(font.size2D - 1f)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)

        header.isOpaque = false
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        header.border = JBUI.Borders.empty(2, 0)

        val left = JPanel(BorderLayout(4, 0)).apply { isOpaque = false }
        left.add(arrow, BorderLayout.WEST)

        val labels = JPanel(BorderLayout(6, 0)).apply { isOpaque = false }
        titleLabel.foreground = accent ?: PiTheme.mutedFg()
        icon?.let { titleLabel.icon = it }
        labels.add(titleLabel, BorderLayout.WEST)
        labels.add(subtitleLabel, BorderLayout.CENTER)
        left.add(labels, BorderLayout.CENTER)

        header.add(left, BorderLayout.CENTER)

        add(header, BorderLayout.NORTH)
        if (expanded) materialize()

        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = setExpanded(!isExpanded)
        }
        header.addMouseListener(toggle)
        arrow.addMouseListener(toggle)
        titleLabel.addMouseListener(toggle)
        subtitleLabel.addMouseListener(toggle)
    }

    fun setSubtitle(text: String) {
        subtitleLabel.text = text
    }

    fun setTitle(text: String) {
        titleLabel.text = text
    }

    /** Creates the body component the first time it is actually needed. */
    private fun materialize(): JComponent {
        content?.let { return it }
        val built = contentFactory()
        built.border = JBUI.Borders.emptyLeft(16)
        content = built
        add(built, BorderLayout.CENTER)
        return built
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        arrow.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        if (expanded) materialize()
        content?.isVisible = expanded
        revalidate()
        // The transcript's layout depends on our height, so nudge the whole chain.
        var p = parent
        while (p != null) { p.revalidate(); p = p.parent }
        repaint()
    }

    override fun heightForWidth(width: Int): Int {
        val insets = insets
        var h = insets.top + insets.bottom + header.preferredSize.height
        val body = content
        if (isExpanded && body != null) {
            val contentInsets = body.insets
            val inner = (width - insets.left - insets.right - contentInsets.left - contentInsets.right)
                .coerceAtLeast(20)
            h += if (body is WidthAware) {
                body.heightForWidth(inner) + contentInsets.top + contentInsets.bottom
            } else {
                body.preferredSize.height
            }
        }
        return h
    }

    override fun getPreferredSize(): Dimension {
        val w = if (width > 0) width else 600
        return Dimension(w, heightForWidth(w))
    }
}
