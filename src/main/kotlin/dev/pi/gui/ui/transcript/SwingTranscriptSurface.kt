package dev.pi.gui.ui.transcript

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.PiMessage
import dev.pi.gui.ui.MessageRenderer
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.TranscriptPanel
import dev.pi.gui.ui.components.PiButton
import dev.pi.gui.ui.components.StackPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * The original Swing conversation view, kept as the fallback for IDEs where JCEF is unavailable
 * or switched off.
 *
 * This is the code that used to live directly in `ChatPanel`, moved behind [TranscriptSurface]
 * unchanged. It is slower than the browser — every message costs an HTML document and a layout
 * pass — but it works everywhere, which the browser does not.
 */
class SwingTranscriptSurface(private val project: Project) : TranscriptSurface {

    override var onLoadEarlier: (() -> Unit)? = null

    private val transcript = TranscriptPanel()
    private val scrollPane = JBScrollPane(
        transcript,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    )

    private val cards = CardLayout()
    private val root = JPanel(cards)
    private var emptyState: JComponent

    private var streamingComponent: JComponent? = null
    private var loadEarlierRow: JComponent? = null
    private var rendered = 0

    init {
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.background = PiTheme.chatBg
        scrollPane.background = PiTheme.chatBg
        transcript.background = PiTheme.chatBg
        transcript.isOpaque = true

        root.isOpaque = true
        root.background = PiTheme.chatBg
        emptyState = buildEmptyState()
        root.add(emptyState, CARD_EMPTY)
        root.add(scrollPane, CARD_CHAT)
        cards.show(root, CARD_EMPTY)
    }

    override val component: JComponent get() = root

    override fun setMessages(messages: List<PiMessage>, hiddenCount: Int) {
        transcript.removeAll()
        streamingComponent = null
        loadEarlierRow =
            if (hiddenCount > 0) buildLoadEarlierRow(hiddenCount).also { transcript.add(it) } else null
        messages.forEach { transcript.add(MessageRenderer.render(project, it)) }
        rendered = messages.size

        if (messages.isEmpty()) cards.show(root, CARD_EMPTY) else cards.show(root, CARD_CHAT)
        transcript.revalidate()
        transcript.repaint()
    }

    override fun appendMessage(message: PiMessage) {
        rendered++
        cards.show(root, CARD_CHAT)
        // Insert before the live streaming bubble so ordering stays chronological.
        val streamingIdx = streamingComponent?.let { comp ->
            transcript.components.indexOfFirst { it === comp }
        } ?: -1
        val component = MessageRenderer.render(project, message)
        if (streamingIdx >= 0) transcript.add(component, streamingIdx) else transcript.add(component)
        transcript.revalidate()
        transcript.repaint()
        if (isNearBottom()) scrollToBottom()
    }

    override fun prependMessages(messages: List<PiMessage>, hiddenCount: Int) {
        val pinned = isNearBottom()
        loadEarlierRow?.let { transcript.remove(it) }
        loadEarlierRow = null
        messages.reversed().forEach { transcript.add(MessageRenderer.render(project, it), 0) }
        rendered += messages.size
        if (hiddenCount > 0) {
            val row = buildLoadEarlierRow(hiddenCount)
            loadEarlierRow = row
            transcript.add(row, 0)
        }
        transcript.revalidate()
        transcript.repaint()
        // Content grew above the viewport; without re-pinning the view would drift upward.
        if (pinned) scrollToBottom()
    }

    override fun setStreaming(message: PiMessage?) {
        if (message == null) {
            streamingComponent?.let { transcript.remove(it) }
            streamingComponent = null
            transcript.revalidate()
            transcript.repaint()
            return
        }
        val renderedComponent = MessageRenderer.render(project, message)
        streamingComponent?.let { transcript.remove(it) }
        cards.show(root, CARD_CHAT)
        transcript.add(renderedComponent)
        streamingComponent = renderedComponent
        transcript.revalidate()
        transcript.repaint()
        if (isNearBottom()) scrollToBottom()
    }

    override fun showEmptyState(projectPath: String?) {
        transcript.removeAll()
        streamingComponent = null
        loadEarlierRow = null
        rendered = 0
        cards.show(root, CARD_EMPTY)
    }

    override fun applySettings() {
        scrollPane.viewport.background = PiTheme.chatBg
        scrollPane.background = PiTheme.chatBg
        transcript.background = PiTheme.chatBg
        root.background = PiTheme.chatBg
        root.remove(emptyState)
        emptyState = buildEmptyState()
        root.add(emptyState, CARD_EMPTY)
    }

    override fun isNearBottom(): Boolean {
        val bar = scrollPane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(120)
    }

    /**
     * Jump straight to the newest message.
     *
     * The viewport is moved directly rather than through `verticalScrollBar.value`, because the
     * platform's scrollbar runs the IDE's smooth-scrolling interpolator: assigning a value glided
     * down the whole transcript, repainting every frame, to land where it could have jumped in
     * one step.
     */
    override fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val viewport = scrollPane.viewport
            val view = viewport.view ?: return@invokeLater
            scrollPane.validate()
            // `preferredSize` covers the window between rebuilding and the layout pass landing,
            // where `height` still belongs to the old content.
            val contentHeight = maxOf(view.height, view.preferredSize.height)
            val y = (contentHeight - viewport.extentSize.height).coerceAtLeast(0)
            viewport.viewPosition = Point(0, y)
        }
    }

    override fun renderedCount(): Int = rendered

    private fun buildLoadEarlierRow(hidden: Int): JComponent {
        val row = StackPanel(0).apply { border = JBUI.Borders.empty(4, 0, 10, 0) }
        val button = PiButton(PiBundle.message("chat.loadEarlier", hidden), null, PiButton.Style.SECONDARY)
        button.addActionListener { onLoadEarlier?.invoke() }
        row.add(
            JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0)).apply {
                isOpaque = false
                add(button)
            }
        )
        return row
    }

    /** Centered watermark shown until the conversation has content. */
    private fun buildEmptyState(): JComponent {
        val wrapper = JPanel(GridBagLayout()).apply {
            isOpaque = true
            background = PiTheme.chatBg
        }
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val glyph = JBLabel("π").apply {
            font = PiTheme.uiFont().deriveFont(Font.BOLD, JBUI.scale(56).toFloat())
            foreground = PiTheme.logoFg()
            alignmentX = JComponent.CENTER_ALIGNMENT
        }
        val title = JBLabel(PiBundle.message("chat.empty.title")).apply {
            font = PiTheme.uiFont().deriveFont(Font.PLAIN, JBUI.scale(15).toFloat())
            foreground = PiTheme.mutedFg()
            alignmentX = JComponent.CENTER_ALIGNMENT
        }
        val hint = JBLabel(project.basePath ?: "").apply {
            font = PiTheme.uiFont().deriveFont(PiTheme.uiFont().size2D - 1f)
            foreground = PiTheme.noticeFg
            alignmentX = JComponent.CENTER_ALIGNMENT
        }
        column.add(glyph)
        column.add(Box.createVerticalStrut(JBUI.scale(10)))
        column.add(title)
        column.add(Box.createVerticalStrut(JBUI.scale(4)))
        column.add(hint)
        wrapper.add(column, GridBagConstraints())
        return wrapper
    }

    override fun dispose() {
        transcript.removeAll()
    }

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_CHAT = "chat"
    }
}
