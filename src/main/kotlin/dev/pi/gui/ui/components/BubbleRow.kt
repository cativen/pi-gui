package dev.pi.gui.ui.components

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Right-aligns a single child (the user message bubble) and lets it hug its content: the bubble
 * takes its natural unwrapped width, capped at [maxRatio] of the available width, and wraps
 * below that cap — the same shape chat bubbles have in the reference webview UI.
 */
class BubbleRow(
    private val bubble: JComponent,
    private val maxRatio: Float = 0.85f,
) : JPanel(), WidthAware {

    init {
        isOpaque = false
        layout = BubbleLayout()
        add(bubble)
    }

    override fun heightForWidth(width: Int): Int {
        val insets = insets
        val inner = (width - insets.left - insets.right).coerceAtLeast(1)
        val w = bubbleWidth(inner)
        return insets.top + insets.bottom + heightOf(bubble, w)
    }

    private fun bubbleWidth(inner: Int): Int {
        val cap = (inner * maxRatio).toInt().coerceAtLeast(40)
        return minOf(cap, naturalWidthOf(bubble)).coerceAtLeast(40)
    }

    private fun heightOf(c: Component, width: Int): Int =
        if (c is WidthAware) c.heightForWidth(width) else c.preferredSize.height

    /** The width the child wants with no wrapping; containers take the max of their children. */
    private fun naturalWidthOf(c: Component): Int = when (c) {
        is HtmlBlock -> c.naturalWidth()
        is Container -> {
            val childMax = c.components.filter { it.isVisible }
                .maxOfOrNull { naturalWidthOf(it) } ?: 0
            val extra = c.insets.let { it.left + it.right }
            childMax + extra
        }
        else -> Int.MAX_VALUE / 2 // unknown content: fall back to the cap
    }

    private inner class BubbleLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) {}
        override fun removeLayoutComponent(comp: Component?) {}

        override fun preferredLayoutSize(parent: Container): Dimension {
            val w = if (parent.width > 0) parent.width else 600
            return Dimension(w, heightForWidth(w))
        }

        override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

        override fun layoutContainer(parent: Container) {
            synchronized(parent.treeLock) {
                val insets = parent.insets
                val inner = (parent.width - insets.left - insets.right).coerceAtLeast(1)
                val w = bubbleWidth(inner)
                val h = heightOf(bubble, w)
                bubble.setBounds(insets.left + inner - w, insets.top, w, h)
            }
        }
    }

    override fun getPreferredSize(): Dimension {
        val w = if (width > 0) width else 600
        return Dimension(w, heightForWidth(w))
    }
}
