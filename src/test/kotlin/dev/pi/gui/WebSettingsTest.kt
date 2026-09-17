package dev.pi.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.providers.ProvidersRegistry
import dev.pi.gui.providers.CcSwitchImporter
import dev.pi.gui.providers.ImportedProvider
import dev.pi.gui.providers.ProviderKind
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.ThemeMode
import dev.pi.gui.settings.UiLanguage
import dev.pi.gui.settings.CommitLanguage
import dev.pi.gui.ui.settings.WebSettingsSurface
import dev.pi.gui.web.PiWebView
import dev.pi.gui.web.WebPage
import java.io.File
import kotlin.io.path.createTempDirectory
import javax.swing.JComponent
import javax.swing.JPanel

/** Contract tests for the JCEF settings application. */
class WebSettingsTest : BasePlatformTestCase() {

    private lateinit var original: PiSettings.State

    override fun setUp() {
        super.setUp()
        original = PiSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().loadState(original)
        } finally {
            super.tearDown()
        }
    }

    private class RecordingPage : WebPage {
        val posted = mutableListOf<Map<String, Any?>>()
        var disposed = false
        override val component: JComponent = JPanel()
        override fun post(event: Map<String, Any?>) { posted += event }
        override fun requestBrowserFocus() = Unit
        override fun dispose() { disposed = true }
    }

    private fun settingsJs(): String = PiWebView::class.java.getResourceAsStream("/web/settings.js")!!
        .bufferedReader().use { it.readText() }

    fun testSettingsPageIsSelfContained() {
        val document = PiWebView.document("settings")
        assertFalse(document.contains("href=\"app.css\""))
        assertFalse(document.contains("src=\"settings.js\""))
        assertTrue(document.contains("id=\"settings-root\""))
        assertTrue(document.contains("window.__piSend"))
        assertTrue(document.contains("default-src 'none'"))
        assertTrue(document.contains("data-page=\"commit-ai\""))
        assertTrue(document.contains("data-page=\"mcp\""))
        assertTrue(document.contains("id=\"mcp-list\""))
        assertTrue(document.contains("id=\"commit-prompt\""))
    }

    fun testMcpNavigationSitsBetweenSkillsAndPlugins() {
        val js = settingsJs()
        val skills = js.indexOf("['skills', 'skill', 'settings.tab.skills']")
        val mcp = js.indexOf("['mcp', 'mcp', 'settings.tab.mcp']")
        val plugins = js.indexOf("['plugins', 'plugin', 'settings.tab.plugins']")
        assertTrue("MCP must follow Skills", mcp > skills)
        assertTrue("MCP must precede Plugins", plugins > mcp)
    }

    fun testCommitAiNavigationSitsBetweenPluginsAndCli() {
        val js = settingsJs()
        val plugins = js.indexOf("['plugins', 'plugin', 'settings.tab.plugins']")
        val commitAi = js.indexOf("['commit-ai', 'commit', 'settings.tab.commitAi']")
        val cli = js.indexOf("['cli', 'terminal', 'settings.tab.cli']")
        assertTrue("plugins tab missing", plugins >= 0)
        assertTrue("Commit AI must follow Plugins", commitAi > plugins)
        assertTrue("Commit AI must precede pi CLI", cli > commitAi)
    }

    fun testEveryMessageSentBySettingsJsHasABridgeHandler() {
        val sent = Regex("""send\(\{\s*type:\s*'([^']+)'""").findAll(settingsJs())
            .map { it.groupValues[1] }.toSet()
        assertTrue("settings.js declared no bridge messages", sent.size > 10)
        assertEquals(emptySet<String>(), sent - WebSettingsSurface.HANDLED_MESSAGES)
    }

    fun testSkillSearchUsesRepositoryMetadataAndOneDeferredDomSwap() {
        val js = settingsJs()
        assertTrue(js.contains("source: button.dataset.source"))
        assertTrue(js.contains("name: button.dataset.name"))
        assertTrue(js.contains("window.requestAnimationFrame"))
        assertTrue(js.contains("host.replaceChildren"))
        assertFalse("result cards must use delegated events", js.contains("host.querySelectorAll('.install-skill').forEach"))
    }

    fun testEveryNativeEventHasAJavaScriptHandler() {
        val js = settingsJs()
        val body = js.substringAfter("var handlers = {").substringBefore("\n  };")
        val handlers = Regex("""^\s{4}(\w+): function""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(
            emptySet<String>(),
            setOf("theme", "i18n", "settings", "providers", "skills", "mcp", "packages", "skillResults", "packageResults", "status") - handlers,
        )
    }

    fun testStoredApiKeyIsNeverPostedToJcef() {
        val page = RecordingPage()
        val registry = ProvidersRegistry(File(createTempDirectory("pi-settings-secrets").toFile(), "agent"))
        registry.import(
            CcSwitchImporter.Outcome(
                listOf(
                    ImportedProvider(
                        id = "",
                        name = "Secret provider",
                        kind = ProviderKind.CLAUDE_CODE,
                        baseUrl = "https://example.test",
                        apiKey = "must-not-enter-jcef",
                        models = listOf("model-one"),
                        api = "anthropic-messages",
                    )
                ),
                emptyList(),
            )
        )
        val surface = WebSettingsSurface(project, registry, page = { page })
        try {
            val event = page.posted.last { it["type"] == "providers" }
            val items = event["items"] as List<*>
            val provider = items.single() as Map<*, *>
            assertEquals("", provider["apiKey"])
            assertEquals(true, provider["hasApiKey"])
            assertFalse(event.toString().contains("must-not-enter-jcef"))
        } finally {
            surface.dispose()
            registry.sidecarFile().parentFile.deleteRecursively()
        }
    }

    fun testGeneralAndCliDraftOnlyApplyWhenRequested() {
        val page = RecordingPage()
        var bridge: (JsonObject) -> Unit = {}
        val registry = ProvidersRegistry(File(createTempDirectory("pi-settings-test").toFile(), "agent"))
        val surface = WebSettingsSurface(project, registry) { callback ->
            bridge = callback
            page
        }
        try {
            val settings = PiSettings.getInstance()
            settings.themeMode = ThemeMode.SYSTEM
            settings.language = UiLanguage.SIMPLIFIED_CHINESE
            settings.extraArgs = ""
            settings.commitLanguage = CommitLanguage.CHINESE
            settings.commitPrompt = PiSettings.DEFAULT_COMMIT_PROMPT

            bridge(JsonParser.parseString("""{"type":"updateDraft","field":"theme","value":"DARK"}""").asJsonObject)
            bridge(JsonParser.parseString("""{"type":"updateDraft","field":"language","value":"en"}""").asJsonObject)
            bridge(JsonParser.parseString("""{"type":"updateDraft","field":"extraArgs","value":"--models test/*"}""").asJsonObject)
            bridge(JsonParser.parseString("""{"type":"updateDraft","field":"commitLanguage","value":"ENGLISH"}""").asJsonObject)
            bridge(JsonParser.parseString("""{"type":"updateDraft","field":"commitPrompt","value":"Use a short subject"}""").asJsonObject)

            assertEquals(ThemeMode.SYSTEM, settings.themeMode)
            assertTrue(surface.isModified())
            surface.apply()
            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals(UiLanguage.ENGLISH, settings.language)
            assertEquals("--models test/*", settings.extraArgs)
            assertEquals(CommitLanguage.ENGLISH, settings.commitLanguage)
            assertEquals("Use a short subject", settings.commitPrompt)
            assertFalse(surface.isModified())
        } finally {
            surface.dispose()
        }
        assertTrue(page.disposed)
    }
}
