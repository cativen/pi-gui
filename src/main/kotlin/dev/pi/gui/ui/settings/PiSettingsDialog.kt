package dev.pi.gui.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.web.PiWebView
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Settings dialog opened from the tool window toolbar. "General" is the first tab.
 *
 * The same JCEF surface backs [dev.pi.gui.settings.PiSettingsConfigurable], so the IDE settings
 * page and this dialog cannot drift apart. Swing panels remain available only as a JCEF fallback.
 */
class PiSettingsDialog(project: Project?) : DialogWrapper(project, true) {

    private val web = if (PiWebView.isAvailable()) WebSettingsSurface(project) else null
    private val general by lazy { GeneralSettingsPanel() }
    private val providers by lazy { ProvidersSettingsPanel(project) }
    private val skills by lazy { SkillsSettingsPanel(project) }
    private val plugins by lazy { PluginsSettingsPanel(project) }
    private val cli by lazy { CliSettingsPanel() }

    init {
        title = PiBundle.message("settings.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        web?.let { surface ->
            return surface.component.apply {
                preferredSize = Dimension(JBUI.scale(980), JBUI.scale(650))
                minimumSize = Dimension(JBUI.scale(660), JBUI.scale(460))
            }
        }

        // JCEF can be disabled or absent in a few IDE distributions. Retain the native panels as
        // a functional fallback instead of making Settings impossible to open.
        // Tabs on the left read like the sidebar navigation of the reference settings page.
        val tabs = JBTabbedPane(javax.swing.JTabbedPane.LEFT)
        tabs.addTab(PiBundle.message("settings.tab.general"), general)
        tabs.addTab(PiBundle.message("settings.tab.cli"), cli)
        tabs.addTab(PiBundle.message("settings.tab.providers"), providers)
        tabs.addTab(PiBundle.message("settings.tab.skills"), skills)
        tabs.addTab(PiBundle.message("settings.tab.plugins"), plugins)
        tabs.border = JBUI.Borders.empty()
        tabs.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(520))
        return tabs
    }

    override fun doOKAction() {
        if (web != null) {
            web.apply()
        } else {
            general.apply()
            cli.apply()
            dev.pi.gui.settings.PiSettings.getInstance().fireChanged()
        }
        super.doOKAction()
    }

    override fun dispose() {
        web?.dispose()
        super.dispose()
    }
}
