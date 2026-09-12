package dev.pi.gui.ui

import com.intellij.util.ui.JBUI
import dev.pi.gui.ui.components.StackPanel
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.Scrollable

/**
 * The scrollable message list.
 *
 * [getScrollableTracksViewportWidth] is the important part: it makes the viewport dictate our
 * width, which is what lets every child compute a correct wrapped height.
 */
class TranscriptPanel : StackPanel(0), Scrollable {

    init {
        border = JBUI.Borders.empty(12, 16)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(24)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
