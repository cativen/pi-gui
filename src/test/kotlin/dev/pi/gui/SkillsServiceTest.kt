package dev.pi.gui

import dev.pi.gui.skills.SkillScope
import dev.pi.gui.skills.SkillsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillsServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun skill(name: String, content: String): File {
        val dir = File(tmp.root, "skills/$name").apply { mkdirs() }
        return File(dir, "SKILL.md").apply { writeText(content) }
    }

    private val sample = """
        ---
        name: find-skills
        description: Helps users discover and install agent skills.
        ---

        # Find Skills

        Body text.
    """.trimIndent()

    // ------------------------------------------------------------ parsing

    @Test
    fun `frontmatter keys are parsed`() {
        val fm = SkillsService.parseFrontmatter(sample)
        assertEquals("find-skills", fm["name"])
        assertEquals("Helps users discover and install agent skills.", fm["description"])
    }

    @Test
    fun `wrapped description lines are joined`() {
        val fm = SkillsService.parseFrontmatter(
            "---\nname: x\ndescription: first part\n  second part\n---\nbody"
        )
        assertEquals("first part second part", fm["description"])
    }

    @Test
    fun `quotes around values are stripped`() {
        val fm = SkillsService.parseFrontmatter("---\nname: \"quoted\"\n---\nbody")
        assertEquals("quoted", fm["name"])
    }

    @Test
    fun `a document without frontmatter yields nothing`() {
        assertTrue(SkillsService.parseFrontmatter("# Just a heading").isEmpty())
    }

    @Test
    fun `skill is enabled when the disable key is absent`() {
        val info = SkillsService.read(skill("find-skills", sample), SkillScope.GLOBAL)!!
        assertEquals("find-skills", info.name)
        assertTrue(info.enabled)
        assertEquals(SkillScope.GLOBAL, info.scope)
    }

    @Test
    fun `skill is disabled when the key is true`() {
        val file = skill("x", "---\nname: x\ndisable-model-invocation: true\n---\nbody")
        assertFalse(SkillsService.read(file, SkillScope.GLOBAL)!!.enabled)
    }

    @Test
    fun `an explicit false keeps the skill enabled`() {
        val file = skill("x", "---\nname: x\ndisable-model-invocation: false\n---\nbody")
        assertTrue(SkillsService.read(file, SkillScope.GLOBAL)!!.enabled)
    }

    @Test
    fun `directory name is the fallback when frontmatter has no name`() {
        val file = skill("fallback-name", "---\ndescription: no name here\n---\nbody")
        assertEquals("fallback-name", SkillsService.read(file, SkillScope.GLOBAL)!!.name)
    }

    @Test
    fun `a missing file reads as null`() {
        assertNull(SkillsService.read(File(tmp.root, "nope/SKILL.md"), SkillScope.GLOBAL))
    }

    // ------------------------------------------------------------ toggling

    /** The whole point of the surgical edit: the author's body must survive untouched. */
    @Test
    fun `disabling adds the key and preserves the body`() {
        val file = skill("find-skills", sample)
        val info = SkillsService.read(file, SkillScope.GLOBAL)!!
        assertTrue(SkillsService.setEnabled(info, false))

        val updated = file.readText()
        assertTrue(updated, updated.contains("disable-model-invocation: true"))
        assertTrue("body must be preserved", updated.contains("# Find Skills"))
        assertTrue("body must be preserved", updated.contains("Body text."))
        assertTrue("name must be preserved", updated.contains("name: find-skills"))
        assertFalse(SkillsService.read(file, SkillScope.GLOBAL)!!.enabled)
    }

    @Test
    fun `enabling removes the key again`() {
        val file = skill("x", "---\nname: x\ndisable-model-invocation: true\n---\n\nbody\n")
        val disabled = SkillsService.read(file, SkillScope.GLOBAL)!!
        assertTrue(SkillsService.setEnabled(disabled, true))

        val updated = file.readText()
        assertFalse(updated, updated.contains("disable-model-invocation"))
        assertTrue(updated.contains("name: x"))
        assertTrue(SkillsService.read(file, SkillScope.GLOBAL)!!.enabled)
    }

    @Test
    fun `toggling round-trips back to the original document`() {
        val file = skill("find-skills", sample)
        val original = file.readText()
        val info = SkillsService.read(file, SkillScope.GLOBAL)!!

        SkillsService.setEnabled(info, false)
        val disabled = SkillsService.read(file, SkillScope.GLOBAL)!!
        SkillsService.setEnabled(disabled, true)

        assertEquals("round trip must restore the file byte for byte", original, file.readText())
    }

    @Test
    fun `an existing false value is flipped rather than duplicated`() {
        val file = skill("x", "---\nname: x\ndisable-model-invocation: false\n---\nbody")
        SkillsService.setEnabled(SkillsService.read(file, SkillScope.GLOBAL)!!, false)
        val updated = file.readText()
        assertEquals(1, Regex("disable-model-invocation").findAll(updated).count())
        assertTrue(updated.contains("disable-model-invocation: true"))
    }

    @Test
    fun `crlf documents keep their line endings`() {
        val file = skill("x", "---\r\nname: x\r\n---\r\nbody\r\n")
        SkillsService.setEnabled(SkillsService.read(file, SkillScope.GLOBAL)!!, false)
        val updated = file.readText()
        assertTrue("CRLF must be preserved", updated.contains("\r\n"))
        assertFalse("must not introduce bare LF", updated.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `a document without frontmatter is left alone`() {
        assertNull(SkillsService.rewriteFrontmatter("# no frontmatter", false))
    }

    // ------------------------------------------------------------- listing

    @Test
    fun `listing skips directories without a SKILL file`() {
        skill("real", sample)
        File(tmp.root, "skills/empty-dir").mkdirs()

        val root = File(tmp.root, "skills")
        val found = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { SkillsService.read(File(it, "SKILL.md"), SkillScope.GLOBAL) }
        assertEquals(1, found.size)
        assertEquals("find-skills", found.first().name)
    }

    @Test
    fun `project skills directory follows the agents convention`() {
        val dir = SkillsService.projectSkillsDir("/tmp/proj")
        assertEquals(File("/tmp/proj/.agents/skills"), dir)
        assertNull(SkillsService.projectSkillsDir(null))
        assertNull(SkillsService.projectSkillsDir(""))
    }

    @Test
    fun `home directory is abbreviated in the displayed path`() {
        val home = System.getProperty("user.home")
        val info = SkillsService.read(skill("x", sample), SkillScope.GLOBAL)!!
            .copy(filePath = "$home/.agents/skills/x/SKILL.md")
        assertEquals("~/.agents/skills/x/SKILL.md", info.displayPath())
    }
}
