package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.skills.SearchTimeoutException
import dev.pi.gui.skills.SkillsRegistry
import java.net.ServerSocket

/**
 * The search deadline watchdog: a wedged endpoint (or a wedged proxy negotiation) must surface as
 * a [SearchTimeoutException] within the deadline, not hang the UI on "searching" forever.
 */
class SkillsSearchWatchdogTest : BasePlatformTestCase() {

    /**
     * A listening socket that never accepts or answers: the TCP handshake completes through the
     * backlog, so connect() succeeds and the read blocks — exactly the shape of a stalled proxy.
     */
    fun testStalledEndpointTimesOutWithinTheDeadline() {
        ServerSocket(0).use { server ->
            server.soTimeout = 0
            val startedAt = System.currentTimeMillis()
            val error = try {
                SkillsRegistry.searchUrl("http://127.0.0.1:${server.localPort}/api/search?q=word", 1_500)
                null
            } catch (e: SearchTimeoutException) {
                e
            }
            val elapsed = System.currentTimeMillis() - startedAt

            assertNotNull("a stalled endpoint must throw SearchTimeoutException, not return", error)
            assertTrue("must give up within the deadline (took ${elapsed}ms)", elapsed < 10_000)
            assertTrue(error!!.message.orEmpty().contains("within"))
        }
    }

    /** The happy path: a well-formed payload still parses into results. */
    fun testParseReadsTheRegistryPayload() {
        val body = java.io.File(javaClass.classLoader.getResource("fixtures/skills-search.json").toURI())
            .readText()
        val results = SkillsRegistry.parse(body)
        assertTrue("fixture must contain results", results.size >= 2)
        assertEquals("anthropics/skills/pdf", results[0].id)
        assertEquals("pdf", results[0].name)
        assertTrue(results[0].installs > 0)
    }
}
