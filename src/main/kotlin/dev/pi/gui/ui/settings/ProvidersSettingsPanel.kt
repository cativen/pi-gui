package dev.pi.gui.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.providers.CcSwitchImporter
import dev.pi.gui.providers.ImportedProvider
import dev.pi.gui.providers.ProviderKind
import dev.pi.gui.providers.ProvidersRegistry
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.components.DotIcon
import dev.pi.gui.ui.components.PiButton
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The "Model Providers" page: providers imported from cc-switch, grouped into a Claude Code
 * (Anthropic) and a Codex (OpenAI) section. The main import button auto-detects both cc-switch
 * storage generations — the v3 `cc-switch.db` and the v2 `config.json` — so one click works
 * on any install; a second button lets the user pick a db stored somewhere else.
 *
 * "Enable" makes a provider the chat's active one: the choice is persisted in [PiSettings],
 * open chat panels apply it through the settings-change listener, and the next session starts
 * with it already selected.
 */
class ProvidersSettingsPanel(
    private val project: Project?,
    private val registry: ProvidersRegistry = ProvidersRegistry.default(),
    /** Overridden by tests. */
    private val autoImporter: () -> CcSwitchImporter.Outcome =
        { CcSwitchImporter.importAuto() },
    /** Overridden by tests. */
    private val dbImporter: (File) -> CcSwitchImporter.Outcome =
        { CcSwitchImporter.importFromDb(it) },
) : JPanel(BorderLayout()) {

    private val statusLabel = JBLabel(" ").apply { foreground = PiTheme.mutedFg() }
    private val rowsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(460))
        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(rowsPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        reload()
    }

    // ------------------------------------------------------------- toolbar

    private fun buildToolbar(): JPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
        border = JBUI.Borders.empty(8, 10, 4, 10)
        isOpaque = false

        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        val autoButton = PiButton(
            PiBundle.message("providers.import.auto"), AllIcons.Actions.Download, PiButton.Style.SECONDARY,
        ).apply { addActionListener { importAuto() } }
        val dbButton = PiButton(
            PiBundle.message("providers.import.db"), AllIcons.Nodes.Folder, PiButton.Style.SECONDARY,
        ).apply { addActionListener { importDb() } }
        buttons.add(autoButton)
        buttons.add(Box.createHorizontalStrut(JBUI.scale(6)))
        buttons.add(dbButton)

        add(buttons, BorderLayout.NORTH)
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun importAuto() {
        statusLabel.text = PiBundle.message("providers.importing")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching(autoImporter).getOrElse {
                CcSwitchImporter.Outcome(emptyList(), emptyList())
            }
            // any(): this panel sits inside the modal settings dialog; see SkillsSettingsPanel.
            ApplicationManager.getApplication().invokeLater({ applyImport(outcome) }, ModalityState.any())
        }
    }

    private fun importDb() {
        val fileChooser = JFileChooser().apply {
            dialogTitle = PiBundle.message("providers.import.db.title")
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter(PiBundle.message("providers.import.db.desc"), "db")
        }
        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val dbFile = fileChooser.selectedFile ?: return
        statusLabel.text = PiBundle.message("providers.importing")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { dbImporter(dbFile) }.getOrElse {
                CcSwitchImporter.Outcome(emptyList(), emptyList())
            }
            ApplicationManager.getApplication().invokeLater(
                { applyImport(outcome) }, ModalityState.any(),
            )
        }
    }

    private fun applyImport(outcome: CcSwitchImporter.Outcome) {
        if (outcome.imported.isEmpty() && outcome.skipped.isEmpty()) {
            statusLabel.text = PiBundle.message("providers.import.empty")
            return
        }
        val summary = registry.import(outcome)
        statusLabel.text = PiBundle.message(
            "providers.import.done", summary.added, summary.updated, summary.skipped.size,
        ) + (outcome.source?.let { PiBundle.message("providers.import.source", it) } ?: "") +
            summary.skipped.joinToString("") {
                "  " + PiBundle.message(
                    "providers.skipped.list", it.name, PiBundle.message(skipKey(it.reason)),
                )
            }
        reload()
    }

    private fun skipKey(reason: CcSwitchImporter.Skipped.Reason): String = when (reason) {
        CcSwitchImporter.Skipped.Reason.NO_CREDENTIALS -> "providers.skipped.noCredentials"
        CcSwitchImporter.Skipped.Reason.NO_BASE_URL -> "providers.skipped.noBaseUrl"
        CcSwitchImporter.Skipped.Reason.NO_MODELS -> "providers.skipped.noModels"
        CcSwitchImporter.Skipped.Reason.BAD_DATA -> "providers.skipped.badData"
        CcSwitchImporter.Skipped.Reason.NOT_FOUND -> "providers.skipped.notFound"
    }

    // ----------------------------------------------------------------- rows

    private fun reload() {
        val providers = registry.list()
        rowsPanel.removeAll()

        ProviderKind.entries.forEach { kind ->
            val sectionProviders = providers.filter { it.kind == kind }
            rowsPanel.add(sectionHeader(sectionTitle(kind), sectionProviders.isNotEmpty()))
            sectionProviders.forEach { p -> rowsPanel.add(ProviderRow(p)) }
        }

        if (providers.isEmpty()) {
            rowsPanel.add(
                JBLabel(PiBundle.message("providers.empty.hint")).apply {
                    foreground = PiTheme.mutedFg()
                    border = JBUI.Borders.empty(14, 10)
                }
            )
        }
        rowsPanel.add(Box.createVerticalGlue())
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    private fun sectionTitle(kind: ProviderKind): String = when (kind) {
        ProviderKind.CLAUDE_CODE -> PiBundle.message("providers.claudeSection")
        ProviderKind.CODEX -> PiBundle.message("providers.codexSection")
    }

    private fun sectionHeader(title: String, hasEntries: Boolean): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(14, 4, 6, 4)
        isOpaque = false
        add(
            JBLabel(title).apply {
                font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 1f)
                foreground = if (hasEntries) PiTheme.textFg() else PiTheme.mutedFg()
            },
            BorderLayout.WEST,
        )
    }

    /** One imported provider: current marker + name + base URL, with enable/edit/delete. */
    private inner class ProviderRow(val provider: ImportedProvider) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(PiTheme.messageDivider),
                JBUI.Borders.empty(7, 10, 7, 6),
            )

            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
            val active = PiSettings.getInstance().activeProvider == provider.id
            left.add(JBLabel().apply { icon = DotIcon(if (active) PiTheme.accent else PiTheme.buttonBorder) })
            left.add(JBLabel(provider.name).apply { toolTipText = provider.baseUrl })
            left.add(
                JBLabel(provider.baseUrl).apply {
                    font = PiTheme.monoFont().deriveFont(PiTheme.monoFont().size2D - 1f)
                    foreground = PiTheme.mutedFg()
                }
            )
            if (active) {
                left.add(
                    JBLabel(PiBundle.message("providers.current")).apply {
                        border = JBUI.Borders.empty(1, 5)
                        foreground = PiTheme.accent
                        font = font.deriveFont(font.size2D - 1f)
                    }
                )
            }
            add(left, BorderLayout.CENTER)

            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
            actions.add(actionButton(PiBundle.message("providers.enable"), PiButton.Style.SECONDARY) { enable(provider) })
            actions.add(actionButton(PiBundle.message("providers.edit"), PiButton.Style.GHOST) { edit(provider) })
            actions.add(actionButton(PiBundle.message("providers.delete"), PiButton.Style.GHOST) { delete(provider) })
            add(actions, BorderLayout.EAST)
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun actionButton(text: String, style: PiButton.Style, action: () -> Unit): PiButton =
        PiButton(text, null, style).apply { addActionListener { action() } }

    private fun enable(provider: ImportedProvider) {
        val settings = PiSettings.getInstance()
        settings.activeProvider = provider.id
        settings.activeModel = provider.defaultModel ?: provider.models.firstOrNull().orEmpty()
        settings.fireChanged() // open chat panels switch right away
        reload()
    }

    private fun edit(provider: ImportedProvider) {
        val dialog = EditProviderDialog(this, provider)
        if (!dialog.showAndGet()) return
        val edited = dialog.providerOrNull() ?: return
        registry.update(edited)

        // Keep the saved selection coherent when the active provider's model list changed.
        val settings = PiSettings.getInstance()
        if (settings.activeProvider == edited.id && edited.models.isNotEmpty()) {
            settings.activeModel = edited.models.firstOrNull { it == settings.activeModel }
                ?: edited.defaultModel ?: edited.models.first()
            settings.fireChanged()
        }
        reload()
    }

    private fun delete(provider: ImportedProvider) {
        val confirmed = Messages.showYesNoDialog(
            this,
            PiBundle.message("providers.delete.confirm", provider.name),
            PiBundle.message("settings.tab.providers"),
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        registry.delete(provider.id)
        val settings = PiSettings.getInstance()
        if (settings.activeProvider == provider.id) {
            settings.activeProvider = ""
            settings.activeModel = ""
            settings.fireChanged()
        }
        reload()
    }

    // -------------------------------------------------------------- test API

    internal fun providerNamesForTest(kind: ProviderKind): List<String> =
        registry.list().filter { it.kind == kind }.map { it.name }

    @org.jetbrains.annotations.TestOnly
    internal fun importForTest(outcome: CcSwitchImporter.Outcome) = applyImport(outcome)

    @org.jetbrains.annotations.TestOnly
    internal fun enableForTest(name: String) {
        registry.list().firstOrNull { it.name == name }?.let { enable(it) }
    }
}
