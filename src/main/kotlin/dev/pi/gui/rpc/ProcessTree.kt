package dev.pi.gui.rpc

import java.util.concurrent.TimeUnit

/**
 * Kills a process together with everything it spawned.
 *
 * On Windows pi is launched as `pi.cmd`, so the real process tree is
 * `cmd.exe → node.exe (pi) → MCP servers`. `Process.destroy()` terminates only the handle it
 * holds — the cmd.exe wrapper — and orphans the rest. Every leaked tree pins hundreds of MB and
 * its file watchers keep burning CPU, which is how a few session switches can grind the whole
 * IDE (and the machine) to a halt.
 */
internal object ProcessTree {

    fun killTree(root: Process) {
        // Capture the tree first: once the parent dies, its descendants are re-parented and
        // can no longer be found through it.
        val tree = ArrayList<ProcessHandle>()
        root.descendants().forEach { tree.add(it) }
        tree.add(root.toHandle())

        // Deepest first (descendants() yields parents before children, so reverse it): a child
        // killed after its parent may respawn or re-parent itself out of reach.
        tree.asReversed().forEach { runCatching { it.destroyForcibly() } }

        // Brief deadline so a stuck process cannot stall the caller's watchdog thread forever.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        tree.forEach { handle ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) runCatching { handle.onExit().get(remaining, TimeUnit.NANOSECONDS) }
        }
    }
}
