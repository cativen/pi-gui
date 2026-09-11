package dev.pi.gui.ui.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.pi.gui.PiLocator
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.PiTheme
import java.awt.BorderLayout
import javax.swing.JPanel

/** The "pi CLI" page: executable location and extra launch arguments. */
class CliSettingsPanel : JPanel(BorderLayout()) {

    private val piPathField = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().also {
            it.title = PiBundle.message("settings.cli.path")
        }
        addBrowseFolderListener(null, descriptor)
    }
    private val extraArgsField = JBTextField()

    init {
        border = JBUI.Borders.empty(12, 16)

        val detected = PiLocator.discover()
        val detectedLabel = JBLabel(
            if (detected != null) PiBundle.message("settings.cli.detected", detected.absolutePath)
            else PiBundle.message("settings.cli.notFound")
        ).apply {
            foreground = if (detected != null) PiTheme.mutedFg() else PiTheme.errorFg
        }

        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel(PiBundle.message("settings.cli.path")), piPathField, 1, false)
                .addComponentToRightColumn(detectedLabel, 0)
                .addLabeledComponent(JBLabel(PiBundle.message("settings.cli.extraArgs")), extraArgsField, 1, false)
                .addComponentToRightColumn(
                    JBLabel(PiBundle.message("settings.cli.extraArgs.hint")).apply {
                        foreground = PiTheme.mutedFg()
                    },
                    0,
                )
                .addComponentFillVertically(JPanel(), 0)
                .panel,
            BorderLayout.CENTER,
        )
        reset()
    }

    fun reset() {
        val settings = PiSettings.getInstance()
        piPathField.text = settings.piPath
        extraArgsField.text = settings.extraArgs
    }

    fun apply() {
        val settings = PiSettings.getInstance()
        settings.piPath = piPathField.text.trim()
        settings.extraArgs = extraArgsField.text.trim()
    }

    fun isModified(): Boolean {
        val settings = PiSettings.getInstance()
        return piPathField.text != settings.piPath || extraArgsField.text != settings.extraArgs
    }
}
