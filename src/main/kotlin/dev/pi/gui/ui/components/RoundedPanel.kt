package dev.pi.gui.ui.components

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.Path2D

/** A [StackPanel] that paints a rounded, optionally outlined background behind its children. */
class RoundedPanel(
    private val background: Color,
    private val outline: Color? = null,
    private val arc: Int = 8,
    gap: Int = 0,
    padding: Insets = JBUI.insets(8, 10),
    /**
     * Per-corner radii as (topLeft, topRight, bottomRight, bottomLeft). When set it wins over
     * [arc] — chat bubbles use this for the classic "one square corner" shape.
     */
    private val corners: IntArray? = null,
) : StackPanel(gap) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(padding)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val radii = corners?.map { JBUI.scale(it) }?.toIntArray()
            g2.color = background
            fillRounded(g2, 0, 0, width - 1, height - 1, radii)
            outline?.let {
                g2.color = it
                drawRounded(g2, 0, 0, width - 1, height - 1, radii)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun fillRounded(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, radii: IntArray?) {
        if (radii == null) {
            val a = JBUI.scale(arc)
            g2.fillRoundRect(x, y, w, h, a, a)
        } else {
            g2.fill(roundedPath(x, y, w, h, radii))
        }
    }

    private fun drawRounded(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, radii: IntArray?) {
        if (radii == null) {
            val a = JBUI.scale(arc)
            g2.drawRoundRect(x, y, w, h, a, a)
        } else {
            g2.draw(roundedPath(x, y, w, h, radii))
        }
    }

    private fun roundedPath(x: Int, y: Int, w: Int, h: Int, r: IntArray): Path2D.Double {
        val tl = r[0].coerceAtMost(minOf(w, h) / 2)
        val tr = r[1].coerceAtMost(minOf(w, h) / 2)
        val br = r[2].coerceAtMost(minOf(w, h) / 2)
        val bl = r[3].coerceAtMost(minOf(w, h) / 2)
        val path = Path2D.Double()
        path.moveTo(x + tl.toDouble(), y.toDouble())
        path.lineTo(x + w - tr.toDouble(), y.toDouble())
        if (tr > 0) path.quadTo(x + w.toDouble(), y.toDouble(), x + w.toDouble(), y + tr.toDouble())
        path.lineTo(x + w.toDouble(), y + h - br.toDouble())
        if (br > 0) path.quadTo(x + w.toDouble(), y + h.toDouble(), x + w - br.toDouble(), y + h.toDouble())
        path.lineTo(x + bl.toDouble(), y + h.toDouble())
        if (bl > 0) path.quadTo(x.toDouble(), y + h.toDouble(), x.toDouble(), y + h - bl.toDouble())
        path.lineTo(x.toDouble(), y + tl.toDouble())
        if (tl > 0) path.quadTo(x.toDouble(), y.toDouble(), x + tl.toDouble(), y.toDouble())
        path.closePath()
        return path
    }
}
