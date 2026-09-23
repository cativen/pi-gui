package dev.pi.gui

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Installs pi with the command published on https://pi.dev/. */
object PiInstaller {
    const val UNIX_INSTALL_COMMAND = "curl -fsSL https://pi.dev/install.sh | sh"
    const val WINDOWS_INSTALL_COMMAND = "powershell -c \"irm https://pi.dev/install.ps1 | iex\""
    private const val TIMEOUT_MINUTES = 15L
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    data class Result(
        val success: Boolean,
        val output: String,
        val timedOut: Boolean = false,
    )

    fun command(isWindows: Boolean = PiLocator.isWindows()): String =
        if (isWindows) WINDOWS_INSTALL_COMMAND else UNIX_INSTALL_COMMAND

    fun install(): Result {
        return try {
            val environment = PiLocator.shellEnvironment()
            val processCommand = if (PiLocator.isWindows()) {
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "irm https://pi.dev/install.ps1 | iex")
            } else {
                val shell = environment["SHELL"]?.takeIf { it.isNotBlank() } ?: "/bin/sh"
                listOf(shell, "-lc", UNIX_INSTALL_COMMAND)
            }
            val builder = ProcessBuilder(processCommand).redirectErrorStream(true)
            // Do not leak the IDE process environment (including unrelated secrets) into a
            // downloaded script. PiLocator exposes only the operational allow-list.
            builder.environment().clear()
            builder.environment().putAll(environment)
            val process = builder.start()
            // The official installer may prompt to update PATH. Closing stdin makes that prompt
            // take its documented default instead of hanging inside the IDE indefinitely.
            process.outputStream.close()
            val output = StringBuilder()
            val reader = thread(name = "pi-gui-installer-output", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < MAX_OUTPUT_CHARS) {
                            val remaining = MAX_OUTPUT_CHARS - output.length
                            output.append(line.take(remaining)).append('\n')
                        }
                    }
                }
            }
            val finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finished) process.destroyForcibly()
            reader.join(2_000)
            Result(finished && process.exitValue() == 0, output.toString().trim(), timedOut = !finished)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
