package dev.pi.gui.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.ThemeMode
import dev.pi.gui.settings.UiLanguage
import dev.pi.gui.ui.PiTheme
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider

/** The "General" page: appearance, conversation behaviour and UI language. */
class GeneralSettingsPanel : JPanel() {

    private val systemTheme = JBRadioButton(PiBundle.message("settings.appearance.system"))
    private val lightTheme = JBRadioButton(PiBundle.message("settings.appearance.light"))
    private val darkTheme = JBRadioButton(PiBundle.message("settings.appearance.dark"))

    private val expandThinking = JBCheckBox(PiBundle.message("settings.expandThinking"))
    private val expandToolCalls = JBCheckBox(PiBundle.message("settings.expandToolCalls"))
    private val showThinking = JBCheckBox(PiBundle.message("settings.showThinking"))
    private val sendOnEnter = JBCheckBox(PiBundle.message("settings.sendOnEnter"))

    private val fontSlider = JSlider(
        PiSettings.MIN_FONT_SIZE,
        PiSettings.MAX_FONT_SIZE,
        PiTheme.defaultFontSize(),
    )
    private val fontValue = JBLabel()

    private val english = JBRadioButton(PiBundle.message("settings.language.en"))
    private val simplified = JBRadioButton(PiBundle.message("settings.language.zhCN"))
    private val traditional = JBRadioButton(PiBundle.message("settings.language.zhTW"))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12, 16)

        ButtonGroup().apply {
            add(systemTheme); add(lightTheme); add(darkTheme)
        }
        ButtonGroup().apply {
            add(english); add(simplified); add(traditional)
        }

        add(sectionTitle(PiBundle.message("settings.appearance")))
        add(row(systemTheme, lightTheme, darkTheme))
        add(gap(14))

        add(sectionTitle(PiBundle.message("settings.conversation")))
        add(leftAligned(showThinking))
        add(leftAligned(expandThinking))
        add(leftAligned(expandToolCalls))
        add(leftAligned(sendOnEnter))
        add(gap(8))
        add(fontSizeRow())
        add(gap(14))

        add(sectionTitle(PiBundle.message("settings.language")))
        add(leftAligned(english, "en"))
        add(leftAligned(simplified, "zh-CN"))
        add(leftAligned(traditional, "zh-TW"))

        add(Box.createVerticalGlue())

        fontSlider.addChangeListener { updateFontValueLabel() }
        reset()
    }

    private fun sectionTitle(text: String): JComponentRow = JComponentRow(
        JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.emptyBottom(6)
        }
    )

    private fun leftAligned(component: Component, trailing: String? = null): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
            add(component, BorderLayout.WEST)
            if (trailing != null) {
                add(
                    JBLabel(trailing).apply {
                        foreground = PiTheme.mutedFg()
                        font = font.deriveFont(font.size2D - 1f)
                    },
                    BorderLayout.EAST,
                )
            }
        }

    private fun row(vararg components: Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(16), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            components.forEach { add(it) }
        }

    private fun fontSizeRow(): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
        }

        panel.add(JBLabel(PiBundle.message("settings.fontSize")), BorderLayout.WEST)

        fontSlider.apply {
            paintTicks = false
            paintLabels = false
            preferredSize = Dimension(JBUI.scale(220), JBUI.scale(24))
        }
        panel.add(fontSlider, BorderLayout.CENTER)

        val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        fontValue.foreground = PiTheme.mutedFg()
        trailing.add(fontValue)
        trailing.add(
            JLabel(AllIcons.General.Reset).apply {
                toolTipText = PiBundle.message("settings.reset")
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        fontSlider.value = PiTheme.defaultFontSize()
                    }
                })
            }
        )
        panel.add(trailing, BorderLayout.EAST)

        updateFontValueLabel()
        return panel
    }

    private fun updateFontValueLabel() {
        fontValue.text = "${fontSlider.value}px"
    }

    private fun gap(height: Int) = Box.createVerticalStrut(JBUI.scale(height))

    // ------------------------------------------------------------- state

    fun reset() {
        val settings = PiSettings.getInstance()
        when (settings.themeMode) {
            ThemeMode.SYSTEM -> systemTheme.isSelected = true
            ThemeMode.LIGHT -> lightTheme.isSelected = true
            ThemeMode.DARK -> darkTheme.isSelected = true
        }
        showThinking.isSelected = settings.showThinking
        expandThinking.isSelected = settings.expandThinking
        expandToolCalls.isSelected = settings.autoExpandToolCalls
        sendOnEnter.isSelected = settings.sendOnEnter
        fontSlider.value = if (settings.chatFontSize > 0) settings.chatFontSize else PiTheme.defaultFontSize()
        updateFontValueLabel()
        when (settings.language) {
            UiLanguage.ENGLISH -> english.isSelected = true
            UiLanguage.SIMPLIFIED_CHINESE -> simplified.isSelected = true
            UiLanguage.TRADITIONAL_CHINESE -> traditional.isSelected = true
        }
    }

    fun apply() {
        val settings = PiSettings.getInstance()
        settings.themeMode = when {
            lightTheme.isSelected -> ThemeMode.LIGHT
            darkTheme.isSelected -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        settings.showThinking = showThinking.isSelected
        settings.expandThinking = expandThinking.isSelected
        settings.autoExpandToolCalls = expandToolCalls.isSelected
        settings.sendOnEnter = sendOnEnter.isSelected
        settings.chatFontSize = fontSlider.value
        settings.language = when {
            english.isSelected -> UiLanguage.ENGLISH
            traditional.isSelected -> UiLanguage.TRADITIONAL_CHINESE
            else -> UiLanguage.SIMPLIFIED_CHINESE
        }
    }

    fun isModified(): Boolean {
        val settings = PiSettings.getInstance()
        val theme = when {
            lightTheme.isSelected -> ThemeMode.LIGHT
            darkTheme.isSelected -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        val language = when {
            english.isSelected -> UiLanguage.ENGLISH
            traditional.isSelected -> UiLanguage.TRADITIONAL_CHINESE
            else -> UiLanguage.SIMPLIFIED_CHINESE
        }
        return theme != settings.themeMode ||
            language != settings.language ||
            showThinking.isSelected != settings.showThinking ||
            expandThinking.isSelected != settings.expandThinking ||
            expandToolCalls.isSelected != settings.autoExpandToolCalls ||
            sendOnEnter.isSelected != settings.sendOnEnter ||
            fontSlider.value != (if (settings.chatFontSize > 0) settings.chatFontSize else PiTheme.defaultFontSize())
    }

    /** BoxLayout needs every child left-aligned; wrapping keeps that in one place. */
    private class JComponentRow(child: Component) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            add(child, BorderLayout.WEST)
        }
    }
}
