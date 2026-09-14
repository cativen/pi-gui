package dev.pi.gui

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.ui.ChatPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * The composer's context readout and its compact button.
 *
 * pi reports the figure as `contextUsage` on `get_session_stats`; the shapes exercised here were
 * captured from the real CLI, including the cases its docs call out — the object is absent when
 * no model is bound, and `tokens`/`percent` are null straight after a compaction.
 */
class ContextCompactionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The readout and the compact button are asserted as Swing components here; in web
        // mode they are DOM nodes. The threshold logic they guard is shared.
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------ the threshold

    fun testBelowTheThresholdCompactionIsRefused() {
        assertTrue(ChatPanel.isTooSmallToCompact(0.0))
        assertTrue(ChatPanel.isTooSmallToCompact(12.8807))
        assertTrue(ChatPanel.isTooSmallToCompact(19.99))
    }

    fun testAtOrAboveTheThresholdCompactionRuns() {
        assertFalse(ChatPanel.isTooSmallToCompact(ChatPanel.COMPACT_MIN_PERCENT))
        assertFalse(ChatPanel.isTooSmallToCompact(55.0))
        assertFalse(ChatPanel.isTooSmallToCompact(100.0))
    }

    /**
     * pi sends a null percentage when no model is bound and right after a compaction. Blocking on
     * that would refuse a compaction the user legitimately asked for, so unknown must pass.
     */
    fun testUnknownUsageDoesNotBlockCompaction() {
        assertFalse(ChatPanel.isTooSmallToCompact(null))
    }

    // ------------------------------------------------------------- the readout

    fun testPercentageIsRoundedForDisplay() {
        assertEquals("13%", ChatPanel.formatContextPercent(12.8807))
        assertEquals("20%", ChatPanel.formatContextPercent(20.0))
        assertEquals("0%", ChatPanel.formatContextPercent(0.0))
    }

    /** A session with real content must never read as `0%`. */
    fun testTinyButNonZeroUsageIsNotShownAsZero() {
        assertEquals("<1%", ChatPanel.formatContextPercent(0.4))
        assertEquals("<1%", ChatPanel.formatContextPercent(0.999))
    }

    /** Captured from pi 0.84.2 on a real session. */
    fun testReadoutUsesTheRealPayloadShape() {
        val panel = ChatPanel(project)
        try {
            panel.applyContextUsageForTest(
                JsonParser.parseString(
                    """{"contextUsage":{"tokens":128807,"contextWindow":1000000,"percent":12.8807}}"""
                ).asJsonObject
            )
            val label = panel.contextLabelForTest()
            assertTrue("readout was '${label.text}'", label.text.contains("13%"))
            assertTrue(label.isVisible)
            assertTrue(
                "tooltip should carry the absolute figures, was '${label.toolTipText}'",
                label.toolTipText.contains("128.8k") && label.toolTipText.contains("1.0M"),
            )
        } finally {
            panel.dispose()
        }
    }

    /** `contextUsage` is omitted when no model or context window is available. */
    fun testReadoutHidesWhenPiReportsNoUsage() {
        val panel = ChatPanel(project)
        try {
            panel.applyContextUsageForTest(JsonParser.parseString("""{"totalMessages":3}""").asJsonObject)
            assertFalse(panel.contextLabelForTest().isVisible)
            assertEquals("", panel.contextLabelForTest().text)
        } finally {
            panel.dispose()
        }
    }

    /** Immediately after a compaction pi nulls the figures until the next assistant reply. */
    fun testReadoutHidesWhileTheFigureIsNullAfterCompaction() {
        val panel = ChatPanel(project)
        try {
            panel.applyContextUsageForTest(
                JsonParser.parseString(
                    """{"contextUsage":{"tokens":null,"contextWindow":1000000,"percent":null}}"""
                ).asJsonObject
            )
            assertFalse(panel.contextLabelForTest().isVisible)
        } finally {
            panel.dispose()
        }
    }

    /** Switching sessions must not leave the previous session's percentage on screen. */
    fun testReadoutClearsWhenUsageGoesAway() {
        val panel = ChatPanel(project)
        try {
            panel.applyContextUsageForTest(
                JsonParser.parseString(
                    """{"contextUsage":{"tokens":500000,"contextWindow":1000000,"percent":50.0}}"""
                ).asJsonObject
            )
            assertTrue(panel.contextLabelForTest().isVisible)

            panel.applyContextUsageForTest(null)
            assertFalse(panel.contextLabelForTest().isVisible)
            assertEquals("", panel.contextLabelForTest().text)
        } finally {
            panel.dispose()
        }
    }

    // --------------------------------------------------------------- the button

    fun testCompactButtonSitsInTheComposerNextToSend() {
        val panel = ChatPanel(project)
        try {
            val compact = panel.compactButtonForTest()
            val row = compact.parent
            assertNotNull("compact button was never added to the composer", row)

            val onScreen = mutableListOf<Component>()
            collect(panel, onScreen)
            assertTrue("compact button is not in the panel", onScreen.contains(compact))
            assertTrue("context readout is not in the panel", onScreen.contains(panel.contextLabelForTest()))

            // It shares the control row with the send button, to the left of it.
            val children = (row as Container).components.toList()
            val compactIndex = children.indexOf(compact)
            val sendIndex = children.indexOfFirst { it is JComponent && it !== compact && isSendButton(it) }
            assertTrue("send button not found in the same row", sendIndex >= 0)
            assertTrue(
                "compact button should sit before send (compact=$compactIndex send=$sendIndex)",
                compactIndex in 0 until sendIndex,
            )
        } finally {
            panel.dispose()
        }
    }

    /** Compacting while a turn is in flight fights the run that is using the context. */
    fun testCompactButtonIsDisabledWhileTheAgentRuns() {
        val panel = ChatPanel(project)
        try {
            assertTrue("should start enabled", panel.compactButtonForTest().isEnabled)
            panel.setRunningForTest(true)
            assertFalse("must be disabled mid-run", panel.compactButtonForTest().isEnabled)
            panel.setRunningForTest(false)
            assertTrue("must come back after the run", panel.compactButtonForTest().isEnabled)
        } finally {
            panel.dispose()
        }
    }

    private fun isSendButton(c: Component): Boolean =
        c is dev.pi.gui.ui.components.PiButton &&
            c.toolTipText == dev.pi.gui.i18n.PiBundle.message("chat.send")

    private fun collect(c: Component, out: MutableList<Component>) {
        out.add(c)
        if (c is Container) c.components.forEach { collect(it, out) }
    }
}
