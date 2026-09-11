package dev.pi.gui

import dev.pi.gui.packages.PackageScope
import dev.pi.gui.packages.PackagesRegistry
import dev.pi.gui.packages.PackagesService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------- registry parsing

    /** Parsed against markup captured from the live site, so the selectors are real. */
    private fun sampleHtml(): String =
        PackagesTest::class.java.getResourceAsStream("/pidev-packages-sample.html")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `real pi_dev markup yields packages`() {
        val results = PackagesRegistry.parse(sampleHtml())
        assertTrue("expected a full page of results, got ${results.size}", results.size >= 20)

        val adapter = results.firstOrNull { it.name == "pi-mcp-adapter" }
        requireNotNull(adapter) { "known package missing from parse" }
        assertEquals("extension", adapter.types)
        assertTrue(adapter.downloads > 100_000)
        assertTrue(adapter.description, adapter.description.contains("MCP"))
    }

    @Test
    fun `install source is the npm form`() {
        val results = PackagesRegistry.parse(sampleHtml())
        assertEquals("npm:pi-mcp-adapter", results.first { it.name == "pi-mcp-adapter" }.installSource)
    }

    @Test
    fun `scoped package names survive parsing`() {
        val results = PackagesRegistry.parse(sampleHtml())
        val scoped = results.firstOrNull { it.name.startsWith("@") }
        requireNotNull(scoped) { "expected at least one scoped package" }
        assertEquals("npm:${scoped.name}", scoped.installSource)
    }

    /** Scraping must fail soft: unknown markup produces no results rather than an exception. */
    @Test
    fun `unrecognised markup parses to nothing`() {
        assertTrue(PackagesRegistry.parse("<html><body>redesigned</body></html>").isEmpty())
        assertTrue(PackagesRegistry.parse("").isEmpty())
    }

    @Test
    fun `download counts are formatted`() {
        val results = PackagesRegistry.parse(sampleHtml())
        val label = results.first { it.downloads > 100_000 }.downloadsLabel()
        assertTrue(label, label.endsWith("downloads"))
    }

    // -------------------------------------------------------- installed list

    private fun settings(json: String): File =
        tmp.newFile("settings.json").apply { writeText(json) }

    private fun readVia(file: File): List<dev.pi.gui.packages.InstalledPackage> {
        // listInstalled() resolves real directories, so exercise the parser through a temp file
        // by pointing the project settings path at it.
        val projectDir = tmp.newFolder("proj")
        File(projectDir, ".pi").mkdirs()
        File(projectDir, ".pi/settings.json").writeText(file.readText())
        return PackagesService.listInstalled(projectDir.absolutePath)
            .filter { it.scope == PackageScope.PROJECT }
    }

    @Test
    fun `bare string package entries are read`() {
        val packages = readVia(settings("""{"packages":["npm:@foo/bar","git:github.com/u/r"]}"""))
        assertEquals(2, packages.size)
        assertEquals("npm:@foo/bar", packages[0].source)
        assertEquals("@foo/bar", packages[0].displayName)
        assertNull("a bare entry declares no explicit resources", packages[0].extensions)
    }

    @Test
    fun `object package entries expose their resource filters`() {
        val packages = readVia(
            settings("""{"packages":[{"source":"npm:x","extensions":["a","b"],"skills":["s"]}]}""")
        )
        assertEquals(1, packages.size)
        assertEquals(listOf("a", "b"), packages[0].extensions)
        assertEquals(listOf("s"), packages[0].skills)
    }

    @Test
    fun `settings without a packages key yields nothing`() {
        assertTrue(readVia(settings("""{"theme":"light"}""")).isEmpty())
    }

    @Test
    fun `malformed settings do not throw`() {
        assertTrue(readVia(settings("not json at all")).isEmpty())
    }

    @Test
    fun `summary counts packages and declared resources`() {
        val packages = readVia(
            settings(
                """{"packages":[
                     {"source":"npm:a","extensions":["e1","e2"],"skills":["s1"]},
                     {"source":"npm:b","themes":["t1"],"prompts":["p1","p2"]}
                   ]}"""
            )
        )
        val counts = PackagesService.summarize(packages)
        assertEquals(2, counts.packages)
        assertEquals(2, counts.extensions)
        assertEquals(1, counts.skills)
        assertEquals(2, counts.prompts)
        assertEquals(1, counts.themes)
    }

    @Test
    fun `project settings live under dot pi`() {
        assertEquals(File("/tmp/p/.pi/settings.json"), PackagesService.projectSettingsFile("/tmp/p"))
        assertNull(PackagesService.projectSettingsFile(null))
        assertNull(PackagesService.projectSettingsFile(""))
    }

    @Test
    fun `display name strips the source prefix`() {
        val packages = readVia(settings("""{"packages":["npm:pi-subagents"]}"""))
        assertEquals("pi-subagents", packages.single().displayName)
    }
}
