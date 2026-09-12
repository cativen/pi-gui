package dev.pi.gui.ui.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.pi.gui.ui.PiTheme
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shared building blocks for the settings pages, mirroring the reference webview settings:
 * sections live on rounded "cards" (standard dialog background, hairline border, 8px radius)
 * with a bold title, stacked with generous gaps.
 *
 * The card deliberately paints the IDE's standard panel background (the same color the
 * Skills/Plugins/Providers pages and the dialog itself use) instead of a chat-theme surface,
 * so every settings page shares one background — pinning the chat theme to Dark must not
 * paint the General/CLI pages black.
 */
object SettingsComponents {

    /** A rounded card with a bold section title; children are stacked vertically inside. */
    fun card(title: String): JPanel {
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(8)
                    g2.color = UIUtil.getPanelBackground()
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    g2.color = JBColor.border()
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(12, 14)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        panel.add(
            JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                foreground = UIUtil.getLabelForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        panel.add(Box.createVerticalStrut(JBUI.scale(8)))
        return panel
    }

    /** Wraps [child] so a BoxLayout parent stretches it horizontally but keeps its height. */
    fun row(child: Component, height: Int = 28): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(height))
            add(child, BorderLayout.WEST)
        }

    fun gap(height: Int): Component = Box.createVerticalStrut(JBUI.scale(height))

    /** Small grey hint under a field, like the reference `.form-hint`. */
    fun hint(text: String): JBLabel = JBLabel(text).apply {
        foreground = PiTheme.mutedFg()
        font = font.deriveFont(font.size2D - 1f)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Bold-ish 13px label above a field, like the reference `.form-group label`. */
    fun fieldLabel(text: String): JPanel = row(
        JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            foreground = PiTheme.textFg()
        },
        height = 22,
    )
}
