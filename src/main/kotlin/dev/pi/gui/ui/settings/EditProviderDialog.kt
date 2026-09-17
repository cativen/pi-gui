package dev.pi.gui.ui.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.providers.ImportedProvider
import dev.pi.gui.ui.PiTheme
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

/**
 * Edits an imported provider: display name, endpoint, key and the model list (one id per
 * line). Identity fields — the pi provider id, the section it appears in, and its cc-switch
 * source id — stay fixed so edits cannot orphan the registry sidecar.
 */
class EditProviderDialog(
    parent: JComponent,
    private val original: ImportedProvider,
) : DialogWrapper(parent, true) {

    private val nameField = JBTextField(original.name)
    private val baseUrlField = JBTextField(original.baseUrl)
    // A stored credential is never placed back into an editable UI control.
    private val apiKeyField = JPasswordField()
    private val modelsArea = JBTextArea().apply {
        text = original.models.joinToString("\n")
        rows = 4
    }

    private var edited: ImportedProvider? = null

    init {
        title = PiBundle.message("providers.dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 2)
        }

        fun addField(labelKey: String, field: JComponent, mono: Boolean = false) {
            root.add(
                JBLabel(PiBundle.message(labelKey)).apply {
                    font = font.deriveFont(java.awt.Font.BOLD, font.size2D - 1f)
                    foreground = PiTheme.textFg()
                    border = JBUI.Borders.empty(10, 0, 3, 0)
                }
            )
            if (mono) field.font = PiTheme.monoFont()
            field.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            field.alignmentX = JPanel.LEFT_ALIGNMENT
            root.add(field)
        }

        addField("providers.name", nameField)
        addField("providers.baseUrl", baseUrlField, mono = true)
        addField("providers.apiKey", apiKeyField, mono = true)
        root.add(
            JBLabel(PiBundle.message("providers.apiKey.preserve")).apply {
                foreground = PiTheme.mutedFg()
                border = JBUI.Borders.empty(3, 0, 0, 0)
            }
        )
        root.add(
            JBLabel(PiBundle.message("providers.models")).apply {
                font = font.deriveFont(java.awt.Font.BOLD, font.size2D - 1f)
                foreground = PiTheme.textFg()
                border = JBUI.Borders.empty(10, 0, 3, 0)
            }
        )
        modelsArea.font = PiTheme.monoFont()
        modelsArea.alignmentX = JPanel.LEFT_ALIGNMENT
        root.add(modelsArea)

        root.preferredSize = Dimension(JBUI.scale(480), JBUI.scale(300))
        return JPanel(BorderLayout()).apply { add(root, BorderLayout.CENTER) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doOKAction() {
        val models = modelsArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val valid = nameField.text.isNotBlank() && baseUrlField.text.isNotBlank() && models.isNotEmpty()
        if (!valid) {
            // Keep the dialog open and tell the user what is missing.
            setErrorText(PiBundle.message("providers.invalid"))
            return
        }
        edited = original.copy(
            name = nameField.text.trim(),
            baseUrl = baseUrlField.text.trim(),
            apiKey = String(apiKeyField.password).trim().ifBlank { original.apiKey },
            models = models,
            defaultModel = original.defaultModel?.takeIf { it in models } ?: models.first(),
        )
        super.doOKAction()
    }

    /** The edited provider, or null when the dialog was cancelled or validation failed. */
    fun providerOrNull(): ImportedProvider? = edited
}
