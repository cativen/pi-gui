package dev.pi.gui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.ui.settings.CliSettingsPanel
import dev.pi.gui.ui.settings.GeneralSettingsPanel
import dev.pi.gui.ui.settings.WebSettingsSurface
import dev.pi.gui.web.PiWebView
import javax.swing.JComponent

/**
 * IDE Settings → Tools → Pi GUI.
 *
 * Reuses the same JCEF surface as the toolbar dialog; the compact native General/CLI view is kept
 * only for IDE builds where JCEF is unavailable.
 */
class PiSettingsConfigurable : Configurable {

    private var general: GeneralSettingsPanel? = null
    private var cli: CliSettingsPanel? = null
    private var web: WebSettingsSurface? = null

    override fun getDisplayName(): String = "Pi GUI"

    override fun createComponent(): JComponent {
        if (PiWebView.isAvailable()) {
            return WebSettingsSurface(null).also { web = it }.component
        }
        val generalPanel = GeneralSettingsPanel().also { general = it }
        val cliPanel = CliSettingsPanel().also { cli = it }
        return JBTabbedPane().apply {
            addTab(PiBundle.message("settings.tab.general"), generalPanel)
            addTab(PiBundle.message("settings.tab.cli"), cliPanel)
        }
    }

    override fun isModified(): Boolean =
        web?.isModified() == true || general?.isModified() == true || cli?.isModified() == true

    override fun apply() {
        web?.let {
            it.apply()
            return
        }
        general?.apply()
        cli?.apply()
        PiSettings.getInstance().fireChanged()
    }

    override fun reset() {
        web?.let {
            it.reset()
            return
        }
        general?.reset()
        cli?.reset()
    }

    override fun disposeUIResources() {
        web?.dispose()
        web = null
        general = null
        cli = null
    }
}
