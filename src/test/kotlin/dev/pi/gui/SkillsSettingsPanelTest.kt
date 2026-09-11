package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.skills.SkillScope
import dev.pi.gui.skills.SkillsService
import dev.pi.gui.ui.settings.SkillsSettingsPanel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The skills page with a per-row switch: flipping the switch must rewrite the skill's
 * frontmatter on disk (the same key pi itself reads) and settle in the new state.
 */
class SkillsSettingsPanelTest : BasePlatformTestCase() {

    private lateinit var skillsRoot: File

    override fun setUp() {
        super.setUp()
        skillsRoot = File.createTempFile("pi-skills", "").let { dir ->
            dir.delete()
            dir.mkdirs()
            dir
        }
    }

    override fun tearDown() {
        try {
            skillsRoot.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun writeSkill(name: String, extra: String = ""): File {
        val dir = File(skillsRoot, name).apply { mkdirs() }
        return File(dir, "SKILL.md").apply {
            writeText(
                """
                ---
                name: $name
                description: Test skill $name.
                $extra
                ---

                # $name

                Body text.
                """.trimIndent() + "\n"
            )
        }
    }

    /** Builds the panel against the temporary directory and waits for the async first load. */
    private fun newPanel(vararg files: File): SkillsSettingsPanel {
        val paths = files.toList()
        val panel = SkillsSettingsPanel(project) {
            paths.mapNotNull { SkillsService.read(it, SkillScope.GLOBAL) }
        }
        assertTrue(
            "the first load must complete",
            waitFor { panel.skillNamesForTest().isNotEmpty() },
        )
        return panel
    }

    /** Pumps pending events until [condition] holds or two seconds pass; reload() is async. */
    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    fun testRowsShowOneSwitchPerSkill() {
        val a = writeSkill("alpha")
        val b = writeSkill("beta")
        val panel = newPanel(a, b)

        assertEquals(setOf("alpha", "beta"), panel.skillNamesForTest().toSet())
        assertNotNull(panel.toggleForTest("alpha"))
        assertNotNull(panel.toggleForTest("beta"))
    }

    fun testFlippingTheRowSwitchDisablesTheSkillOnDisk() {
        val file = writeSkill("gamma")
        val panel = newPanel(file)
        val toggle = panel.toggleForTest("gamma")!!
        assertTrue("starts enabled", toggle.isSelected)

        toggle.doClick() // off

        val text = file.readText()
        assertTrue("frontmatter must carry the disable key", text.contains("disable-model-invocation: true"))
        assertTrue("body must survive", text.contains("Body text."))
        assertFalse("switch settles off", toggle.isSelected)

        toggle.doClick() // on again

        assertFalse("key removed again", file.readText().contains("disable-model-invocation"))
        assertTrue("switch settles on", toggle.isSelected)
    }
}
