package dev.pi.gui.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
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
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The "Skills" page: every skill the agent can see, with an enable switch and an entry point for
 * installing new ones from skills.sh.
 */
class SkillsSettingsPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<SkillInfo>()
    private val list = JBList(model)

    private val detailCards = CardLayout()
    private val detail = JPanel(detailCards)

    private val pathLabel = JBLabel()
    private val scopeLabel = JBLabel()
    private val nameValue = JBLabel()
    private val descriptionValue = JBLabel()
    private val enabledToggle = OnOffButton()

    private var suppressToggleEvents = false

    init {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(460))

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SkillCellRenderer()
        list.emptyText.text = PiBundle.message("skills.empty")
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) showDetail(list.selectedValue)
        }

        detail.add(buildEmptyDetail(), CARD_EMPTY)
        detail.add(buildDetail(), CARD_DETAIL)

        val splitter = OnePixelSplitter(false, 0.32f).apply {
            firstComponent = buildSidebar()
            secondComponent = detail
        }
        add(splitter, BorderLayout.CENTER)

        enabledToggle.addActionListener {
            if (suppressToggleEvents) return@addActionListener
            toggleSelected(enabledToggle.isSelected)
        }

        reload()
    }

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
        panel.add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

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

    // ------------------------------------------------------------------ data

    fun reload() {
        val previous = list.selectedValue?.filePath
        ApplicationManager.getApplication().executeOnPooledThread {
            val skills = SkillsService.listSkills(project?.basePath)
            ApplicationManager.getApplication().invokeLater {
                model.clear()
                skills.forEach { model.addElement(it) }
                val index = skills.indexOfFirst { it.filePath == previous }
                if (index >= 0) list.selectedIndex = index
                else if (skills.isNotEmpty()) list.selectedIndex = 0
                else showDetail(null)
            }
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

    private fun toggleSelected(enabled: Boolean) {
        val skill = list.selectedValue ?: return
        if (!SkillsService.setEnabled(skill, enabled)) {
            Messages.showErrorDialog(
                this,
                PiBundle.message("skills.toggleFailed", skill.displayPath()),
                PiBundle.message("settings.tab.skills"),
            )
            suppressToggleEvents = true
            enabledToggle.isSelected = skill.enabled
            suppressToggleEvents = false
            return
        }
        // Re-read from disk so the list badge reflects what was actually written.
        val index = list.selectedIndex
        SkillsService.read(java.io.File(skill.filePath), skill.scope)?.let {
            model.setElementAt(it, index)
        }
        list.repaint()
    }

    private fun openAddDialog() {
        val dialog = AddSkillDialog(project)
        if (dialog.showAndGet()) reload()
    }

    private inner class SkillCellRenderer : ColoredListCellRenderer<SkillInfo>() {
        override fun customizeCellRenderer(
            list: JList<out SkillInfo>,
            value: SkillInfo?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            border = JBUI.Borders.empty(4, 8)
            icon = DotIcon(if (value.enabled) PiTheme.accent else PiTheme.buttonBorder)
            append(
                value.name,
                if (value.enabled) SimpleTextAttributes.REGULAR_ATTRIBUTES
                else SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            if (value.scope == SkillScope.PROJECT) {
                append("  " + PiBundle.message("skills.scope.project"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_DETAIL = "detail"
    }
}
