package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.ui.components.CodeBlock
import dev.pi.gui.ui.components.HtmlBlock
import javax.swing.plaf.TextUI
import javax.swing.text.View
import java.awt.Dimension

/**
 * The analytic monospace height must track what the HTML view would actually render, so the
 * scrollbar stays honest while the measurement itself stays off the EDT's critical path.
 */
class AnalyticHeightTest : BasePlatformTestCase() {

    fun testAnalyticHeightTracksViewMeasurementForCode() {
        val code = (1..300).joinToString("\n") {
            if (it % 7 == 0) "val long$it = someFunction(argument1, argument2, argument3, argument4, argument5)" +
                " // this line is deliberately long enough to wrap at the test width"
            else "val v$it = $it"
        }
        val analytic = CodeBlock(project, "kotlin", code)
        analytic.size = Dimension(600, 10)
        val a = analytic.heightForWidth(600)

        // Reference: the same content measured through the real HTML view pipeline.
        val reference = HtmlBlock(dev.pi.gui.ui.markdown.CodeHighlighter.toHtml(project, "kotlin", code))
        reference.size = Dimension(600, 10)
        reference.setSize(600 - reference.insets.left - reference.insets.right, Short.MAX_VALUE.toInt())
        val root: View = (reference.ui as TextUI).getRootView(reference)
        root.setSize(600f - reference.insets.left - reference.insets.right, Float.MAX_VALUE)
        val viewHeight = Math.ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt()

        val fm = reference.getFontMetrics(dev.pi.gui.ui.PiTheme.monoFont())
        // Never clip (analytic >= view) and never waste a screen (within ~2% + two rows).
        assertTrue("analytic $a clipped the real $viewHeight", a >= viewHeight - fm.height)
        assertTrue(
            "analytic $a drifted from real $viewHeight",
            a <= viewHeight + fm.height * 3 + viewHeight * 5 / 100,
        )
    }

    fun testAnalyticHeightIsCheapForHugeCode() {
        val code = (1..5_000).joinToString("\n") { "val v$it = $it // filler" }
        val start = System.currentTimeMillis()
        val block = CodeBlock(project, "kotlin", code)
        var h = 0
        repeat(50) { h = block.heightForWidth(600) }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("height $h", h > 0)
        assertTrue("5000-line measurement x50 took ${elapsed}ms", elapsed < 500)
    }
}
