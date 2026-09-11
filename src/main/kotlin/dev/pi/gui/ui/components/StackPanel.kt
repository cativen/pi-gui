package dev.pi.gui.ui.components

import java.awt.Dimension
import javax.swing.JPanel

/** A transparent panel that stacks children vertically and reports height-for-width correctly. */
open class StackPanel(gap: Int = 0) : JPanel(), WidthAware {

    private val stack = VerticalStackLayout(gap)

    init {
        layout = stack
        isOpaque = false
    }

    override fun heightForWidth(width: Int): Int = stack.heightFor(this, width)

    override fun getPreferredSize(): Dimension {
        val w = if (width > 0) width else 600
        return Dimension(w, heightForWidth(w))
    }
}
