package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.pi.gui.commands.CommandRegistry
import java.io.File

/**
 * Exercises the detached `pi --mode rpc` probe against the real CLI.
 *
 * This is the part of the feature that cannot be faked: before the first message there is no
 * agent, so the popup depends on a throwaway process answering `get_commands` and exiting without
 * side effects. Skipped when pi is not installed.
 */
class CommandProbeLiveTest : BasePlatformTestCase() {

    fun testProbeReadsCommandsFromTheRealCli() {
        val pi = PiLocator.findPi()
        if (pi == null) {
            println("pi CLI not found — skipping the live command probe test")
            return
        }

        val registry = CommandRegistry(File(System.getProperty("user.home")))
        registry.ensureLoaded(null) {}
        assertTrue("probe did not answer within the timeout", awaitLoaded(registry))

        val commands = registry.snapshot()
        // Every command has to be invocable, which means a non-blank name.
        assertTrue(commands.all { it.name.isNotBlank() })

        // The snapshot merges pi's built-ins with what the probe fetched; only the fetched half
        // comes off the wire, and pi documents exactly three kinds for it.
        val fetched = commands.filterNot { it.source == dev.pi.gui.commands.BuiltinCommands.SOURCE }
        assertTrue("the real CLI reported no commands at all", fetched.isNotEmpty())
        val kinds = fetched.mapNotNull { it.source }.toSet()
        assertTrue(
            "unexpected command kinds from pi: $kinds",
            setOf("extension", "prompt", "skill").containsAll(kinds),
        )
    }

    /**
     * The probe must not leave a session behind — it is opened purely to ask one question.
     *
     * pi does create the session *directory* for whatever cwd it is launched in, as it does on
     * every launch, but it must stay empty: a stray transcript would show up in the sidebar.
     */
    fun testProbeWritesNoSessionFile() {
        val pi = PiLocator.findPi()
        if (pi == null) {
            println("pi CLI not found — skipping the live command probe test")
            return
        }

        val dir = File(System.getProperty("java.io.tmpdir"), "pi-gui-probe-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val sessionDir = File(
            System.getProperty("user.home"),
            ".pi/agent/sessions/--" + dir.canonicalPath.replace('/', '-').trim('-') + "--",
        )
        try {
            val registry = CommandRegistry(dir)
            registry.ensureLoaded(null) {}
            awaitLoaded(registry)
            val transcripts = sessionDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            assertTrue(
                "the probe wrote ${transcripts.size} session file(s) into ${sessionDir.path}",
                transcripts.isEmpty(),
            )
        } finally {
            dir.deleteRecursively()
            sessionDir.delete()
        }
    }

    /** The callback lands on the EDT, which is where this test runs — so pump while waiting. */
    private fun awaitLoaded(registry: CommandRegistry): Boolean {
        repeat(400) {
            UIUtil.dispatchAllInvocationEvents()
            if (registry.hasLoaded()) return true
            Thread.sleep(50)
        }
        return registry.hasLoaded()
    }
}
