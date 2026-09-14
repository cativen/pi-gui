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

    /**
     * The analytic path exists to avoid an O(document) HTML layout pass, so that is what is
     * asserted — relative to the very pipeline it replaces.
     *
     * A wall-clock budget flaked: it timed construction (which syntax-highlights every line) and
     * a cold JIT inside the same window, and 50 repeats at one width were 49 cache hits, since
     * `heightForWidth` memoises per width. Both sides of a ratio move together with machine speed
     * and load, so this holds on a busy laptop and on CI alike.
     */
    fun testAnalyticHeightIsCheapForHugeCode() {
        val code = (1..2_000).joinToString("\n") { "val v$it = $it // filler" }
        val html = dev.pi.gui.ui.markdown.CodeHighlighter.toHtml(project, "kotlin", code)

        // Warm the JIT and the shared highlighting caches so neither side pays first-call costs.
        CodeBlock(project, "kotlin", "val warm = 1").heightForWidth(600)

        val analytic = CodeBlock(project, "kotlin", code)
        analytic.heightForWidth(600)

        // A distinct width per call, or the per-width memo would make this time 49 cache hits.
        var h = 0
        val analyticStart = System.nanoTime()
        repeat(MEASUREMENTS) { h = analytic.heightForWidth(600 + it) }
        val analyticNanos = System.nanoTime() - analyticStart

        val viewBased = HtmlBlock(html)
        val viewStart = System.nanoTime()
        val viewHeight = viewBased.heightForWidth(600)
        val viewNanos = System.nanoTime() - viewStart

        assertTrue("analytic height $h", h > 0)
        assertTrue("reference height $viewHeight", viewHeight > 0)
        assertTrue(
            "$MEASUREMENTS analytic measurements took ${analyticNanos / 1_000_000}ms, " +
                "but a single view measurement took ${viewNanos / 1_000_000}ms — " +
                "the analytic path is no longer avoiding the layout pass",
            analyticNanos < viewNanos,
        )
    }

    private companion object {
        const val MEASUREMENTS = 50
    }
}
