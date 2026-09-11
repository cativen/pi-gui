package dev.pi.gui.ui.components

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * A small filled circle used as a status bullet.
 *
 * Drawn rather than taken from `AllIcons` so the exact colour is under our control and the code
 * cannot break when the platform reshuffles its icon constants.
 */
class DotIcon(
    private val color: Color,
    private val diameter: Int = 8,
) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(diameter)
    override fun getIconHeight(): Int = JBUI.scale(diameter)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
        } finally {
            g2.dispose()
        }
    }
}
