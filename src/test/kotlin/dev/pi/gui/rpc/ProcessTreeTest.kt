package dev.pi.gui.rpc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The process tree must die with its root. On Windows pi runs as
 * `pi.cmd → cmd.exe → node (→ MCP servers)`; `Process.destroy()` alone leaves node alive —
 * every session switch then leaked a full pi tree, and enough leaks froze the whole IDE.
 */
class ProcessTreeTest {

    @Test
    fun killingTheTreeTakesTheChildrenWithIt() {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        // Same shape as a pi spawn: a shell wrapper with a long-running child.
        val proc = if (windows) {
            ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > nul").start()
        } else {
            ProcessBuilder("sh", "-c", "sleep 60").start()
        }
        try {
            if (windows) {
                assertTrue("child never spawned under the wrapper", waitFor { proc.descendants().count() > 0 })
            }
            val children = proc.descendants().map { it.pid() }.toList()

            ProcessTree.killTree(proc)

            assertTrue("root survived the tree kill", waitFor { !proc.isAlive })
            children.forEach { pid ->
                assertTrue(
                    "child $pid survived the tree kill",
                    waitFor { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) },
                )
            }
        } finally {
            // Never leak a test process, even when an assertion fails above.
            runCatching { ProcessTree.killTree(proc) }
        }
    }

    /** A plain destroy() must not be able to pass this test — it kills the wrapper only. */
    @Test
    fun killTreeIsFastEnoughForAWatchdog() {
        val proc = ProcessBuilder(if (System.getProperty("os.name").lowercase().contains("win"))
            "cmd.exe" else "sh", if (System.getProperty("os.name").lowercase().contains("win"))
            "/c ping -n 60 127.0.0.1 > nul" else "-c sleep 60").start()
        try {
            val start = System.nanoTime()
            ProcessTree.killTree(proc)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("tree kill took ${elapsedMs}ms", elapsedMs < 5_000)
            assertTrue("process still alive", waitFor { !proc.isAlive })
        } finally {
            runCatching { ProcessTree.killTree(proc) }
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
