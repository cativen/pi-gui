package dev.pi.gui.ui.components

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/** A [StackPanel] that paints a rounded, optionally outlined background behind its children. */
class RoundedPanel(
    private val background: Color,
    private val outline: Color? = null,
    private val arc: Int = 10,
    gap: Int = 0,
) : StackPanel(gap) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val a = JBUI.scale(arc)
            g2.color = background
            g2.fillRoundRect(0, 0, width - 1, height - 1, a, a)
            outline?.let {
                g2.color = it
                g2.drawRoundRect(0, 0, width - 1, height - 1, a, a)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
