package dev.pi.gui.rpc

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives `pi --mode rpc` as a child process.
 *
 * Protocol: newline-delimited JSON. Commands go to stdin; stdout carries both command
 * responses (`{"type":"response", ...}`) and unsolicited agent events. Responses are
 * correlated back to their caller by the `id` we attach to every command.
 *
 * All callbacks fire on the reader thread — callers are responsible for hopping to the EDT.
 */
class PiRpcClient(
    private val piExecutable: File,
    private val workingDir: File,
    private val environment: Map<String, String>,
    /** Existing session file to resume, or null to start a fresh session. */
    private val sessionFile: String? = null,
    private val extraArgs: List<String> = emptyList(),
) {
    interface Listener {
        fun onEvent(event: JsonObject) {}
        /** Called once the process exits, expectedly or otherwise. */
        fun onExit(exitCode: Int, stderr: String) {}
        fun onStderrLine(line: String) {}
    }

    private val gson = Gson()
    private val log = Logger.getInstance(PiRpcClient::class.java)

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val requestId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val stderrBuffer = StringBuilder()
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get() && process?.isAlive == true

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    @Throws(IOException::class)
    fun start() {
        check(!running.get()) { "RPC client already started" }

        val command = mutableListOf(piExecutable.absolutePath, "--mode", "rpc")
        sessionFile?.let { command += listOf("--session", it) }
        command += extraArgs

        val pb = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)
        pb.environment().putAll(environment)
        // pi honors these for its own child processes; keep the agent anchored to this project.
        pb.environment()["PWD"] = workingDir.absolutePath

        log.info("Starting pi RPC: ${command.joinToString(" ")} (cwd=${workingDir.absolutePath})")
        val proc = pb.start()
        process = proc
        writer = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
        running.set(true)

        startThread("pi-rpc-stdout") { pumpStdout(proc) }
        startThread("pi-rpc-stderr") { pumpStderr(proc) }
        startThread("pi-rpc-waiter") {
            val code = try { proc.waitFor() } catch (e: InterruptedException) { -1 }
            running.set(false)
            val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
            // Unblock anyone still waiting on a response.
            pending.keys.toList().forEach { id ->
                pending.remove(id)?.invoke(errorResponse(id, "pi process exited (code $code)"))
            }
            if (!stopping.get()) {
                listeners.forEach { runCatching { it.onExit(code, err) } }
            }
        }
    }

    private fun startThread(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private fun pumpStdout(proc: Process) {
        BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = try { reader.readLine() } catch (e: IOException) { null } ?: break
                if (line.isBlank()) continue
                val json = try {
                    JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
                } catch (e: Exception) {
                    log.debug("Ignoring non-JSON line from pi: ${line.take(200)}")
                    null
                } ?: continue
                dispatch(json)
            }
        }
    }

    private fun pumpStderr(proc: Process) {
        BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = try { reader.readLine() } catch (e: IOException) { null } ?: break
                synchronized(stderrBuffer) {
                    stderrBuffer.append(line).append('\n')
                    // Keep the tail only; some runs are chatty.
                    if (stderrBuffer.length > 64_000) {
                        stderrBuffer.delete(0, stderrBuffer.length - 32_000)
                    }
                }
                listeners.forEach { runCatching { it.onStderrLine(line) } }
            }
        }
    }

    private fun dispatch(json: JsonObject) {
        val type = json.get("type")?.asStringOrNull()
        if (type == "response") {
            val id = json.get("id")?.asStringOrNull()
            if (id != null) {
                val callback = pending.remove(id)
                if (callback != null) {
                    runCatching { callback(json) }
                        .onFailure { log.warn("RPC response handler failed", it) }
                    return
                }
            }
            // A response nobody is waiting for is still worth surfacing (e.g. errors).
        }
        listeners.forEach { runCatching { it.onEvent(json) }.onFailure { e -> log.warn("Event listener failed", e) } }
    }

    /**
     * Send a command. [onResponse] fires with the matching `response` object, or with a
     * synthetic failure response if the process dies first.
     */
    fun send(command: JsonObject, onResponse: ((JsonObject) -> Unit)? = null) {
        val id = requestId.incrementAndGet().toString()
        command.addProperty("id", id)
        if (onResponse != null) pending[id] = onResponse

        val w = writer
        if (w == null || !isRunning) {
            pending.remove(id)
            onResponse?.invoke(errorResponse(id, "pi process is not running"))
            return
        }
        try {
            synchronized(w) {
                w.write(gson.toJson(command))
                w.write("\n")
                w.flush()
            }
        } catch (e: IOException) {
            pending.remove(id)
            log.warn("Failed writing RPC command", e)
            onResponse?.invoke(errorResponse(id, "failed to write to pi: ${e.message}"))
        }
    }

    fun send(type: String, build: JsonObject.() -> Unit = {}, onResponse: ((JsonObject) -> Unit)? = null) {
        val obj = JsonObject()
        obj.addProperty("type", type)
        obj.build()
        send(obj, onResponse)
    }

    /**
     * @param images pi's `ImageContent`: `{type:"image", data:<base64>, mimeType:...}`.
     *               At most 10, each up to 10 MB, or the agent rejects the prompt.
     */
    fun prompt(
        message: String,
        images: List<ImagePayload> = emptyList(),
        onResponse: ((JsonObject) -> Unit)? = null,
    ) = send("prompt", {
        addProperty("message", message)
        if (images.isNotEmpty()) add("images", imagesArray(images))
    }, onResponse)

    fun steer(
        message: String,
        images: List<ImagePayload> = emptyList(),
        onResponse: ((JsonObject) -> Unit)? = null,
    ) = send("steer", {
        addProperty("message", message)
        if (images.isNotEmpty()) add("images", imagesArray(images))
    }, onResponse)

    private fun imagesArray(images: List<ImagePayload>): JsonArray = JsonArray().apply {
        images.forEach { image ->
            add(JsonObject().apply {
                addProperty("type", "image")
                addProperty("data", image.base64)
                addProperty("mimeType", image.mimeType)
            })
        }
    }

    data class ImagePayload(val base64: String, val mimeType: String)

    fun followUp(message: String, onResponse: ((JsonObject) -> Unit)? = null) =
        send("follow_up", { addProperty("message", message) }, onResponse)

    fun abort(onResponse: ((JsonObject) -> Unit)? = null) = send("abort", {}, onResponse)

    fun getState(onResponse: (JsonObject) -> Unit) = send("get_state", {}, onResponse)

    fun getAvailableModels(onResponse: (JsonObject) -> Unit) = send("get_available_models", {}, onResponse)

    fun setModel(provider: String, modelId: String, onResponse: ((JsonObject) -> Unit)? = null) =
        send("set_model", {
            addProperty("provider", provider)
            addProperty("modelId", modelId)
        }, onResponse)

    fun setThinkingLevel(level: String, onResponse: ((JsonObject) -> Unit)? = null) =
        send("set_thinking_level", { addProperty("level", level) }, onResponse)

    fun getAvailableThinkingLevels(onResponse: (JsonObject) -> Unit) =
        send("get_available_thinking_levels", {}, onResponse)

    fun compact(onResponse: ((JsonObject) -> Unit)? = null) = send("compact", {}, onResponse)

    fun setSessionName(name: String, onResponse: ((JsonObject) -> Unit)? = null) =
        send("set_session_name", { addProperty("name", name) }, onResponse)

    fun getMessages(onResponse: (JsonObject) -> Unit) = send("get_messages", {}, onResponse)

    fun getSessionStats(onResponse: (JsonObject) -> Unit) = send("get_session_stats", {}, onResponse)

    /** Answer an `extension_ui_request`. This is a notification, not a command — no id. */
    fun sendExtensionUiResponse(response: JsonObject) {
        val w = writer ?: return
        try {
            synchronized(w) {
                w.write(gson.toJson(response))
                w.write("\n")
                w.flush()
            }
        } catch (e: IOException) {
            log.warn("Failed writing extension UI response", e)
        }
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        running.set(false)
        val proc = process ?: return
        try { writer?.close() } catch (ignored: IOException) {}
        Thread({
            try {
                // Graceful first: closing stdin asks pi to exit, giving it time to flush the
                // session file.
                if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    // pi did not exit: kill the whole tree. On Windows pi runs as
                    // pi.cmd → cmd.exe → node (→ MCP servers), and destroying only the
                    // wrapper orphans node and everything under it — leaked trees are what
                    // make the IDE grind after a few session switches.
                    ProcessTree.killTree(proc)
                }
            } catch (e: Throwable) {
                log.debug("Failed stopping pi process", e)
            }
        }, "pi-rpc-stop").apply { isDaemon = true }.start()
    }

    fun collectedStderr(): String = synchronized(stderrBuffer) { stderrBuffer.toString() }

    private fun errorResponse(id: String, message: String): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", "response")
        addProperty("success", false)
        addProperty("error", message)
    }
}

internal fun com.google.gson.JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive) asString else null
