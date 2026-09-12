package dev.pi.gui.providers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import dev.pi.gui.PiLocator
import java.io.File

/**
 * Owns the imported-provider list and mirrors it into pi's own `~/.pi/agent/models.json`.
 *
 * Two files are involved:
 *  - `providers.import.json` — the registry's private sidecar (what we imported, from where);
 *  - `models.json` — pi's official custom-provider file. We only ever touch entries whose id
 *    starts with [ID_PREFIX]; anything the user wrote there by hand survives untouched.
 */
class ProvidersRegistry(private val agentDir: File) {

    private val LOG = Logger.getInstance(ProvidersRegistry::class.java)

    data class ImportSummary(val added: Int, val updated: Int, val skipped: List<CcSwitchImporter.Skipped>)

    fun sidecarFile(): File = File(agentDir, SIDECAR_NAME)
    fun modelsFile(): File = File(agentDir, MODELS_NAME)

    /** All imported providers, Claude Code first then Codex, each alphabetical. */
    fun list(): List<ImportedProvider> = try {
        readSidecar().sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    } catch (e: Exception) {
        LOG.warn("Cannot read provider sidecar", e)
        emptyList()
    }

    fun find(id: String): ImportedProvider? = list().firstOrNull { it.id == id }

    /**
     * Merges an import outcome into the registry: entries are matched by cc-switch source id
     * (falling back to provider id) so re-importing updates in place instead of duplicating.
     */
    fun import(outcome: CcSwitchImporter.Outcome): ImportSummary {
        val existing = readSidecar().toMutableList()
        var added = 0
        var updated = 0

        outcome.imported.forEach { incoming ->
            val match = existing.firstOrNull { e ->
                (incoming.sourceId != null && e.sourceId == incoming.sourceId) || e.id == incoming.id
            }
            if (match == null) {
                existing += incoming.copy(id = uniqueId(incoming.name, existing))
                added++
            } else {
                val index = existing.indexOf(match)
                existing[index] = incoming.copy(id = match.id)
                updated++
            }
        }

        write(existing)
        return ImportSummary(added, updated, outcome.skipped)
    }

    /** Applies an edit made in the settings dialog. */
    fun update(provider: ImportedProvider) {
        val existing = readSidecar().toMutableList()
        val index = existing.indexOfFirst { it.id == provider.id }
        if (index < 0) return
        existing[index] = provider
        write(existing)
    }

    fun delete(id: String) {
        write(readSidecar().filterNot { it.id == id })
    }

    // ------------------------------------------------------------- storage

    private fun readSidecar(): List<ImportedProvider> {
        val file = sidecarFile()
        if (!file.isFile) return emptyList()
        val root = JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val providers = root.get("providers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return providers.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val models = obj.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { p -> p.isJsonPrimitive }?.asString } ?: return@mapNotNull null
            val kind = try {
                ProviderKind.valueOf(obj.get("kind")?.asString ?: return@mapNotNull null)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            ImportedProvider(
                id = obj.get("id")?.asString ?: return@mapNotNull null,
                name = obj.get("name")?.asString ?: return@mapNotNull null,
                kind = kind,
                baseUrl = obj.get("baseUrl")?.asString.orEmpty(),
                apiKey = obj.get("apiKey")?.asString.orEmpty(),
                models = models,
                defaultModel = obj.get("defaultModel")?.asString?.takeIf { it.isNotBlank() },
                sourceId = obj.get("sourceId")?.asString?.takeIf { it.isNotBlank() },
                api = obj.get("api")?.asString ?: "anthropic-messages",
            )
        }
    }

    private fun write(providers: List<ImportedProvider>) {
        agentDir.mkdirs()
        sidecarFile().writeText(gson.toJson(sidecarJson(providers)))
        writeModelsJson(providers)
    }

    private fun sidecarJson(providers: List<ImportedProvider>): JsonObject {
        val array = JsonArray()
        providers.forEach { p ->
            array.add(
                JsonObject().apply {
                    addProperty("id", p.id)
                    addProperty("name", p.name)
                    addProperty("kind", p.kind.name)
                    addProperty("baseUrl", p.baseUrl)
                    addProperty("apiKey", p.apiKey)
                    add("models", JsonArray().apply { p.models.forEach { add(it) } })
                    p.defaultModel?.let { addProperty("defaultModel", it) }
                    p.sourceId?.let { addProperty("sourceId", it) }
                    addProperty("api", p.api)
                }
            )
        }
        return JsonObject().apply {
            addProperty("version", 1)
            add("providers", array)
        }
    }

    /**
     * Rewrites pi's `models.json`: our managed `ccswitch-*` entries are removed and re-added,
     * every other key the user may have written survives as-is.
     */
    private fun writeModelsJson(providers: List<ImportedProvider>) {
        val file = modelsFile()
        val root = if (file.isFile) {
            try {
                JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                LOG.warn("models.json is unparseable; refusing to overwrite it", e)
                null
            } ?: JsonObject()
        } else {
            JsonObject()
        }

        val piProviders = root.get("providers")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { root.add("providers", it) }

        piProviders.keySet().filter { it.startsWith(ID_PREFIX) }.forEach { piProviders.remove(it) }
        providers.forEach { p -> piProviders.add(p.id, piProviderJson(p)) }

        file.writeText(gson.toJson(root))
    }

    private fun piProviderJson(p: ImportedProvider): JsonObject = JsonObject().apply {
        addProperty("name", p.name)
        addProperty("baseUrl", p.baseUrl)
        addProperty("api", p.api)
        addProperty("apiKey", p.apiKey)
        add("models", JsonArray().apply {
            p.models.forEach { modelId ->
                add(
                    JsonObject().apply {
                        addProperty("id", modelId)
                        addProperty("name", modelId)
                        addProperty("reasoning", true)
                        add("input", JsonArray().apply {
                            add("text")
                            add("image")
                        })
                        addProperty("contextWindow", DEFAULT_CONTEXT_WINDOW)
                        addProperty("maxTokens", DEFAULT_MAX_TOKENS)
                        add("cost", JsonObject().apply {
                            addProperty("input", 0)
                            addProperty("output", 0)
                            addProperty("cacheRead", 0)
                            addProperty("cacheWrite", 0)
                        })
                    }
                )
            }
        })
    }

    /** `ccswitch-<slug>` unique among the current ids. */
    private fun uniqueId(name: String, existing: List<ImportedProvider>): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "provider" }
        var candidate = ID_PREFIX + slug
        var counter = 2
        val taken = existing.map { it.id }.toSet()
        while (candidate in taken) {
            candidate = "$ID_PREFIX$slug-$counter"
            counter++
        }
        return candidate
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        const val ID_PREFIX = "ccswitch-"
        const val SIDECAR_NAME = "providers.import.json"
        const val MODELS_NAME = "models.json"
        const val DEFAULT_CONTEXT_WINDOW = 200_000
        const val DEFAULT_MAX_TOKENS = 64_000

        fun default(): ProvidersRegistry = ProvidersRegistry(PiLocator.agentDir())
    }
}
