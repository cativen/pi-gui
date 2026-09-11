package dev.pi.gui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.ui.settings.CliSettingsPanel
import dev.pi.gui.ui.settings.GeneralSettingsPanel
import javax.swing.JComponent

/**
 * IDE Settings → Tools → Pi GUI.
 *
 * Reuses the very same panels as the in-panel settings dialog, so the two entry points always
 * show and write identical state.
 */
class PiSettingsConfigurable : Configurable {

    private var general: GeneralSettingsPanel? = null
    private var cli: CliSettingsPanel? = null

    override fun getDisplayName(): String = "Pi GUI"

    override fun createComponent(): JComponent {
        val generalPanel = GeneralSettingsPanel().also { general = it }
        val cliPanel = CliSettingsPanel().also { cli = it }
        return JBTabbedPane().apply {
            addTab(PiBundle.message("settings.tab.general"), generalPanel)
            addTab(PiBundle.message("settings.tab.cli"), cliPanel)
        }
    }

    override fun isModified(): Boolean =
        general?.isModified() == true || cli?.isModified() == true

    override fun apply() {
        general?.apply()
        cli?.apply()
        PiSettings.getInstance().fireChanged()
    }

    override fun reset() {
        general?.reset()
        cli?.reset()
    }

    override fun disposeUIResources() {
        general = null
        cli = null
    }
}
