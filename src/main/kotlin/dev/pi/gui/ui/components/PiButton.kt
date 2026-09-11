package dev.pi.gui.ui.components

import com.intellij.util.ui.JBUI
import dev.pi.gui.ui.PiTheme
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton

/**
 * A flat, rounded button with hover and pressed states.
 *
 * Swing's stock button chrome looks dated next to the rest of the chat surface, so the background
 * is painted here and only the text/icon come from the standard UI delegate.
 */
class PiButton(
    text: String? = null,
    icon: Icon? = null,
    private val style: Style = Style.SECONDARY,
) : JButton() {

    enum class Style { PRIMARY, SECONDARY, GHOST }

    private var hovered = false

    init {
        this.text = text
        this.icon = icon
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 10)
        font = PiTheme.uiFont()
        iconTextGap = JBUI.scale(4)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (isEnabled) { hovered = true; repaint() }
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }
        })
        refreshForeground()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) hovered = false
        cursor = Cursor.getPredefinedCursor(
            if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
        )
        refreshForeground()
        repaint()
    }

    private fun refreshForeground() {
        foreground = when {
            !isEnabled -> PiTheme.mutedFg()
            style == Style.PRIMARY -> PiTheme.onAccent
            else -> PiTheme.textFg()
        }
    }

    /** Null means "paint nothing", which is what a resting ghost button wants. */
    private fun fillColor(): Color? {
        if (!isEnabled) {
            return if (style == Style.GHOST) null else PiTheme.buttonBg
        }
        val pressed = model.isPressed && model.isArmed
        return when (style) {
            Style.PRIMARY -> when {
                pressed -> PiTheme.accentPressed
                hovered -> PiTheme.accentHover
                else -> PiTheme.accent
            }
            Style.SECONDARY -> when {
                pressed -> PiTheme.buttonPressed
                hovered -> PiTheme.buttonHover
                else -> PiTheme.buttonBg
            }
            Style.GHOST -> when {
                pressed -> PiTheme.buttonPressed
                hovered -> PiTheme.buttonHover
                else -> null
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            fillColor()?.let {
                g2.color = it
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            }
            if (style == Style.SECONDARY) {
                g2.color = PiTheme.buttonBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, maxOf(base.height, JBUI.scale(24)))
    }

    /**
     * Pin the maximum to the preferred size. In a `BoxLayout` an unbounded maximum lets the button
     * soak up all the leftover row space, which stretched it to several times its natural size.
     */
    override fun getMaximumSize(): Dimension = preferredSize

    override fun getMinimumSize(): Dimension = preferredSize

    override fun updateUI() {
        super.updateUI()
        // The L&F resets these when the theme changes.
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        border = JBUI.Borders.empty(3, 10)
        font = PiTheme.uiFont()
        refreshForeground()
    }
}
