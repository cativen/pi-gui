package dev.pi.gui.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.pi.gui.commands.PiCommand
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * The `/` completion list shown above the composer.
 *
 * The popup never takes focus: the user keeps typing into the composer and the caller forwards
 * Up/Down/Enter/Tab/Escape here. That is also why selection is driven from outside rather than by
 * the list's own key bindings.
 */
class CommandPopup(private val onChosen: (PiCommand) -> Unit) {

    private val model = DefaultListModel<PiCommand>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = PiTheme.surfaceBg
        border = JBUI.Borders.empty(2, 0)
        cellRenderer = CommandRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = locationToIndex(e.point)
                if (index >= 0 && locationToIndex(e.point) < model.size) {
                    selectedIndex = index
                    chooseSelected()
                }
            }
        })
    }

    private val scroll = JBScrollPane(
        list,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = JBUI.Borders.customLine(PiTheme.toolBorder, 1)
        background = PiTheme.surfaceBg
        viewport.background = PiTheme.surfaceBg
    }

    private var popup: JBPopup? = null
    private var owner: JComponent? = null

    val isShowing: Boolean get() = popup?.isVisible == true

    val itemCount: Int get() = model.size

    /**
     * Show [commands] anchored above [anchor].
     *
     * With no commands the popup still appears when [emptyMessage] is set, so "still loading" and
     * "nothing matches" are visible states rather than a popup that mysteriously fails to open.
     * Safe to call on every keystroke: an already-visible popup is resized in place.
     */
    fun show(anchor: JComponent, commands: List<PiCommand>, emptyMessage: String? = null) {
        if (commands.isEmpty() && emptyMessage == null) {
            hide()
            return
        }
        applyTheme()
        list.emptyText.text = emptyMessage ?: ""

        val previous = list.selectedValue
        model.clear()
        commands.forEach { model.addElement(it) }
        // Keep the highlight on the same command while the user narrows the filter.
        val restored = commands.indexOfFirst { it.name == previous?.name }
        if (commands.isNotEmpty()) {
            list.selectedIndex = if (restored >= 0) restored else 0
            list.ensureIndexIsVisible(list.selectedIndex)
        }

        val size = preferredPopupSize(anchor, commands.size)
        val existing = popup
        if (existing != null && existing.isVisible) {
            existing.size = size
            // Re-anchor: the popup grows upward, so its top edge moves as rows are added.
            existing.setLocation(popupLocation(anchor, size))
            return
        }

        owner = anchor
        scroll.preferredSize = size
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setMinSize(size)
            .createPopup()
        popup = created
        created.show(RelativePoint(anchor, Point(0, -size.height - JBUI.scale(4))))
    }

    /** Re-read the palette on every show: the user can flip Light/Dark between two `/` presses. */
    private fun applyTheme() {
        list.background = PiTheme.surfaceBg
        list.fixedCellHeight = PiTheme.uiFont().size + JBUI.scale(10)
        scroll.background = PiTheme.surfaceBg
        scroll.viewport.background = PiTheme.surfaceBg
        scroll.border = JBUI.Borders.customLine(PiTheme.toolBorder, 1)
    }

    private fun preferredPopupSize(anchor: JComponent, count: Int): Dimension {
        val rows = count.coerceIn(1, MAX_VISIBLE_ROWS)
        val rowHeight = list.fixedCellHeight.takeIf { it > 0 } ?: JBUI.scale(ROW_HEIGHT)
        val width = anchor.width.takeIf { it > 0 } ?: JBUI.scale(420)
        return Dimension(width, rows * rowHeight + JBUI.scale(6))
    }

    private fun popupLocation(anchor: JComponent, size: Dimension): Point {
        val origin = anchor.locationOnScreen
        return Point(origin.x, origin.y - size.height - JBUI.scale(4))
    }

    fun moveSelection(delta: Int) {
        if (model.isEmpty) return
        val next = (list.selectedIndex + delta).coerceIn(0, model.size - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    /** @return true when a command was accepted. */
    fun chooseSelected(): Boolean {
        val selected = list.selectedValue ?: return false
        hide()
        onChosen(selected)
        return true
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    /**
     * Colors come from [PiTheme], not the platform's list colors.
     *
     * `ColoredListCellRenderer` and `SimpleTextAttributes` both resolve against the IDE's own LaF,
     * which ignores the user's Light/Dark override for the chat surface — a forced-Light panel in
     * a dark IDE would render unreadably. So the row is plain Swing under our own palette.
     */
    private inner class CommandRenderer : javax.swing.ListCellRenderer<PiCommand> {
        private val nameLabel = javax.swing.JLabel()
        private val descriptionLabel = javax.swing.JLabel()
        private val row = javax.swing.JPanel(java.awt.BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = true
            border = JBUI.Borders.empty(3, 8)
            add(nameLabel, java.awt.BorderLayout.WEST)
            // CENTER lets the JLabel clip the long tail of a skill description with an ellipsis.
            add(descriptionLabel, java.awt.BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out PiCommand>,
            value: PiCommand?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ): java.awt.Component {
            val font = PiTheme.uiFont()
            row.background = if (selected) PiTheme.accent else PiTheme.surfaceBg

            nameLabel.font = font.deriveFont(java.awt.Font.BOLD)
            nameLabel.foreground = if (selected) PiTheme.onAccent else PiTheme.textFg()
            nameLabel.text = value?.let { "/${it.name}" } ?: ""

            descriptionLabel.font = font
            descriptionLabel.foreground = if (selected) PiTheme.onAccent else PiTheme.mutedFg()
            // Skill descriptions run to whole paragraphs; one line is all that fits.
            descriptionLabel.text = value?.description?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

            row.toolTipText = value?.description
            return row
        }
    }

    @org.jetbrains.annotations.TestOnly
    fun itemsForTest(): List<PiCommand> = (0 until model.size).map { model.getElementAt(it) }

    /** The popup's content, so its appearance can be rendered offscreen without a window. */
    @org.jetbrains.annotations.TestOnly
    fun contentForTest(commands: List<PiCommand>): JComponent {
        applyTheme()
        model.clear()
        commands.forEach { model.addElement(it) }
        if (commands.isNotEmpty()) list.selectedIndex = 0
        return scroll
    }

    @org.jetbrains.annotations.TestOnly
    fun selectedIndexForTest(): Int = list.selectedIndex

    companion object {
        private const val MAX_VISIBLE_ROWS = 8
        private const val ROW_HEIGHT = 22
    }
}
