package dev.pi.gui.packages

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import dev.pi.gui.PiLocator
import java.io.File
import java.util.concurrent.TimeUnit

enum class PackageScope { GLOBAL, PROJECT }

data class InstalledPackage(
    /** Install source exactly as recorded in settings, e.g. `npm:@foo/bar`. */
    val source: String,
    val scope: PackageScope,
    /** Resource counts declared by an object-form entry; null when the entry is a bare string. */
    val extensions: List<String>?,
    val skills: List<String>?,
    val prompts: List<String>?,
    val themes: List<String>?,
) {
    val displayName: String
        get() = source.substringAfter("npm:").substringAfter("git:").trim().ifEmpty { source }
}

data class PackageCommandResult(val success: Boolean, val output: String)

/**
 * Reads pi's installed packages and drives `pi install` / `pi remove`.
 *
 * The list is read straight from settings rather than by shelling out, so opening the panel is
 * instant and works even when the CLI is momentarily unavailable. `pi install` remains the only
 * thing that *writes*, so the CLI stays the single source of truth for install mechanics.
 */
object PackagesService {

    private val LOG = Logger.getInstance(PackagesService::class.java)

    fun globalSettingsFile(): File = File(PiLocator.agentDir(), "settings.json")

    fun projectSettingsFile(projectPath: String?): File? =
        projectPath?.takeIf { it.isNotBlank() }?.let { File(it, ".pi/settings.json") }

    fun listInstalled(projectPath: String?): List<InstalledPackage> {
        val result = mutableListOf<InstalledPackage>()
        result += read(globalSettingsFile(), PackageScope.GLOBAL)
        projectSettingsFile(projectPath)?.let { result += read(it, PackageScope.PROJECT) }
        return result
    }

    private fun read(settingsFile: File, scope: PackageScope): List<InstalledPackage> {
        if (!settingsFile.isFile) return emptyList()
        return try {
            val root = JsonParser.parseString(settingsFile.readText())
                .takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            val packages = root.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()

            packages.mapNotNull { element ->
                when {
                    // Bare string form: just the source.
                    element.isJsonPrimitive -> InstalledPackage(
                        source = element.asString,
                        scope = scope,
                        extensions = null, skills = null, prompts = null, themes = null,
                    )
                    // Object form: source plus explicit resource filters.
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        val source = obj.get("source")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: return@mapNotNull null
                        InstalledPackage(
                            source = source,
                            scope = scope,
                            extensions = stringList(obj, "extensions"),
                            skills = stringList(obj, "skills"),
                            prompts = stringList(obj, "prompts"),
                            themes = stringList(obj, "themes"),
                        )
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            LOG.warn("Cannot read packages from ${settingsFile.absolutePath}", e)
            emptyList()
        }
    }

    private fun stringList(obj: com.google.gson.JsonObject, key: String): List<String>? =
        obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }

    /** Totals for the footer line, mirroring pi's own "N ext · N skills · …" summary. */
    fun summarize(packages: List<InstalledPackage>): ResourceCounts {
        // A bare-string entry autoloads everything it ships, so its contents are unknown here;
        // only explicitly filtered entries can be counted.
        var extensions = 0; var skills = 0; var prompts = 0; var themes = 0
        packages.forEach {
            extensions += it.extensions?.size ?: 0
            skills += it.skills?.size ?: 0
            prompts += it.prompts?.size ?: 0
            themes += it.themes?.size ?: 0
        }
        return ResourceCounts(packages.size, extensions, skills, prompts, themes)
    }

    data class ResourceCounts(
        val packages: Int,
        val extensions: Int,
        val skills: Int,
        val prompts: Int,
        val themes: Int,
    )

    fun install(source: String, scope: PackageScope, projectPath: String?): PackageCommandResult =
        runPi(listOf("install", source.trim()), scope, projectPath)

    fun remove(source: String, scope: PackageScope, projectPath: String?): PackageCommandResult =
        runPi(listOf("remove", source.trim()), scope, projectPath)

    private fun runPi(
        args: List<String>,
        scope: PackageScope,
        projectPath: String?,
    ): PackageCommandResult {
        val executable = PiLocator.findPi()
            ?: return PackageCommandResult(false, "pi executable not found")

        val command = (listOf(executable.absolutePath) + args).toMutableList()
        // `-l` is what makes pi write to the project's .pi/settings.json instead of the global one.
        if (scope == PackageScope.PROJECT) command.add("-l")

        val workingDir = when (scope) {
            PackageScope.PROJECT -> projectPath?.let(::File)?.takeIf { it.isDirectory }
                ?: return PackageCommandResult(false, "Project directory is not available")
            PackageScope.GLOBAL -> File(System.getProperty("user.home"))
        }

        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .also { it.environment().putAll(PiLocator.shellEnvironment()) }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return PackageCommandResult(false, "Timed out after 5 minutes\n$output")
            }
            PackageCommandResult(process.exitValue() == 0, output.trim())
        } catch (e: Exception) {
            LOG.warn("pi ${args.joinToString(" ")} failed", e)
            PackageCommandResult(false, e.message ?: "Command failed")
        }
    }
}
