package dev.pi.gui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.EnvironmentUtil
import dev.pi.gui.settings.PiSettings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds the `pi` executable.
 *
 * IDEs launched from Finder/Dock on macOS do not inherit the login shell `PATH`, so a plain
 * `ProcessBuilder("pi")` fails for anyone using nvm/fnm/volta. [EnvironmentUtil] gives us the
 * shell environment the user actually has, and we fall back to scanning well-known install roots.
 */
object PiLocator {
    private val LOG = Logger.getInstance(PiLocator::class.java)

    private val COMMON_DIRS = listOf(
        "/usr/local/bin",
        "/opt/homebrew/bin",
        "/usr/bin",
    )

    /**
     * Minimal shell environment for child processes.
     *
     * Do not call `EnvironmentUtil.getEnvironmentMap()`: it reads every environment variable,
     * including unrelated secrets. We fetch only operational variables plus the three documented
     * AI credential variables after explicit consent. Before consent, blank entries deliberately
     * override credentials inherited by [ProcessBuilder].
     */
    fun shellEnvironment(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        SAFE_ENV_KEYS.forEach { key -> environmentValue(key)?.let { result[key] = it } }
        if (PiSettings.getInstance().credentialAccessGranted) {
            AI_CREDENTIAL_KEYS.forEach { key -> environmentValue(key)?.let { result[key] = it } }
        } else {
            AI_CREDENTIAL_KEYS.forEach { key -> result[key] = "" }
        }
        return result
    }

    private fun environmentValue(name: String): String? = try {
        EnvironmentUtil.getValue(name)
    } catch (e: Throwable) {
        LOG.warn("Falling back to the JVM value for environment variable $name", e)
        System.getenv(name)
    }

    /**
     * Resolve the pi executable, or null when it cannot be found.
     * An explicit path in settings always wins.
     */
    fun findPi(): File? {
        PiSettings.getInstance().piPath.trim().takeIf { it.isNotEmpty() }?.let { configured ->
            val f = File(configured)
            return if (f.canExecute()) f else null
        }
        return discover()
    }

    /** Best-effort discovery, ignoring the configured override. */
    fun discover(): File? {
        val env = shellEnvironment()

        env["PATH"]?.split(File.pathSeparatorChar)?.forEach { dir ->
            candidate(dir)?.let { return it }
        }

        COMMON_DIRS.forEach { dir -> candidate(dir)?.let { return it } }

        // nvm / fnm keep one bin dir per installed node version.
        val home = System.getProperty("user.home")
        listOf(
            File(home, ".nvm/versions/node"),
            File(home, ".local/share/fnm/node-versions"),
            File(home, "Library/Application Support/fnm/node-versions"),
            File(home, ".volta/bin").parentFile,
        ).forEach { root ->
            if (root != null && root.isDirectory) {
                root.listFiles()?.sortedByDescending { it.name }?.forEach { versionDir ->
                    candidate(File(versionDir, "bin").path)?.let { return it }
                    candidate(File(versionDir, "installation/bin").path)?.let { return it }
                }
            }
        }
        candidate(File(home, ".volta/bin").path)?.let { return it }

        // Last resort: ask a login shell.
        return askLoginShell()
    }

    private fun candidate(dir: String): File? {
        if (dir.isBlank()) return null
        val f = File(dir, if (isWindows()) "pi.cmd" else "pi")
        if (f.isFile && f.canExecute()) return f
        if (isWindows()) {
            val exe = File(dir, "pi.exe")
            if (exe.isFile) return exe
        }
        return null
    }

    private fun askLoginShell(): File? {
        if (isWindows()) return null
        val shell = System.getenv("SHELL") ?: "/bin/sh"
        return try {
            val builder = ProcessBuilder(shell, "-lc", "command -v pi")
                .redirectErrorStream(false)
            if (!PiSettings.getInstance().credentialAccessGranted) {
                AI_CREDENTIAL_KEYS.forEach { builder.environment()[it] = "" }
            }
            val proc = builder.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            out.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
                ?.let { File(it) }
                ?.takeIf { it.canExecute() }
        } catch (e: Exception) {
            LOG.debug("Login-shell lookup for pi failed", e)
            null
        }
    }

    fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /** Directory pi stores its agent data in, honoring `PI_CODING_AGENT_DIR`. */
    fun agentDir(): File {
        shellEnvironment()["PI_CODING_AGENT_DIR"]?.takeIf { it.isNotBlank() }?.let {
            return File(expandTilde(it))
        }
        return File(System.getProperty("user.home"), ".pi/agent")
    }

    fun sessionsDir(): File = File(agentDir(), "sessions")

    private fun expandTilde(path: String): String =
        if (path.startsWith("~")) System.getProperty("user.home") + path.substring(1) else path

    private val SAFE_ENV_KEYS = listOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE",
        "TMPDIR", "TMP", "TEMP", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
        "USERPROFILE", "APPDATA", "LOCALAPPDATA", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT",
        "NODE_PATH", "NVM_DIR", "FNM_DIR", "VOLTA_HOME", "PI_CODING_AGENT_DIR",
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
    )

    internal val AI_CREDENTIAL_KEYS = listOf(
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
    )
}
