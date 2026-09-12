package dev.pi.gui.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.packages.InstalledPackage
import dev.pi.gui.packages.PackageScope
import dev.pi.gui.packages.PackageSearchResult
import dev.pi.gui.packages.PackagesRegistry
import dev.pi.gui.packages.PackagesService
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.components.DotIcon
import dev.pi.gui.ui.components.PiButton
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * The "Plugins" page: installed packages on the left, install/search on the right.
 *
 * Two ways in — a search over pi.dev and a raw source field. The source field is the reliable one
 * (and the only route for `git:` or local-path sources), so it stays available even when the
 * search cannot reach the registry.
 */
class PluginsSettingsPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val installedModel = DefaultListModel<InstalledPackage>()
    private val installedList = JBList(installedModel)

    private val sourceField = JBTextField()
    private val searchField = JBTextField()
    private val resultsModel = DefaultListModel<PackageSearchResult>()
    private val resultsList = JBList(resultsModel)

    private val globalScope = com.intellij.ui.components.JBRadioButton(PiBundle.message("plugins.scope.global"))
    private val projectScope = com.intellij.ui.components.JBRadioButton(PiBundle.message("plugins.scope.project"))

    private val statusLabel = JBLabel(" ")
    private val summaryLabel = JBLabel(" ")
    private val installButton = PiButton(
        PiBundle.message("plugins.install"), AllIcons.Actions.Download, PiButton.Style.PRIMARY,
    )

    init {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(460))

        ButtonGroup().apply { add(globalScope); add(projectScope) }
        globalScope.isSelected = true
        projectScope.isEnabled = project?.basePath != null
        if (!projectScope.isEnabled) projectScope.toolTipText = PiBundle.message("plugins.noProject")

        installedList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        installedList.cellRenderer = InstalledRenderer()
        installedList.emptyText.text = PiBundle.message("plugins.empty")
        installInstalledContextActions()

        val splitter = OnePixelSplitter(false, 0.32f).apply {
            firstComponent = buildSidebar()
            secondComponent = buildAddPanel()
        }
        add(splitter, BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)

        reload()
    }

    // ------------------------------------------------------------- sidebar

    private fun buildSidebar(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JBScrollPane(installedList).apply { border = JBUI.Borders.empty() },
            BorderLayout.CENTER,
        )
        return panel
    }

    private fun installInstalledContextActions() {
        installedList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "pi.removePackage")
        installedList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "pi.removePackage")
        installedList.actionMap.put("pi.removePackage", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = removeSelected()
        })
    }

    // ---------------------------------------------------------- add panel

    private fun buildAddPanel(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 14)
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }
        header.add(
            JBLabel(PiBundle.message("plugins.add")).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            },
            BorderLayout.WEST,
        )
        header.add(
            JBLabel("pi.dev/packages").apply {
                foreground = PiTheme.linkFg()
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) =
                        BrowserUtil.browse(PackagesRegistry.PACKAGES_URL)
                })
            },
            BorderLayout.EAST,
        )
        root.add(header)

        root.add(
            hint(
                PiBundle.message("plugins.location"),
                PiTheme.monoFont().deriveFont(PiTheme.monoFont().size2D - 1f),
            )
        )
        root.add(Box.createVerticalStrut(JBUI.scale(10)))

        // --- manual source -------------------------------------------------
        root.add(fieldLabel(PiBundle.message("plugins.source")))
        sourceField.emptyText.text = "npm:@scope/package"
        sourceField.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        sourceField.alignmentX = LEFT_ALIGNMENT
        sourceField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pi.install")
        sourceField.actionMap.put("pi.install", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = installFromSourceField()
        })
        root.add(sourceField)
        root.add(Box.createVerticalStrut(JBUI.scale(8)))

        val scopeRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }
        val scopes = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(globalScope)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(projectScope)
        }
        installButton.addActionListener { installFromSourceField() }
        scopeRow.add(scopes, BorderLayout.WEST)
        scopeRow.add(installButton, BorderLayout.EAST)
        root.add(scopeRow)
        root.add(Box.createVerticalStrut(JBUI.scale(6)))

        root.add(hint(PiBundle.message("plugins.examples"), null))
        root.add(Box.createVerticalStrut(JBUI.scale(10)))

        // --- registry search ----------------------------------------------
        root.add(fieldLabel(PiBundle.message("plugins.search")))
        val searchRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        }
        searchField.emptyText.text = PiBundle.message("plugins.search.placeholder")
        searchField.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pi.search")
        searchField.actionMap.put("pi.search", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = runSearch()
        })
        val searchButton = PiButton(
            PiBundle.message("plugins.search.action"), AllIcons.Actions.Find, PiButton.Style.SECONDARY,
        )
        searchButton.addActionListener { runSearch() }
        searchRow.add(searchField, BorderLayout.CENTER)
        searchRow.add(searchButton, BorderLayout.EAST)
        root.add(searchRow)
        root.add(Box.createVerticalStrut(JBUI.scale(6)))

        resultsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultsList.cellRenderer = ResultRenderer()
        resultsList.emptyText.text = PiBundle.message("plugins.search.hint")
        // Choosing a result just fills the source field, so the install path stays identical.
        resultsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                resultsList.selectedValue?.let { r -> sourceField.text = r.installSource }
            }
        }
        val resultsScroll = JBScrollPane(resultsList).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(400), JBUI.scale(180))
        }
        root.add(resultsScroll)

        statusLabel.foreground = PiTheme.mutedFg()
        statusLabel.alignmentX = LEFT_ALIGNMENT
        root.add(Box.createVerticalStrut(JBUI.scale(4)))
        root.add(statusLabel)

        return JBScrollPane(root).apply { border = JBUI.Borders.empty() }
    }

    private fun fieldLabel(text: String): JComponent = JBLabel(text).apply {
        alignmentX = LEFT_ALIGNMENT
        foreground = PiTheme.mutedFg()
        font = font.deriveFont(font.size2D - 1f)
        border = JBUI.Borders.emptyBottom(3)
    }

    private fun hint(text: String, font: Font?): JComponent = JBLabel(text).apply {
        alignmentX = LEFT_ALIGNMENT
        foreground = PiTheme.mutedFg()
        font?.let { this.font = it }
    }

    private fun buildFooter(): JComponent {
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(PiTheme.toolBorder),
                JBUI.Borders.empty(6, 12),
            )
        }
        summaryLabel.foreground = PiTheme.mutedFg()
        footer.add(summaryLabel, BorderLayout.WEST)

        val refresh = PiButton(PiBundle.message("plugins.refresh"), AllIcons.Actions.Refresh, PiButton.Style.SECONDARY)
        refresh.addActionListener { reload() }
        footer.add(refresh, BorderLayout.EAST)
        return footer
    }

    // ------------------------------------------------------------------ data

    fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val installed = PackagesService.listInstalled(project?.basePath)
            val counts = PackagesService.summarize(installed)
            // any(): see SkillsSettingsPanel.reload() — this panel lives in the modal settings
            // dialog, so a default (non-modal) invokeLater would be deferred until it closes.
            ApplicationManager.getApplication().invokeLater({
                installedModel.clear()
                installed.forEach { installedModel.addElement(it) }
                summaryLabel.text = PiBundle.message(
                    "plugins.summary",
                    counts.packages, counts.extensions, counts.skills, counts.prompts, counts.themes,
                )
            }, ModalityState.any())
        }
    }

    private fun selectedScope(): PackageScope =
        if (projectScope.isSelected) PackageScope.PROJECT else PackageScope.GLOBAL

    private fun runSearch() {
        val query = searchField.text.trim()
        statusLabel.text = PiBundle.message("plugins.searching")
        resultsModel.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { PackagesRegistry.search(query) }
            ApplicationManager.getApplication().invokeLater({
                outcome
                    .onSuccess { found ->
                        found.forEach { resultsModel.addElement(it) }
                        statusLabel.text = when {
                            found.isNotEmpty() -> PiBundle.message("plugins.search.count", found.size)
                            // Empty can mean "no matches" or "markup changed"; say so honestly.
                            else -> PiBundle.message("plugins.search.none")
                        }
                    }
                    .onFailure {
                        statusLabel.text = PiBundle.message("plugins.search.failed", it.message ?: "")
                    }
            }, ModalityState.any())
        }
    }

    private fun installFromSourceField() {
        val source = sourceField.text.trim()
        if (source.isEmpty()) {
            statusLabel.text = PiBundle.message("plugins.source.required")
            return
        }
        val scope = selectedScope()
        val target = when (scope) {
            PackageScope.GLOBAL -> PiBundle.message("plugins.scope.global")
            PackageScope.PROJECT -> project?.basePath ?: return
        }

        val confirmed = Messages.showYesNoDialog(
            this,
            PiBundle.message("plugins.install.confirm", source, target),
            PiBundle.message("plugins.add"),
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, PiBundle.message("plugins.installing", source), false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val result = PackagesService.install(source, scope, project?.basePath)
                    ApplicationManager.getApplication().invokeLater({
                        if (result.success) {
                            statusLabel.text = PiBundle.message("plugins.install.done", source)
                            sourceField.text = ""
                            reload()
                        } else {
                            Messages.showErrorDialog(
                                this@PluginsSettingsPanel,
                                PiBundle.message("plugins.install.failed", result.output.takeLast(600)),
                                PiBundle.message("plugins.add"),
                            )
                            statusLabel.text = PiBundle.message("plugins.install.failedShort")
                        }
                    }, ModalityState.any())
                }
            }
        )
    }

    private fun removeSelected() {
        val selected = installedList.selectedValue ?: return
        val confirmed = Messages.showYesNoDialog(
            this,
            PiBundle.message("plugins.remove.confirm", selected.source),
            PiBundle.message("plugins.remove"),
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, PiBundle.message("plugins.removing", selected.source), false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val result = PackagesService.remove(selected.source, selected.scope, project?.basePath)
                    ApplicationManager.getApplication().invokeLater({
                        if (result.success) reload()
                        else Messages.showErrorDialog(
                            this@PluginsSettingsPanel,
                            PiBundle.message("plugins.remove.failed", result.output.takeLast(600)),
                            PiBundle.message("plugins.remove"),
                        )
                    }, ModalityState.any())
                }
            }
        )
    }

    private class InstalledRenderer : ColoredListCellRenderer<InstalledPackage>() {
        override fun customizeCellRenderer(
            list: JList<out InstalledPackage>,
            value: InstalledPackage?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            border = JBUI.Borders.empty(4, 8)
            icon = DotIcon(PiTheme.accent)
            append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (value.scope == PackageScope.PROJECT) {
                append("  " + PiBundle.message("plugins.scope.project"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    private class ResultRenderer : ColoredListCellRenderer<PackageSearchResult>() {
        override fun customizeCellRenderer(
            list: JList<out PackageSearchResult>,
            value: PackageSearchResult?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            border = JBUI.Borders.empty(3, 8)
            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            value.types.takeIf { it.isNotBlank() }?.let {
                append("  [$it]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            value.downloadsLabel().takeIf { it.isNotEmpty() }?.let {
                append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            value.description.takeIf { it.isNotBlank() }?.let {
                append("  — ${it.take(70)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}
