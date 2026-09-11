package dev.pi.gui.ui.components

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.border.Border

/**
 * Rounded outline whose color is resolved at paint time, so a focus change only needs a repaint.
 */
class RoundedBorder(
    private val colorProvider: () -> java.awt.Color,
    private val arc: Int = 10,
    private val padding: Insets = JBUI.insets(6),
    private val thickness: Int = 1,
) : Border {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = colorProvider()
            g2.stroke = java.awt.BasicStroke(thickness.toFloat())
            val a = JBUI.scale(arc)
            g2.drawRoundRect(x, y, width - 1, height - 1, a, a)
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(c: Component): Insets = Insets(
        padding.top + thickness,
        padding.left + thickness,
        padding.bottom + thickness,
        padding.right + thickness,
    )

    override fun isBorderOpaque(): Boolean = false
}
