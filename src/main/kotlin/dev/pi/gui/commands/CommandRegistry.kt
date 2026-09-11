package dev.pi.gui.commands

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import dev.pi.gui.PiLocator
import dev.pi.gui.rpc.PiRpcClient
import dev.pi.gui.rpc.asStringOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caches the slash commands available in one project.
 *
 * `get_commands` is an RPC call, so the list normally comes from the agent that is already
 * running. Before the first message there is no agent, and starting one just to populate a
 * completion popup would be heavy-handed — so a short-lived `pi --mode rpc` is spawned instead,
 * asked the one question and killed. Because the probe never sends a prompt, pi writes no
 * transcript — it only creates the (empty) session directory it creates on any launch, which the
 * sidebar already treats as "no sessions". Verified against pi 0.84.2.
 */
class CommandRegistry(private val workingDir: File?) {

    private val log = Logger.getInstance(CommandRegistry::class.java)

    @Volatile
    private var cached: List<PiCommand>? = null
    private val loading = AtomicBoolean(false)

    /**
     * What is known right now, without triggering any work.
     *
     * pi's built-ins are static, so they are always available — the popup is useful the instant
     * `/` is typed, and the fetched extension/prompt/skill commands fill in behind them. A fetched
     * command never shadows a built-in, matching pi, which skips extension commands whose name
     * collides with one.
     */
    fun snapshot(): List<PiCommand> {
        val fetched = cached ?: return BuiltinCommands.AS_COMMANDS
        val builtinNames = BuiltinCommands.ALL.mapTo(HashSet()) { it.name }
        return BuiltinCommands.AS_COMMANDS + fetched.filterNot { it.name in builtinNames }
    }

    fun hasLoaded(): Boolean = cached != null

    /** Drop the cache so the next [ensureLoaded] re-asks pi. */
    fun invalidate() {
        cached = null
    }

    /**
     * Populate the cache if it is empty, then call [onLoaded] on the EDT.
     *
     * Concurrent calls collapse into the one in-flight load; the extra callers simply do not get
     * a callback, which is what the popup wants — it only needs to be told once.
     */
    fun ensureLoaded(client: PiRpcClient?, onLoaded: (List<PiCommand>) -> Unit) {
        cached?.let { onLoaded(it); return }
        if (!loading.compareAndSet(false, true)) return

        if (client != null && client.isRunning) {
            client.send("get_commands", {}) { response -> finish(response, onLoaded) }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val response = probe()
            finish(response, onLoaded)
        }
    }

    /** Refresh from a live agent, replacing whatever the detached probe found. */
    fun refreshFrom(client: PiRpcClient, onLoaded: (List<PiCommand>) -> Unit) {
        client.send("get_commands", {}) { response ->
            val commands = PiCommand.parseResponse(response)
            if (commands.isNotEmpty() || response.get("success")?.asBoolean == true) {
                cached = commands
                ApplicationManager.getApplication().invokeLater { onLoaded(commands) }
            }
        }
    }

    private fun finish(response: JsonObject?, onLoaded: (List<PiCommand>) -> Unit) {
        val commands = response?.let { PiCommand.parseResponse(it) } ?: emptyList()
        // A failed probe is not cached: the popup should try again rather than stay empty forever.
        if (response != null && response.get("success")?.asBoolean == true) cached = commands
        loading.set(false)
        ApplicationManager.getApplication().invokeLater { onLoaded(commands) }
    }

    /**
     * One-shot `pi --mode rpc` that asks for the command list and exits. Blocking — pooled thread
     * only. Returns null if pi is missing, slow or unhappy; callers degrade to an empty list.
     */
    private fun probe(): JsonObject? {
        val executable = PiLocator.findPi() ?: return null
        val dir = workingDir?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))

        val process = try {
            ProcessBuilder(executable.absolutePath, "--mode", "rpc")
                .directory(dir)
                .redirectErrorStream(false)
                .also { it.environment().putAll(PiLocator.shellEnvironment()) }
                .start()
        } catch (e: Exception) {
            log.info("Could not start pi to list commands: ${e.message}")
            return null
        }

        // `readLine` blocks with no timeout of its own, so the deadline is enforced by killing the
        // process: closing its stdout is what makes the read return.
        val watchdog = Thread({
            try {
                if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (ignored: InterruptedException) {
            }
        }, "pi-commands-probe-watchdog").apply { isDaemon = true; start() }

        return try {
            val writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
            writer.write("""{"id":"pi-gui-commands","type":"get_commands"}""")
            writer.write("\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            var result: JsonObject? = null
            while (result == null) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val json = try {
                    JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
                } catch (e: Exception) {
                    null
                } ?: continue
                if (json.get("id")?.asStringOrNull() == "pi-gui-commands") result = json
            }
            result
        } catch (e: Exception) {
            log.info("Command probe failed: ${e.message}")
            null
        } finally {
            watchdog.interrupt()
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    @org.jetbrains.annotations.TestOnly
    fun seedForTest(commands: List<PiCommand>) {
        cached = commands
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 15_000L
    }
}
