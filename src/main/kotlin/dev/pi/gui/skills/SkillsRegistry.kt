package dev.pi.gui.skills

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import dev.pi.gui.PiLocator
import java.io.File
import java.util.concurrent.TimeUnit

data class SkillSearchResult(
    /** Full install identifier, e.g. `anthropics/skills/pdf`. */
    val id: String,
    val name: String,
    val source: String,
    val installs: Long,
) {
    fun installsLabel(): String = when {
        installs >= 1_000_000 -> String.format("%.1fM installs", installs / 1_000_000.0)
        installs >= 1_000 -> String.format("%.1fK installs", installs / 1_000.0)
        installs > 0 -> "$installs installs"
        else -> ""
    }
}

data class InstallOutcome(val success: Boolean, val output: String)

/**
 * Talks to the skills.sh registry and installs skills through the `skills` CLI.
 */
object SkillsRegistry {

    private val LOG = Logger.getInstance(SkillsRegistry::class.java)
    private const val BASE_URL = "https://skills.sh"

    /**
     * Searches the registry. Uses [HttpRequests] so the IDE's proxy configuration is honored —
     * a plain `HttpURLConnection` would bypass it.
     */
    @Throws(Exception::class)
    fun search(query: String, limit: Int = 30): List<SkillSearchResult> {
        if (query.isBlank()) return emptyList()
        val url = "$BASE_URL/api/search?q=${encode(query)}&limit=${limit.coerceIn(1, 50)}"

        // The Accept header matters: without it the endpoint stalls instead of answering.
        // Timeouts are generous because the first HTTPS request from a cold JVM pays for DNS,
        // TLS and proxy detection, which alone can take longer than a typical read timeout.
        val body = HttpRequests.request(url)
            .accept("application/json")
            .connectTimeout(20_000)
            .readTimeout(30_000)
            .readString()

        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val skills = root.get("skills")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        return skills.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.get("id")?.asStringOrNull() ?: return@mapNotNull null
            SkillSearchResult(
                id = id,
                name = obj.get("name")?.asStringOrNull() ?: obj.get("skillId")?.asStringOrNull() ?: id,
                source = obj.get("source")?.asStringOrNull() ?: "",
                installs = obj.get("installs")?.let { if (it.isJsonPrimitive) it.asLong else 0L } ?: 0L,
            )
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * Installs a skill via `npx skills add <id> -y --agent pi`.
     *
     * A project-scoped install runs with the project as the working directory, which is what makes
     * the CLI drop the skill into `<project>/.agents/skills` rather than the global directory.
     */
    fun install(id: String, scope: SkillScope, projectPath: String?): InstallOutcome {
        val command = mutableListOf(npxExecutable(), "skills", "add", id, "-y", "--agent", "pi")
        val workingDir = when (scope) {
            SkillScope.PROJECT -> projectPath?.let(::File)?.takeIf { it.isDirectory }
                ?: return InstallOutcome(false, "Project directory is not available")
            SkillScope.GLOBAL -> File(System.getProperty("user.home"))
        }

        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .also { it.environment().putAll(PiLocator.shellEnvironment()) }
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return InstallOutcome(false, "Timed out after 5 minutes\n$output")
            }
            InstallOutcome(process.exitValue() == 0, output.trim())
        } catch (e: Exception) {
            LOG.warn("skills install failed for $id", e)
            InstallOutcome(false, e.message ?: "Install failed")
        }
    }

    /** `npx` sits next to the `node` that pi itself uses, so reuse the discovered PATH. */
    private fun npxExecutable(): String {
        val name = if (PiLocator.isWindows()) "npx.cmd" else "npx"
        PiLocator.shellEnvironment()["PATH"]?.split(File.pathSeparatorChar)?.forEach { dir ->
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return name
    }
}

private fun com.google.gson.JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive) asString else null
