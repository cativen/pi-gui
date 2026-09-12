package dev.pi.gui.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.skills.SearchTimeoutException
import dev.pi.gui.skills.SkillScope
import dev.pi.gui.skills.SkillSearchResult
import dev.pi.gui.skills.SkillsRegistry
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.components.PiButton
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * Searches skills.sh and installs the chosen skill, either globally or into the current project.
 *
 * Installation shells out to `npx skills add`, which downloads and runs third-party code, so it
 * only ever happens on an explicit click and the scope is spelled out on the button.
 */
class AddSkillDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val queryField = JBTextField()
    private val model = DefaultListModel<SkillSearchResult>()
    private val results = JBList(model)
    private val statusLabel = JBLabel(" ")

    private val installGlobal = PiButton(
        PiBundle.message("skills.install.global"), AllIcons.Actions.Download, PiButton.Style.PRIMARY,
    )
    private val installProject = PiButton(
        PiBundle.message("skills.install.project"), AllIcons.Actions.Download, PiButton.Style.SECONDARY,
    )

    private var installedAnything = false

    init {
        title = PiBundle.message("skills.add")
        setOKButtonText(PiBundle.message("skills.close"))
        init()
        updateButtons()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(620), JBUI.scale(440))
        }

        val searchRow = JPanel(BorderLayout(JBUI.scale(6), 0))
        queryField.emptyText.text = PiBundle.message("skills.search.placeholder")
        queryField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pi.search")
        queryField.actionMap.put("pi.search", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = runSearch()
        })
        val searchButton = PiButton(PiBundle.message("skills.search"), AllIcons.Actions.Find, PiButton.Style.SECONDARY)
        searchButton.addActionListener { runSearch() }
        searchRow.add(queryField, BorderLayout.CENTER)
        searchRow.add(searchButton, BorderLayout.EAST)

        results.selectionMode = ListSelectionModel.SINGLE_SELECTION
        results.cellRenderer = ResultRenderer()
        results.emptyText.text = PiBundle.message("skills.search.hint")
        results.addListSelectionListener { if (!it.valueIsAdjusting) updateButtons() }

        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
        }
        statusLabel.foreground = PiTheme.mutedFg()
        footer.add(statusLabel, BorderLayout.WEST)

        val buttons = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
        }
        installProject.addActionListener { install(SkillScope.PROJECT) }
        installGlobal.addActionListener { install(SkillScope.GLOBAL) }
        buttons.add(installProject)
        buttons.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
        buttons.add(installGlobal)
        footer.add(buttons, BorderLayout.EAST)

        root.add(searchRow, BorderLayout.NORTH)
        root.add(JBScrollPane(results), BorderLayout.CENTER)
        root.add(footer, BorderLayout.SOUTH)
        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = queryField

    private fun updateButtons() {
        val selected = results.selectedValue != null
        installGlobal.isEnabled = selected
        installProject.isEnabled = selected && project?.basePath != null
        installProject.toolTipText =
            if (project?.basePath == null) PiBundle.message("skills.install.noProject") else null
    }

    private fun runSearch() {
        val query = queryField.text.trim()
        if (query.isEmpty()) return
        statusLabel.text = PiBundle.message("skills.searching")
        model.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { SkillsRegistry.search(query) }
            // any(): this dialog is modal, so a default (non-modal) invokeLater from a pooled
            // thread would be deferred until the dialog closes — results would never appear.
            ApplicationManager.getApplication().invokeLater({
                outcome
                    .onSuccess { found ->
                        found.forEach { model.addElement(it) }
                        statusLabel.text =
                            if (found.isEmpty()) PiBundle.message("skills.search.none")
                            else PiBundle.message("skills.search.count", found.size)
                        if (found.isNotEmpty()) results.selectedIndex = 0
                        updateButtons()
                    }
                    .onFailure { error ->
                        statusLabel.text = if (error is SearchTimeoutException) {
                            PiBundle.message("skills.search.timeout")
                        } else {
                            PiBundle.message("skills.search.failed", error.message ?: "")
                        }
                    }
            }, ModalityState.any())
        }
    }

    private fun install(scope: SkillScope) {
        val selected = results.selectedValue ?: return
        val target = when (scope) {
            SkillScope.GLOBAL -> PiBundle.message("skills.scope.global")
            SkillScope.PROJECT -> project?.basePath ?: return
        }

        val confirmed = Messages.showYesNoDialog(
            contentPanel,
            PiBundle.message("skills.install.confirm", selected.id, target),
            PiBundle.message("skills.add"),
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, PiBundle.message("skills.installing", selected.id), false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val outcome = SkillsRegistry.install(selected.id, scope, project?.basePath)
                    ApplicationManager.getApplication().invokeLater({
                        if (outcome.success) {
                            installedAnything = true
                            statusLabel.text = PiBundle.message("skills.install.done", selected.name)
                        } else {
                            Messages.showErrorDialog(
                                contentPanel,
                                PiBundle.message("skills.install.failed", outcome.output.takeLast(600)),
                                PiBundle.message("skills.add"),
                            )
                            statusLabel.text = PiBundle.message("skills.install.failedShort")
                        }
                    }, ModalityState.any())
                }
            }
        )
    }

    /** True when something was installed, so the caller knows to refresh its list. */
    fun installedSomething(): Boolean = installedAnything

    override fun doOKAction() {
        super.doOKAction()
    }

    private class ResultRenderer : ColoredListCellRenderer<SkillSearchResult>() {
        override fun customizeCellRenderer(
            list: JList<out SkillSearchResult>,
            value: SkillSearchResult?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            border = JBUI.Borders.empty(4, 8)
            icon = AllIcons.Nodes.Plugin
            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${value.id}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            value.installsLabel().takeIf { it.isNotEmpty() }?.let {
                append("   $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}
