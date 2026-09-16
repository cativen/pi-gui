package dev.pi.gui.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pi.gui.PiLocator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

enum class McpScope { GLOBAL, PROJECT }

data class McpServerView(
    val id: String,
    val name: String,
    val scope: McpScope,
    val source: String,
    val transport: String,
    val command: String,
    val args: List<String>,
    val url: String,
    val lifecycle: String,
    val enabled: Boolean,
    val valid: Boolean,
    val hasEnv: Boolean,
    val hasHeaders: Boolean,
) {
    fun target(): String = when (transport) {
        "stdio" -> (listOf(command) + args).filter { it.isNotBlank() }.joinToString(" ")
        else -> url
    }
}

data class McpServerInput(
    val id: String?,
    val name: String,
    val scope: McpScope,
    val transport: String,
    val command: String,
    val args: List<String>,
    val url: String,
    val lifecycle: String,
    val env: JsonObject?,
    val headers: JsonObject?,
)

/** Reads and updates the config files used by the common Pi MCP extensions. */
class McpConfigService(
    private val agentDir: File = PiLocator.agentDir(),
    private val projectDir: File? = null,
) {
    private data class Source(val id: String, val scope: McpScope, val file: File)

    fun list(): List<McpServerView> = sources().flatMap { source ->
        val active = servers(readObject(source.file))
        val disabled = servers(readObject(sidecar(source)))
        buildList {
            active.entrySet().sortedBy { it.key.lowercase() }.forEach { (name, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    add(view(source, name, it, enabled = true))
                }
            }
            disabled.entrySet().sortedBy { it.key.lowercase() }.forEach { (name, value) ->
                if (!active.has(name)) value.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    add(view(source, name, it, enabled = false))
                }
            }
        }
    }.sortedWith(compareBy<McpServerView> { it.scope != McpScope.PROJECT }.thenBy { it.name.lowercase() })

    fun save(input: McpServerInput): Result<Unit> = runCatching {
        val name = input.name.trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "invalid-name" }
        val transport = normalizeTransport(input.transport)
        require(transport in SUPPORTED_TRANSPORTS) { "invalid-transport" }
        if (transport == "stdio") require(input.command.trim().isNotEmpty()) { "command-required" }
        else require(input.url.trim().matches(Regex("https?://.+"))) { "url-required" }

        val existing = input.id?.let(::find)
        val source = existing?.first ?: sourceFor(input.scope)
        val oldName = existing?.second?.name
        val root = readObject(source.file)
        val active = servers(root)
        val disabledRoot = readObject(sidecar(source))
        val disabled = servers(disabledRoot)
        val wasEnabled = existing?.second?.enabled ?: true
        val previous = oldName?.let { if (wasEnabled) active.getAsJsonObject(it) else disabled.getAsJsonObject(it) }
        val config = previous?.deepCopy() ?: JsonObject()

        config.addProperty("transport", transport)
        config.remove("type")
        config.addProperty("lifecycle", input.lifecycle.takeIf { it == "eager" } ?: "lazy")
        if (transport == "stdio") {
            config.addProperty("command", input.command.trim())
            config.add("args", GSON.toJsonTree(input.args.map(String::trim).filter(String::isNotEmpty)))
            config.remove("url")
        } else {
            config.addProperty("url", input.url.trim())
            config.remove("command")
            config.remove("args")
        }
        input.env?.let { config.add("env", it.deepCopy()) }
        input.headers?.let { config.add("headers", it.deepCopy()) }

        if (oldName != null && oldName != name) {
            active.remove(oldName)
            disabled.remove(oldName)
        }
        if (wasEnabled) active.add(name, config) else disabled.add(name, config)
        writeObject(source.file, root)
        writeSidecar(source, disabledRoot)
    }

    fun setEnabled(id: String, enabled: Boolean): Result<Unit> = runCatching {
        val (source, server) = find(id) ?: error("not-found")
        if (server.enabled == enabled) return@runCatching
        val root = readObject(source.file)
        val active = servers(root)
        val disabledRoot = readObject(sidecar(source))
        val disabled = servers(disabledRoot)
        if (enabled) {
            val config = disabled.remove(server.name) ?: error("not-found")
            active.add(server.name, config)
        } else {
            val config = active.remove(server.name) ?: error("not-found")
            disabled.add(server.name, config)
        }
        writeObject(source.file, root)
        writeSidecar(source, disabledRoot)
    }

    fun delete(id: String): Result<Unit> = runCatching {
        val (source, server) = find(id) ?: error("not-found")
        val root = readObject(source.file)
        servers(root).remove(server.name)
        val disabledRoot = readObject(sidecar(source))
        servers(disabledRoot).remove(server.name)
        writeObject(source.file, root)
        writeSidecar(source, disabledRoot)
    }

    private fun find(id: String): Pair<Source, McpServerView>? {
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(id)) }.getOrNull() ?: return null
        val sourceId = decoded.substringBefore('\u0000')
        val name = decoded.substringAfter('\u0000', "")
        val source = sources().firstOrNull { it.id == sourceId } ?: return null
        return list().firstOrNull { it.id == id && it.name == name }?.let { source to it }
    }

    private fun sourceFor(scope: McpScope): Source = when (scope) {
        McpScope.GLOBAL -> sources().first { it.id == "global" }
        McpScope.PROJECT -> sources().firstOrNull { it.id == "project-pi" }
            ?: error("project-unavailable")
    }

    private fun sources(): List<Source> = buildList {
        add(Source("global", McpScope.GLOBAL, File(agentDir, "mcp.json")))
        projectDir?.let { project ->
            add(Source("project-pi", McpScope.PROJECT, File(project, ".pi/mcp.json")))
            File(project, ".mcp.json").takeIf { it.isFile }?.let {
                add(Source("project-compatible", McpScope.PROJECT, it))
            }
        }
    }

    private fun view(source: Source, name: String, config: JsonObject, enabled: Boolean): McpServerView {
        val transport = normalizeTransport(
            config["transport"]?.takeIf { it.isJsonPrimitive }?.asString
                ?: config["type"]?.takeIf { it.isJsonPrimitive }?.asString
                ?: "stdio"
        )
        val command = config["command"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val args = config["args"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }.orEmpty()
        val url = config["url"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val valid = if (transport == "stdio") command.isNotBlank() else url.matches(Regex("https?://.+"))
        return McpServerView(
            id = id(source, name), name = name, scope = source.scope,
            source = source.file.absolutePath, transport = transport, command = command, args = args,
            url = url,
            lifecycle = config["lifecycle"]?.takeIf { it.isJsonPrimitive }?.asString ?: "lazy",
            enabled = enabled, valid = valid,
            hasEnv = config["env"]?.isJsonObject == true,
            hasHeaders = config["headers"]?.isJsonObject == true,
        )
    }

    private fun id(source: Source, name: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${source.id}\u0000$name".toByteArray())

    private fun normalizeTransport(value: String): String = when (value.lowercase()) {
        "http", "streamable_http", "streamable-http" -> "streamable-http"
        "sse" -> "sse"
        else -> "stdio"
    }

    private fun readObject(file: File): JsonObject = runCatching {
        if (!file.isFile) JsonObject() else JsonParser.parseString(file.readText()).asJsonObject
    }.getOrDefault(JsonObject())

    private fun servers(root: JsonObject): JsonObject {
        val current = root["mcpServers"]
        if (current?.isJsonObject == true) return current.asJsonObject
        return JsonObject().also { root.add("mcpServers", it) }
    }

    private fun sidecar(source: Source): File = File(source.file.parentFile, ".pi-gui-mcp-disabled.json")

    private fun writeSidecar(source: Source, root: JsonObject) {
        val file = sidecar(source)
        if (servers(root).size() == 0) {
            if (file.isFile) file.delete()
        } else writeObject(file, root)
    }

    private fun writeObject(file: File, root: JsonObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.pi-gui.tmp")
        temp.writeText(GSON.toJson(root) + "\n")
        runCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val SUPPORTED_TRANSPORTS = setOf("stdio", "streamable-http", "sse")
    }
}
