package dev.pi.gui.ui.components

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/** A component whose height depends on the width it is given (wrapped text, mainly). */
interface WidthAware {
    fun heightForWidth(width: Int): Int
}

/**
 * Stacks children vertically, each stretched to the container's full inner width.
 *
 * Swing's `BoxLayout` cannot ask a child "how tall are you at width W", which is exactly what
 * wrapped HTML needs, so the transcript uses this instead.
 */
class VerticalStackLayout(private val gap: Int = 0) : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}

    override fun preferredLayoutSize(parent: Container): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val width = resolveWidth(parent)
            val inner = (width - insets.left - insets.right).coerceAtLeast(1)
            var height = insets.top + insets.bottom
            var visible = 0
            parent.components.forEach { c ->
                if (!c.isVisible) return@forEach
                if (visible > 0) height += gap
                height += heightOf(c, inner)
                visible++
            }
            return Dimension(width, height)
        }
    }

    override fun minimumLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        return Dimension(insets.left + insets.right + 1, preferredLayoutSize(parent).height)
    }

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val inner = (parent.width - insets.left - insets.right).coerceAtLeast(1)
            var y = insets.top
            var visible = 0
            parent.components.forEach { c ->
                if (!c.isVisible) return@forEach
                if (visible > 0) y += gap
                val h = heightOf(c, inner)
                c.setBounds(insets.left, y, inner, h)
                y += h
                visible++
            }
        }
    }

    /** Height this layout needs for [parent]'s children when laid out at [width]. */
    fun heightFor(parent: Container, width: Int): Int {
        val insets = parent.insets
        val inner = (width - insets.left - insets.right).coerceAtLeast(1)
        var height = insets.top + insets.bottom
        var visible = 0
        parent.components.forEach { c ->
            if (!c.isVisible) return@forEach
            if (visible > 0) height += gap
            height += heightOf(c, inner)
            visible++
        }
        return height
    }

    private fun heightOf(c: Component, width: Int): Int =
        if (c is WidthAware) c.heightForWidth(width) else c.preferredSize.height

    /** Prefer the realized width; fall back to the parent's own preference before first layout. */
    private fun resolveWidth(parent: Container): Int {
        if (parent.width > 0) return parent.width
        parent.parent?.let { if (it.width > 0) return it.width }
        return 600
    }
}
