package dev.pi.gui.skills

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import dev.pi.gui.PiLocator
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

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

/** Thrown when the registry does not answer within the hard deadline (see [SkillsRegistry.search]). */
class SearchTimeoutException(message: String) : IOException(message)

/**
 * Talks to the skills.sh registry and installs skills through the `skills` CLI.
 */
object SkillsRegistry {

    private val LOG = Logger.getInstance(SkillsRegistry::class.java)
    private const val BASE_URL = "https://skills.sh"

    /**
     * The wall-clock ceiling for one search. Socket timeouts alone cannot bound the request:
     * when the IDE proxy is set to "auto-detect" or points at a port nothing is serving, the
     * stall happens inside proxy negotiation before any socket timeout starts counting. Only a
     * hard deadline guarantees the UI never sticks on "searching".
     */
    private const val SEARCH_DEADLINE_MS = 20_000L

    /** Daemon threads: one may stay wedged on an uninterruptible proxy read; it must never
     * keep the IDE from exiting. */
    private val searchPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pi-skills-search").apply { isDaemon = true }
    }
    private val installOutputPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pi-skills-install-output").apply { isDaemon = true }
    }

    /**
     * Searches the registry. The first attempt honors the IDE's proxy configuration ([HttpRequests]);
     * if it stalls or fails, a direct connection is tried once — a leftover proxy setting must not
     * make search unusable on a machine that can reach the registry directly.
     */
    @Throws(Exception::class)
    fun search(query: String, limit: Int = 30): List<SkillSearchResult> {
        if (query.isBlank()) return emptyList()
        val url = "$BASE_URL/api/search?q=${encode(query)}&limit=${limit.coerceIn(1, 50)}"
        return searchUrl(url, SEARCH_DEADLINE_MS)
    }

    /** Visible for tests: pointed at a local server to exercise the deadline watchdog. */
    internal fun searchUrl(url: String, deadlineMs: Long): List<SkillSearchResult> {
        val perAttempt = deadlineMs / 2
        val viaProxy = runCatching { fetchViaIdeProxy(url, perAttempt) }
        if (viaProxy.isSuccess) return parse(viaProxy.getOrThrow())

        LOG.debug("skills.sh search via IDE proxy failed, retrying directly", viaProxy.exceptionOrNull())
        return try {
            parse(fetchDirect(url, perAttempt))
        } catch (direct: SearchTimeoutException) {
            // The proxy attempt carries the more informative cause (it went first).
            throw viaProxy.exceptionOrNull() as? SearchTimeoutException ?: direct
        }
    }

    /** The IDE-proxy route, bounded by a hard deadline independent of any socket timeout. */
    private fun fetchViaIdeProxy(url: String, deadlineMs: Long): String {
        val connection = AtomicReference<java.net.URLConnection?>()
        val future = searchPool.submit(
            Callable<String> {
                // The Accept header matters: without it the endpoint stalls instead of answering.
                HttpRequests.request(url)
                    .accept("application/json")
                    .connectTimeout(10_000)
                    .readTimeout(10_000)
                    .connect { request ->
                        connection.set(request.connection)
                        request.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
            }
        )
        return awaitBody(future, connection, deadlineMs)
    }

    /** The direct route for when the IDE proxy is misconfigured or dead. */
    private fun fetchDirect(url: String, deadlineMs: Long): String {
        val connection = AtomicReference<java.net.HttpURLConnection?>()
        val future = searchPool.submit(
            Callable<String> {
                val conn = java.net.URI(url).toURL().openConnection(java.net.Proxy.NO_PROXY)
                    as java.net.HttpURLConnection
                connection.set(conn)
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/json")
                try {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
        )
        return awaitBody(future, connection, deadlineMs)
    }

    /** Waits for [future] under a hard deadline; on expiry force-closes [connection] so the
     * worker thread is released instead of leaking, and reports a timeout the UI can phrase. */
    private fun awaitBody(
        future: java.util.concurrent.Future<String>,
        connection: AtomicReference<out java.net.URLConnection?>,
        deadlineMs: Long,
    ): String = try {
        future.get(deadlineMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        runCatching { (connection.get() as? java.net.HttpURLConnection)?.disconnect() }
        future.cancel(true)
        throw SearchTimeoutException("skills.sh did not answer within ${deadlineMs / 1000}s")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /** Visible for tests. */
    internal fun parse(body: String): List<SkillSearchResult> {
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
     * Installs one result from skills.sh.
     *
     * Registry ids have the shape `owner/repository/skill-name`, while the CLI requires the
     * repository and skill name as separate arguments. Passing the full id as the package makes
     * the CLI clone a non-skill subdirectory and end with "No valid skills found".
     */
    fun install(source: String, skillName: String, scope: SkillScope, projectPath: String?): InstallOutcome {
        val command = buildInstallCommand(npxExecutable(), source, skillName, scope)
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

            // Drain concurrently: waiting until after waitFor can deadlock once the pipe fills.
            val outputFuture = installOutputPool.submit(Callable {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            })
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                val output = runCatching { outputFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
                return InstallOutcome(false, sanitizeCliOutput("Timed out after 5 minutes\n$output"))
            }
            val output = runCatching { outputFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            InstallOutcome(process.exitValue() == 0, sanitizeCliOutput(output))
        } catch (e: Exception) {
            LOG.warn("skills install failed for $source --skill $skillName", e)
            InstallOutcome(false, e.message ?: "Install failed")
        }
    }

    internal fun buildInstallCommand(
        npx: String,
        source: String,
        skillName: String,
        scope: SkillScope,
    ): List<String> = buildList {
        // The first --yes belongs to npx (and must precede the package name), so a clean user
        // machine never blocks on npx's package-install prompt. The trailing -y belongs to skills.
        addAll(listOf(npx, "--yes", "skills", "add", source, "--skill", skillName, "--agent", "pi"))
        if (scope == SkillScope.GLOBAL) add("--global")
        addAll(listOf("--copy", "--json", "-y"))
    }

    /** Removes cursor controls/spinners so errors remain readable in the JCEF settings panel. */
    internal fun sanitizeCliOutput(raw: String): String {
        val withProgressBreaks = raw.replace(Regex("\\u001B\\[[0-9;?]*[GJKH]"), "\n")
        val withoutAnsi = withProgressBreaks
            .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)"), "")
            .replace('\r', '\n')
        val progress = Regex("^[│◇◆●○◐◓◑◒\\s]*(Cloning repository|Discovering skills|Installing).*$", RegexOption.IGNORE_CASE)
        val lines = withoutAnsi.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !progress.matches(it.trim()) }
            .fold(mutableListOf<String>()) { out, line ->
                if (out.lastOrNull() != line) out += line
                out
            }
        return lines.joinToString("\n").trim().takeLast(4_000)
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
