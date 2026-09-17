package dev.pi.gui

import dev.pi.gui.skills.SkillScope
import dev.pi.gui.skills.SkillsRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsRegistryInstallTest {

    @Test
    fun `registry id is split into repository and selected skill`() {
        val command = SkillsRegistry.buildInstallCommand(
            "npx",
            "better-auth/skills",
            "email-and-password-best-practices",
            SkillScope.PROJECT,
        )

        assertTrue(command.windowed(2).contains(listOf("add", "better-auth/skills")))
        assertTrue(command.take(3) == listOf("npx", "--yes", "skills"))
        assertTrue(command.windowed(2).contains(listOf("--skill", "email-and-password-best-practices")))
        assertTrue(command.windowed(2).contains(listOf("--agent", "pi")))
        assertTrue(command.containsAll(listOf("--copy", "--json", "-y")))
        assertFalse(command.contains("better-auth/skills/email-and-password-best-practices"))
        assertFalse(command.contains("--global"))
    }

    @Test
    fun `global install explicitly asks the cli for global scope`() {
        val command = SkillsRegistry.buildInstallCommand("npx", "owner/repo", "skill", SkillScope.GLOBAL)
        assertTrue(command.contains("--global"))
    }

    @Test
    fun `terminal controls and repeated progress are removed from errors`() {
        val raw = "\u001B[1G\u001B[J◐ Cloning repository…" +
            "\u001B[1G\u001B[J◓ Cloning repository…\u001B[?25h\n" +
            "No valid skills found. Skills require a SKILL.md with name and description."

        val clean = SkillsRegistry.sanitizeCliOutput(raw)

        assertFalse(clean.contains("\u001B"))
        assertFalse(clean.contains("Cloning repository"))
        assertTrue(clean.contains("No valid skills found"))
    }
}
