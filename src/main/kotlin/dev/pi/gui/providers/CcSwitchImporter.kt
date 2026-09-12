package dev.pi.gui.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/**
 * Reads provider definitions out of cc-switch, in both storage generations:
 *
 *  - v2: a single `~/.cc-switch/config.json` with a `claude` and a `codex` section;
 *  - v3: a SQLite `~/.cc-switch/cc-switch.db` whose `providers` table holds one row per
 *    provider with a JSON `settings_config` column.
 *
 * Entries without usable API credentials (Claude Official / ChatGPT login) are reported as
 * skipped rather than imported, because pi cannot use another app's OAuth session.
 */
object CcSwitchImporter {

    private val LOG = Logger.getInstance(CcSwitchImporter::class.java)

    /** One provider from cc-switch that could NOT be turned into a pi provider. */
    data class Skipped(val name: String, val reason: Reason) {
        enum class Reason { NO_CREDENTIALS, NO_BASE_URL, NO_MODELS, BAD_DATA, NOT_FOUND }
    }

    data class Outcome(
        val imported: List<ImportedProvider>,
        val skipped: List<Skipped>,
        /** Which cc-switch storage produced this outcome (`cc-switch.db`, `config.json`), if any. */
        val source: String? = null,
    ) {
        companion object {
            fun failure(reason: Skipped.Reason, detail: String): Outcome =
                Outcome(emptyList(), listOf(Skipped(detail, reason)), null)
        }
    }

    // ------------------------------------------------------------ auto-detect

    /** Default cc-switch directory: `~/.cc-switch`. */
    fun defaultDir(): File = File(System.getProperty("user.home"), ".cc-switch")

    /** Default location of the v2 config: `~/.cc-switch/config.json`. */
    fun defaultJsonFile(): File = File(defaultDir(), "config.json")

    /** Default location of the v3 database: `~/.cc-switch/cc-switch.db`. */
    fun defaultDbFile(): File = File(defaultDir(), "cc-switch.db")

    /**
     * One-click import, as other GUIs do it: probes [dir] for both storage generations and
     * imports whichever is present. The v3 database wins when both exist (it is the live
     * storage); the v2 json is only read when the db is missing or yields nothing usable.
     * When neither file exists the outcome is empty and the UI shows its "not found" hint.
     */
    fun importAuto(dir: File = defaultDir()): Outcome {
        val db = File(dir, "cc-switch.db")
        val json = File(dir, "config.json")

        if (db.isFile) {
            val fromDb = importFromDb(db)
            if (fromDb.imported.isNotEmpty() || !json.isFile) return fromDb
            // v3 db exists but yielded nothing usable — try the v2 json, keeping db skips visible.
            val fromJson = importFromJson(json)
            return Outcome(fromJson.imported, fromDb.skipped + fromJson.skipped, fromJson.source)
        }
        if (json.isFile) return importFromJson(json)
        return Outcome(emptyList(), emptyList(), null)
    }

    // ------------------------------------------------------------- v2 JSON

    fun importFromJson(file: File): Outcome {
        if (!file.isFile) return Outcome.failure(Skipped.Reason.NOT_FOUND, file.absolutePath)
        val root = try {
            JsonParser.parseString(file.readText())
        } catch (e: Exception) {
            LOG.warn("Unparseable cc-switch config: ${file.absolutePath}", e)
            return Outcome.failure(Skipped.Reason.BAD_DATA, file.name)
        }.takeIf { it.isJsonObject }?.asJsonObject
            ?: return Outcome.failure(Skipped.Reason.BAD_DATA, file.name)

        val collected = mutableListOf<ImportedProvider>()
        val skipped = mutableListOf<Skipped>()
        // Tolerant to several v2 shapes: {"claude":{"providers":{...}}} or {"claude":{...}}.
        listOf("claude" to ProviderKind.CLAUDE_CODE, "codex" to ProviderKind.CODEX).forEach { (key, kind) ->
            val section = root.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val providers = section.get("providers")?.takeIf { it.isJsonObject }?.asJsonObject ?: section
            providers.entrySet().forEach { (id, element) ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val nameHint = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                val settings = (obj.get("settingsConfig") ?: obj.get("settings_config"))
                    ?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
                collect(settings, nameHint, id, kind, collected, skipped)
            }
        }
        return Outcome(collected, skipped, file.name)
    }

    // -------------------------------------------------------------- v3 DB

    /** Imports from a cc-switch v3 SQLite database, reading a snapshot copy so cc-switch keeps running. */
    fun importFromDb(dbFile: File): Outcome {
        if (!dbFile.isFile) return Outcome.failure(Skipped.Reason.NOT_FOUND, dbFile.absolutePath)

        val snapshot = copyForRead(dbFile)
        if (snapshot == null) {
            LOG.warn("Cannot copy cc-switch db: ${dbFile.absolutePath}")
            return Outcome.failure(Skipped.Reason.BAD_DATA, dbFile.name)
        }

        try {
            sqliteConnection(snapshot).use { conn ->
                val hasTable = conn.prepareStatement(
                    "select count(*) from sqlite_master where type='table' and name='providers'",
                ).use { it.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 } }
                if (!hasTable) return Outcome.failure(Skipped.Reason.BAD_DATA, dbFile.name)

                val rs = conn.prepareStatement(
                    "select id, app_type, name, settings_config from providers " +
                        "where app_type in ('claude','codex') order by sort_index, name",
                ).executeQuery()

                val collected = mutableListOf<ImportedProvider>()
                val skipped = mutableListOf<Skipped>()
                while (rs.next()) {
                    val id = rs.getString(1) ?: continue
                    val appType = rs.getString(2) ?: continue
                    val kind = when (appType) {
                        "claude" -> ProviderKind.CLAUDE_CODE
                        "codex" -> ProviderKind.CODEX
                        else -> continue
                    }
                    val name = rs.getString(3)?.takeIf { it.isNotBlank() } ?: id
                    val settingsConfig = rs.getString(4) ?: ""
                    val obj = try {
                        JsonParser.parseString(settingsConfig).takeIf { it.isJsonObject }?.asJsonObject
                    } catch (e: Exception) {
                        null
                    }
                    if (obj == null) {
                        skipped += Skipped(name, Skipped.Reason.BAD_DATA)
                    } else {
                        collect(obj, name, id, kind, collected, skipped)
                    }
                }
                return Outcome(collected, skipped, dbFile.name)
            }
        } catch (e: Exception) {
            LOG.warn("Cannot read cc-switch db: ${dbFile.absolutePath}", e)
            return Outcome.failure(Skipped.Reason.BAD_DATA, dbFile.name)
        } finally {
            snapshot.delete()
            File(snapshot.absolutePath + "-wal").delete()
            File(snapshot.absolutePath + "-shm").delete()
        }
    }

    /**
     * Opens the snapshot without going through [java.sql.DriverManager]: DriverManager discovers
     * drivers via the thread-context classloader, which inside an IntelliJ plugin does not see
     * bundled libraries and fails with "No suitable driver". Instantiating the bundled xerial
     * driver directly works in both the plugin and flat-test classloaders.
     */
    private fun sqliteConnection(db: File): Connection {
        val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
        return driver.connect("jdbc:sqlite:${db.absolutePath}", Properties())
            ?: throw SQLException("sqlite driver rejected jdbc:sqlite:${db.absolutePath}")
    }

    /** Copies the db (and any -wal sidecar) to a temp file so we never lock cc-switch's live db. */
    private fun copyForRead(dbFile: File): File? = try {
        val snapshot = File.createTempFile("ccswitch", ".db")
        Files.copy(dbFile.toPath(), snapshot.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val wal = File(dbFile.absolutePath + "-wal")
        if (wal.isFile) {
            Files.copy(wal.toPath(), File(snapshot.absolutePath + "-wal").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        snapshot
    } catch (e: Exception) {
        LOG.debug("Snapshot copy failed for ${dbFile.absolutePath}", e)
        null
    }

    // ------------------------------------------------------ normalization

    /**
     * Turns one cc-switch provider entry into an [ImportedProvider] or records why not.
     * Claude entries carry an `env` map; codex entries carry `auth` (JSON) and `config` (TOML text).
     */
    private fun collect(
        settings: JsonObject,
        nameHint: String?,
        sourceId: String,
        kind: ProviderKind,
        collected: MutableList<ImportedProvider>,
        skipped: MutableList<Skipped>,
    ) {
        val name = (nameHint ?: settings.get("name")?.takeIf { it.isJsonPrimitive }?.asString)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: sourceId

        val env = settings.get("env")?.takeIf { it.isJsonObject }?.asJsonObject
        val auth = settings.get("auth")?.takeIf { it.isJsonObject }?.asJsonObject
        val configToml = settings.get("config")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        when (kind) {
            ProviderKind.CLAUDE_CODE -> {
                val baseUrl = env?.string("ANTHROPIC_BASE_URL")?.trim().orEmpty()
                val apiKey = (env?.string("ANTHROPIC_AUTH_TOKEN") ?: env?.string("ANTHROPIC_API_KEY"))
                    ?.trim().orEmpty()
                if (baseUrl.isEmpty()) {
                    skipped += Skipped(name, Skipped.Reason.NO_BASE_URL)
                    return
                }
                if (apiKey.isEmpty()) {
                    skipped += Skipped(name, Skipped.Reason.NO_CREDENTIALS)
                    return
                }
                val models = claudeModels(env)
                if (models.isEmpty()) {
                    skipped += Skipped(name, Skipped.Reason.NO_MODELS)
                    return
                }
                collected += ImportedProvider(
                    id = "", // assigned by the registry
                    name = name,
                    kind = kind,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    models = models,
                    defaultModel = env?.string("ANTHROPIC_MODEL")?.trim()?.takeIf { it.isNotEmpty() },
                    sourceId = sourceId.takeIf { it.isNotBlank() },
                    api = "anthropic-messages",
                )
            }
            ProviderKind.CODEX -> {
                val apiKey = (auth?.string("OPENAI_API_KEY") ?: env?.string("OPENAI_API_KEY"))
                    ?.trim().orEmpty()
                val baseUrl = (tomlString(configToml, "base_url")
                    ?: auth?.string("OPENAI_BASE_URL") ?: env?.string("OPENAI_BASE_URL"))
                    ?.trim().orEmpty()
                if (baseUrl.isEmpty()) {
                    skipped += Skipped(name, Skipped.Reason.NO_BASE_URL)
                    return
                }
                if (apiKey.isEmpty()) {
                    skipped += Skipped(name, Skipped.Reason.NO_CREDENTIALS)
                    return
                }
                val model = tomlString(configToml, "model")?.trim().orEmpty()
                if (model.isEmpty()) {
                    skipped += Skipped(name, Skipped.Reason.NO_MODELS)
                    return
                }
                val wireApi = tomlString(configToml, "wire_api")?.trim()?.lowercase()
                collected += ImportedProvider(
                    id = "",
                    name = name,
                    kind = kind,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    models = listOf(model),
                    defaultModel = model,
                    sourceId = sourceId.takeIf { it.isNotBlank() },
                    api = if (wireApi == "responses") "openai-responses" else "openai-completions",
                )
            }
        }
    }

    /** Model ids advertised through Claude Code's ANTHROPIC_* env vars, de-duplicated in order. */
    private fun claudeModels(env: JsonObject?): List<String> {
        if (env == null) return emptyList()
        val keys = listOf(
            "ANTHROPIC_MODEL",
            "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_OPUS_MODEL",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL",
            "ANTHROPIC_REASONING_MODEL",
            "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME",
            "ANTHROPIC_DEFAULT_OPUS_MODEL_NAME",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME",
        )
        return keys.mapNotNull { key -> env.string(key)?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
    }

    /** First `key = "value"` (or 'value') assignment anywhere in a small TOML document. */
    internal fun tomlString(toml: String, key: String): String? {
        val regex = Regex("""(?m)^\s*(?:\[.*\]\s*)?$key\s*=\s*["']([^"']+)["']""")
        return regex.find(toml)?.groupValues?.get(1)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
