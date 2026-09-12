package dev.pi.gui.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.settings.PiSettings
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Settings dialog opened from the tool window toolbar. "General" is the first tab.
 *
 * The same panels back [dev.pi.gui.settings.PiSettingsConfigurable], so the IDE settings page and
 * this dialog can never drift apart.
 */
class PiSettingsDialog(project: Project?) : DialogWrapper(project, true) {

    private val general = GeneralSettingsPanel()
    private val providers = ProvidersSettingsPanel(project)
    private val skills = SkillsSettingsPanel(project)
    private val plugins = PluginsSettingsPanel(project)
    private val cli = CliSettingsPanel()

    init {
        title = PiBundle.message("settings.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        // Tabs on the left read like the sidebar navigation of the reference settings page.
        val tabs = JBTabbedPane(javax.swing.JTabbedPane.LEFT)
        tabs.addTab(PiBundle.message("settings.tab.general"), general)
        tabs.addTab(PiBundle.message("settings.tab.providers"), providers)
        tabs.addTab(PiBundle.message("settings.tab.skills"), skills)
        tabs.addTab(PiBundle.message("settings.tab.plugins"), plugins)
        tabs.addTab(PiBundle.message("settings.tab.cli"), cli)
        tabs.border = JBUI.Borders.empty()
        tabs.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(520))
        return tabs
    }

    override fun doOKAction() {
        general.apply()
        cli.apply()
        // Wakes up open chat panels so the new theme, font and language take effect immediately.
        PiSettings.getInstance().fireChanged()
        super.doOKAction()
    }
}
