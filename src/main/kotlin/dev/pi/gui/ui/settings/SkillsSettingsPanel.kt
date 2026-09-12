package dev.pi.gui.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.OnOffButton
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.skills.SkillInfo
import dev.pi.gui.skills.SkillScope
import dev.pi.gui.skills.SkillsService
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.components.DotIcon
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Skills" page: every skill the agent can see, one row per skill with its enable switch
 * right on the row, and an entry point for installing new ones from skills.sh.
 *
 * The rows are real components (not a [javax.swing.JList]) on purpose: the switch has to be a
 * live [OnOffButton] the user can flip without opening the detail pane, and renderer stamps are
 * not clickable.
 */
class SkillsSettingsPanel(
    private val project: Project?,
    /** Overridden by tests so the panel can run against a temporary skills directory. */
    private val skillsProvider: () -> List<SkillInfo> = { SkillsService.listSkills(project?.basePath) },
) : JPanel(BorderLayout()) {

    private val detailCards = CardLayout()
    private val detail = JPanel(detailCards)

    private val pathLabel = JBLabel()
    private val scopeLabel = JBLabel()
    private val nameValue = JBLabel()
    private val descriptionValue = JBLabel()
    private val enabledToggle = OnOffButton()

    private val rowsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val emptyLabel = JBLabel(PiBundle.message("skills.empty")).apply {
        border = JBUI.Borders.empty(12, 10)
        foreground = PiTheme.mutedFg()
        isVisible = false
    }
    private var rows: List<SkillRow> = emptyList()

    private var suppressToggleEvents = false

    init {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(460))

        detail.add(buildEmptyDetail(), CARD_EMPTY)
        detail.add(buildDetail(), CARD_DETAIL)

        val splitter = OnePixelSplitter(false, 0.32f).apply {
            firstComponent = buildSidebar()
            secondComponent = detail
        }
        add(splitter, BorderLayout.CENTER)

        enabledToggle.addActionListener {
            if (suppressToggleEvents) return@addActionListener
            rows.firstOrNull { it.current.filePath == selectedFilePath }
                ?.let { toggle(it, enabledToggle.isSelected) }
        }

        reload()
    }

    private var selectedFilePath: String? = null

    private fun buildSidebar(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JBLabel(PiBundle.message("skills.global")).apply {
                border = JBUI.Borders.empty(8, 10, 4, 10)
                foreground = PiTheme.mutedFg()
                font = font.deriveFont(font.size2D - 1f)
            },
            BorderLayout.NORTH,
        )
        panel.add(
            JBScrollPane(SkillsRowsViewport()).apply { border = JBUI.Borders.empty() },
            BorderLayout.CENTER,
        )

        val addButton = JBLabel(PiBundle.message("skills.add"), AllIcons.General.Add, JBLabel.LEFT).apply {
            border = JBUI.Borders.empty(8, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = openAddDialog()
            })
        }
        panel.add(addButton, BorderLayout.SOUTH)
        return panel
    }

    private fun buildEmptyDetail(): JComponent = JPanel(BorderLayout()).apply {
        add(
            JBLabel(PiBundle.message("skills.selectHint")).apply {
                horizontalAlignment = JBLabel.CENTER
                foreground = PiTheme.mutedFg()
            },
            BorderLayout.CENTER,
        )
    }

    private fun buildDetail(): JComponent {
        val root = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(10, 14) }

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { isOpaque = false }
        val left = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
        }
        scopeLabel.apply {
            border = JBUI.Borders.empty(1, 5)
            font = font.deriveFont(font.size2D - 1f)
            foreground = PiTheme.mutedFg()
        }
        pathLabel.apply {
            font = PiTheme.monoFont().deriveFont(PiTheme.monoFont().size2D - 1f)
            foreground = PiTheme.mutedFg()
        }
        left.add(scopeLabel)
        left.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
        left.add(pathLabel)
        header.add(left, BorderLayout.WEST)
        header.add(enabledToggle, BorderLayout.EAST)

        val body = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(18)
            isOpaque = false
        }
        body.add(fieldLabel(PiBundle.message("skills.name")))
        nameValue.font = PiTheme.monoFont().deriveFont(Font.BOLD)
        body.add(leftAligned(nameValue))
        body.add(javax.swing.Box.createVerticalStrut(JBUI.scale(14)))
        body.add(fieldLabel(PiBundle.message("skills.description")))
        descriptionValue.verticalAlignment = JBLabel.TOP
        body.add(leftAligned(descriptionValue))
        body.add(javax.swing.Box.createVerticalGlue())

        root.add(header, BorderLayout.NORTH)
        root.add(body, BorderLayout.CENTER)
        return root
    }

    private fun fieldLabel(text: String): JComponent = leftAligned(
        JBLabel(text).apply {
            foreground = PiTheme.mutedFg()
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyBottom(3)
        }
    )

    private fun leftAligned(component: JComponent): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(component, BorderLayout.CENTER)
    }

    // ------------------------------------------------------------------ rows

    /** One skill in the list: click the text to inspect it, flip the switch to toggle it. */
    private inner class SkillRow(initial: SkillInfo) : JPanel(BorderLayout()) {
        var current: SkillInfo = initial
            private set

        private val dot = JBLabel()
        private val name = JBLabel().apply { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
        val toggle = OnOffButton().apply {
            toolTipText = PiBundle.message("skills.enable.tip")
            addActionListener {
                if (suppressToggleEvents) return@addActionListener
                toggle(this@SkillRow, isSelected)
            }
        }

        init {
            isOpaque = true
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(PiTheme.messageDivider),
                JBUI.Borders.empty(6, 10, 6, 6),
            )

            val west = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = select(current)
                })
            }
            west.add(dot)
            west.add(name)
            add(west, BorderLayout.CENTER)
            add(toggle, BorderLayout.EAST)
            alignmentX = LEFT_ALIGNMENT
            refresh(initial)
        }

        fun refresh(skill: SkillInfo) {
            current = skill
            dot.icon = DotIcon(if (skill.enabled) PiTheme.accent else PiTheme.buttonBorder)
            name.text = skill.name
            name.foreground = if (skill.enabled) PiTheme.textFg() else PiTheme.mutedFg()
            suppressToggleEvents = true
            toggle.isSelected = skill.enabled
            suppressToggleEvents = false
        }
    }

    private fun select(skill: SkillInfo) {
        selectedFilePath = skill.filePath
        val selectedBg = rowSelectedBg
        rows.forEach { it.background = if (it.current.filePath == skill.filePath) selectedBg else Color(0, 0, 0, 0) }
        showDetail(skill)
    }

    private fun toggle(row: SkillRow, enabled: Boolean) {
        if (!SkillsService.setEnabled(row.current, enabled)) {
            Messages.showErrorDialog(
                this,
                PiBundle.message("skills.toggleFailed", row.current.displayPath()),
                PiBundle.message("settings.tab.skills"),
            )
            row.refresh(row.current) // revert the switch to what is actually on disk
            return
        }
        // Re-read from disk so the row reflects what was actually written.
        SkillsService.read(File(row.current.filePath), row.current.scope)?.let { updated ->
            row.refresh(updated)
            if (updated.filePath == selectedFilePath) showDetail(updated)
        }
    }

    // ------------------------------------------------------------------ data

    fun reload() {
        val previous = selectedFilePath
        ApplicationManager.getApplication().executeOnPooledThread {
            val skills = runCatching(skillsProvider).getOrDefault(emptyList())
            // any(): this panel sits inside the modal settings dialog, and an invokeLater posted
            // from a pooled thread defaults to the non-modal state, which the IDE defers until
            // every modal dialog closes — without this the list would stay empty while open.
            ApplicationManager.getApplication().invokeLater({
                rows.forEach { rowsPanel.remove(it) }
                rows = skills.map { SkillRow(it) }
                rows.forEach { rowsPanel.add(it) }
                rowsPanel.revalidate()
                rowsPanel.repaint()
                emptyLabel.isVisible = rows.isEmpty()

                val selected = rows.firstOrNull { it.current.filePath == previous } ?: rows.firstOrNull()
                if (selected != null) select(selected.current) else {
                    selectedFilePath = null
                    showDetail(null)
                }
            }, ModalityState.any())
        }
    }

    private fun showDetail(skill: SkillInfo?) {
        if (skill == null) {
            detailCards.show(detail, CARD_EMPTY)
            return
        }
        scopeLabel.text = when (skill.scope) {
            SkillScope.GLOBAL -> PiBundle.message("skills.scope.global")
            SkillScope.PROJECT -> PiBundle.message("skills.scope.project")
        }
        pathLabel.text = skill.displayPath()
        nameValue.text = skill.name
        descriptionValue.text = "<html><body style='width:420px'>" +
            dev.pi.gui.ui.markdown.Markdown.escapeHtml(skill.description) + "</body></html>"

        suppressToggleEvents = true
        enabledToggle.isSelected = skill.enabled
        suppressToggleEvents = false

        detailCards.show(detail, CARD_DETAIL)
    }

    private fun openAddDialog() {
        val dialog = AddSkillDialog(project)
        if (dialog.showAndGet()) reload()
    }

    // -------------------------------------------------------------- test API

    internal fun toggleForTest(skillName: String): OnOffButton? =
        rows.firstOrNull { it.current.name == skillName }?.toggle

    internal fun skillNamesForTest(): List<String> = rows.map { it.current.name }

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_DETAIL = "detail"
    }

    private val rowSelectedBg: java.awt.Color get() = PiTheme.buttonPressed

    /** The scrollable column hosting the rows; tracks the viewport width so rows never clip. */
    private inner class SkillsRowsViewport : JPanel(), javax.swing.Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(rowsPanel)
            add(emptyLabel)
            add(Box.createVerticalGlue())
        }

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 10
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 60
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    }
}
