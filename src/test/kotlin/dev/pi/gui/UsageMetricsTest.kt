package dev.pi.gui

import dev.pi.gui.model.Usage
import dev.pi.gui.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageMetricsTest {

    @Test
    fun `cache hit rate is the cached share of prompt tokens`() {
        // Shape taken from a real pi session entry.
        val usage = Usage(input = 743, output = 50, cacheRead = 9408, cacheWrite = 0)
        assertEquals(0.927, usage.cacheHitRate()!!, 0.001)
        assertEquals("93%", SessionStore.formatPercent(usage.cacheHitRate()!!))
    }

    /** Output tokens are not prompt tokens and must not dilute the rate. */
    @Test
    fun `output tokens are excluded from the rate`() {
        val usage = Usage(input = 100, output = 100_000, cacheRead = 100, cacheWrite = 0)
        assertEquals(0.5, usage.cacheHitRate()!!, 0.0001)
    }

    @Test
    fun `cache writes count as prompt tokens that missed`() {
        val usage = Usage(input = 0, output = 10, cacheRead = 50, cacheWrite = 50)
        assertEquals(0.5, usage.cacheHitRate()!!, 0.0001)
    }

    @Test
    fun `no prompt tokens yields no rate rather than a division by zero`() {
        assertNull(Usage(input = 0, output = 25, cacheRead = 0, cacheWrite = 0).cacheHitRate())
    }

    @Test
    fun `fully cached prompt is one hundred percent`() {
        val usage = Usage(input = 0, output = 5, cacheRead = 2048, cacheWrite = 0)
        assertEquals("100%", SessionStore.formatPercent(usage.cacheHitRate()!!))
    }

    @Test
    fun `uncached prompt is zero percent`() {
        val usage = Usage(input = 2048, output = 5, cacheRead = 0, cacheWrite = 0)
        assertEquals("0%", SessionStore.formatPercent(usage.cacheHitRate()!!))
    }

    @Test
    fun `prompt tokens exclude output`() {
        assertEquals(300L, Usage(input = 100, output = 999, cacheRead = 100, cacheWrite = 100).promptTokens())
    }

    @Test
    fun `durations read naturally at every scale`() {
        assertEquals("250ms", SessionStore.formatDuration(250))
        assertEquals("1.5s", SessionStore.formatDuration(1_500))
        assertEquals("42s", SessionStore.formatDuration(42_000))
        assertEquals("2m 5s", SessionStore.formatDuration(125_000))
    }

    @Test
    fun `zero duration is still rendered`() {
        assertEquals("0ms", SessionStore.formatDuration(0))
    }
}
