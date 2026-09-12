package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.providers.CcSwitchImporter
import dev.pi.gui.providers.ImportedProvider
import dev.pi.gui.providers.ProviderKind
import dev.pi.gui.providers.ProvidersRegistry
import dev.pi.gui.ui.settings.ProvidersSettingsPanel
import org.junit.Assert.assertEquals
import java.io.File

/**
 * The "Model Providers" settings page against a temporary registry: importing lists the
 * providers under their cc-switch family section.
 */
class ProvidersSettingsPanelTest : BasePlatformTestCase() {

    private lateinit var agentDir: File
    private lateinit var registry: ProvidersRegistry

    override fun setUp() {
        super.setUp()
        agentDir = File.createTempFile("pi-providers", "").let { dir ->
            dir.delete()
            dir.mkdirs()
            dir
        }
        registry = ProvidersRegistry(agentDir)
    }

    override fun tearDown() {
        try {
            agentDir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun provider(name: String, kind: ProviderKind, baseUrl: String) = ImportedProvider(
        id = "",
        name = name,
        kind = kind,
        baseUrl = baseUrl,
        apiKey = "key",
        models = listOf("model-x"),
        defaultModel = "model-x",
        sourceId = name,
        api = if (kind == ProviderKind.CLAUDE_CODE) "anthropic-messages" else "openai-completions",
    )

    fun testImportListsProvidersUnderTheirSection() {
        val outcome = CcSwitchImporter.Outcome(
            listOf(
                provider("MiniMax", ProviderKind.CLAUDE_CODE, "https://a.example.com"),
                provider("Relay", ProviderKind.CODEX, "https://b.example.com/v1"),
                provider("Zhipu", ProviderKind.CLAUDE_CODE, "https://c.example.com"),
            ),
            listOf(CcSwitchImporter.Skipped("Claude Official", CcSwitchImporter.Skipped.Reason.NO_BASE_URL)),
        )
        val panel = ProvidersSettingsPanel(project, registry, autoImporter = { outcome })

        panel.importForTest(outcome)

        assertEquals(listOf("MiniMax", "Zhipu"), panel.providerNamesForTest(ProviderKind.CLAUDE_CODE))
        assertEquals(listOf("Relay"), panel.providerNamesForTest(ProviderKind.CODEX))
    }

    fun testEnablingAProviderRecordsItAsTheActiveSelection() {
        val outcome = CcSwitchImporter.Outcome(listOf(provider("MiniMax", ProviderKind.CLAUDE_CODE, "https://a.example.com")), emptyList())
        val panel = ProvidersSettingsPanel(project, registry, autoImporter = { outcome })
        panel.importForTest(outcome)

        panel.enableForTest("MiniMax")

        val imported = registry.list().single()
        assertEquals(imported.id, dev.pi.gui.settings.PiSettings.getInstance().activeProvider)
        assertEquals("model-x", dev.pi.gui.settings.PiSettings.getInstance().activeModel)
        // Clean up so other tests in the same suite start from a blank selection.
        dev.pi.gui.settings.PiSettings.getInstance().apply {
            activeProvider = ""
            activeModel = ""
        }
    }

    fun testEmptyRegistryShowsNothing() {
        val panel = ProvidersSettingsPanel(project, registry, autoImporter = {
            CcSwitchImporter.Outcome(emptyList(), emptyList())
        })

        assertEquals(emptyList<String>(), panel.providerNamesForTest(ProviderKind.CLAUDE_CODE))
        assertEquals(emptyList<String>(), panel.providerNamesForTest(ProviderKind.CODEX))
    }
}
