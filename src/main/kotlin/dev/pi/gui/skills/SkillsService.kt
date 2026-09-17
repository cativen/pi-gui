package dev.pi.gui.skills

import com.intellij.openapi.diagnostic.Logger
import dev.pi.gui.PiLocator
import java.io.File

enum class SkillScope { GLOBAL, PROJECT }

data class SkillInfo(
    val name: String,
    val description: String,
    /** Absolute path of the skill's SKILL.md. */
    val filePath: String,
    val scope: SkillScope,
    val enabled: Boolean,
) {
    /** Path shown in the detail header, with the home directory abbreviated. */
    fun displayPath(): String {
        val home = System.getProperty("user.home")
        return if (filePath.startsWith(home)) "~" + filePath.removePrefix(home) else filePath
    }
}

/**
 * Reads and toggles agent skills on disk.
 *
 * Skills live as `<root>/skills/<name>/SKILL.md` with YAML frontmatter. A skill is enabled unless
 * its frontmatter carries `disable-model-invocation: true`, which is the same key pi itself reads,
 * so toggling here is picked up by the CLI and by other pi front-ends.
 */
object SkillsService {

    private val LOG = Logger.getInstance(SkillsService::class.java)
    private const val DISABLE_KEY = "disable-model-invocation"

    fun globalSkillsDir(): File = File(System.getProperty("user.home"), ".agents/skills")

    /** Native Pi location used by `npx skills add --agent pi --global`. */
    fun piGlobalSkillsDir(): File = File(PiLocator.agentDir(), "skills")

    fun projectSkillsDir(projectPath: String?): File? =
        projectPath?.takeIf { it.isNotBlank() }?.let { File(it, ".agents/skills") }

    /** Native Pi location used by `npx skills add --agent pi` in a project. */
    fun piProjectSkillsDir(projectPath: String?): File? =
        projectPath?.takeIf { it.isNotBlank() }?.let { File(it, ".pi/skills") }

    /** All skills visible to the agent: global first, then any the project adds. */
    fun listSkills(projectPath: String?): List<SkillInfo> {
        val skills = mutableListOf<SkillInfo>()
        skills += scan(globalSkillsDir(), SkillScope.GLOBAL)
        skills += scan(piGlobalSkillsDir(), SkillScope.GLOBAL)
        projectSkillsDir(projectPath)?.let { skills += scan(it, SkillScope.PROJECT) }
        piProjectSkillsDir(projectPath)?.let { skills += scan(it, SkillScope.PROJECT) }
        return skills.sortedWith(compareBy({ it.scope }, { it.name.lowercase() }))
    }

    private fun scan(root: File, scope: SkillScope): List<SkillInfo> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> read(File(dir, "SKILL.md"), scope, fallbackName = dir.name) }
            .sortedBy { it.name.lowercase() }
    }

    fun read(skillFile: File, scope: SkillScope, fallbackName: String = skillFile.parentFile?.name ?: "skill"): SkillInfo? {
        if (!skillFile.isFile) return null
        val text = try { skillFile.readText() } catch (e: Exception) {
            LOG.debug("Unreadable skill: ${skillFile.absolutePath}", e)
            return null
        }
        val frontmatter = parseFrontmatter(text)
        return SkillInfo(
            name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = frontmatter["description"].orEmpty(),
            filePath = skillFile.absolutePath,
            scope = scope,
            enabled = !isTruthy(frontmatter[DISABLE_KEY]),
        )
    }

    private fun isTruthy(value: String?): Boolean =
        value?.trim()?.lowercase() in setOf("true", "yes", "1")

    /**
     * Extracts the leading `---` fenced block as key/value pairs.
     * Only the flat scalar subset skills actually use is supported.
     */
    fun parseFrontmatter(text: String): Map<String, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return emptyMap()
        val end = normalized.indexOf("\n---", 3)
        if (end < 0) return emptyMap()

        val body = normalized.substring(normalized.indexOf('\n', 3) + 1, end)
        val result = LinkedHashMap<String, String>()
        var currentKey: String? = null
        val buffer = StringBuilder()

        fun flush() {
            currentKey?.let { result[it] = buffer.toString().trim().trim('"', '\'') }
            buffer.setLength(0)
        }

        body.split("\n").forEach { line ->
            val match = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(line)
            if (match != null) {
                flush()
                currentKey = match.groupValues[1]
                buffer.append(match.groupValues[2])
            } else if (currentKey != null && line.isNotBlank()) {
                // Continuation of a wrapped scalar.
                buffer.append(' ').append(line.trim())
            }
        }
        flush()
        return result
    }

    /**
     * Enables or disables a skill by touching only the `disable-model-invocation` key, leaving the
     * rest of the author's formatting untouched. Enabling removes the key entirely, which is the
     * file's natural "enabled" state.
     */
    fun setEnabled(skill: SkillInfo, enabled: Boolean): Boolean {
        val file = File(skill.filePath)
        if (!file.isFile) return false

        val original = try { file.readText() } catch (e: Exception) {
            LOG.warn("Cannot read ${file.absolutePath}", e)
            return false
        }
        val updated = rewriteFrontmatter(original, enabled) ?: return false
        if (updated == original) return true

        return try {
            file.writeText(updated)
            true
        } catch (e: Exception) {
            LOG.warn("Cannot write ${file.absolutePath}", e)
            false
        }
    }

    /** Returns the rewritten document, or null when there is no frontmatter block to edit. */
    fun rewriteFrontmatter(text: String, enabled: Boolean): String? {
        val usesCrLf = text.contains("\r\n")
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return null

        val firstNewline = normalized.indexOf('\n', 3)
        if (firstNewline < 0) return null
        val end = normalized.indexOf("\n---", firstNewline)
        if (end < 0) return null

        val header = normalized.substring(0, firstNewline + 1)
        val body = normalized.substring(firstNewline + 1, end)
        val rest = normalized.substring(end)

        val lines = body.split("\n").toMutableList()
        val existingIndex = lines.indexOfFirst {
            Regex("^\\s*$DISABLE_KEY\\s*:").containsMatchIn(it)
        }

        if (enabled) {
            if (existingIndex >= 0) lines.removeAt(existingIndex)
        } else {
            if (existingIndex >= 0) lines[existingIndex] = "$DISABLE_KEY: true"
            else lines.add("$DISABLE_KEY: true")
        }

        val rebuilt = header + lines.joinToString("\n") + rest
        return if (usesCrLf) rebuilt.replace("\n", "\r\n") else rebuilt
    }
}
