package dev.pi.gui

import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Checks the version of the pi.dev CLI and runs its documented self-update command. */
object PiCliUpdater {
    private const val PACKAGE_URL = "https://registry.npmjs.org/@earendil-works/pi-coding-agent/latest"
    private val VERSION = Regex("""(?:^|\s)v?(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)""")

    fun installedVersion(executable: File): String? =
        run(executable, "--version", 10)?.takeIf { it.success }?.output
            ?.let { VERSION.find(it)?.groupValues?.get(1) }

    /** The package linked from pi.dev is the source used by its official installer. */
    fun latestVersion(): String? = try {
        val connection = URL(PACKAGE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Pi-GUI/1.0.4")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.reader(Charsets.UTF_8).use {
                JsonParser.parseReader(it).asJsonObject.get("version")?.asString
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    fun updateAvailable(installed: String?, latest: String?): Boolean {
        val current = parse(installed) ?: return false
        val target = parse(latest) ?: return false
        for (index in 0..2) {
            if (current.first[index] != target.first[index])
                return current.first[index] < target.first[index]
        }
        // A prerelease of the same numeric version is older than the stable release.
        return current.second && !target.second
    }

    fun update(executable: File): PiInstaller.Result =
        run(executable, "update", 15 * 60)
            ?: PiInstaller.Result(false, "Could not start pi update.")

    private fun parse(value: String?): Pair<List<Int>, Boolean>? {
        val match = value?.trim()?.let { VERSION.find(it) } ?: return null
        val numbers = match.groupValues[1].substringBefore('-').substringBefore('+')
            .split('.').mapNotNull(String::toIntOrNull)
        if (numbers.size != 3) return null
        return numbers to match.groupValues[1].contains('-')
    }

    private fun run(executable: File, argument: String, timeoutSeconds: Long): PiInstaller.Result? = try {
        val command = if (PiLocator.isWindows() && executable.extension.equals("cmd", true)) {
            listOf("cmd.exe", "/d", "/c", "\"${executable.absolutePath}\" $argument")
        } else {
            listOf(executable.absolutePath, argument)
        }
        val environment = PiLocator.shellEnvironment().toMutableMap()
        // Version checks and package updates do not need AI provider credentials.
        PiLocator.AI_CREDENTIAL_KEYS.forEach(environment::remove)
        environment["PATH"] = listOfNotNull(executable.parent, environment["PATH"])
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment().clear()
            environment().putAll(environment)
        }.start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = thread(name = "pi-gui-cli-$argument", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < 64 * 1024) output.append(line.take(64 * 1024 - output.length)).append('\n')
                }
            }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2_000)
        PiInstaller.Result(finished && process.exitValue() == 0, output.toString().trim(), !finished)
    } catch (e: Exception) {
        PiInstaller.Result(false, e.message ?: e.javaClass.simpleName)
    }
}
