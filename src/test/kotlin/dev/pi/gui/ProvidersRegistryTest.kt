package dev.pi.gui

import com.google.gson.JsonParser
import dev.pi.gui.providers.CcSwitchImporter
import dev.pi.gui.providers.ImportedProvider
import dev.pi.gui.providers.ProviderKind
import dev.pi.gui.providers.ProvidersRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The provider registry: what import/update/delete write into the sidecar, and — more
 * importantly — that pi's `models.json` gets our `ccswitch-*` entries without ever touching
 * providers the user wrote by hand.
 */
class ProvidersRegistryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun registry(): ProvidersRegistry = ProvidersRegistry(temp.newFolder())

    private fun provider(
        name: String,
        kind: ProviderKind = ProviderKind.CLAUDE_CODE,
        baseUrl: String = "https://$name.example.com",
        sourceId: String? = name,
        models: List<String> = listOf("model-one"),
    ) = ImportedProvider(
        id = "", // assigned by the registry
        name = name,
        kind = kind,
        baseUrl = baseUrl,
        apiKey = "key-$name",
        models = models,
        defaultModel = models.first(),
        sourceId = sourceId,
        api = if (kind == ProviderKind.CLAUDE_CODE) "anthropic-messages" else "openai-completions",
    )

    private fun outcome(vararg providers: ImportedProvider) =
        CcSwitchImporter.Outcome(providers.toList(), emptyList())

    private fun modelsJson(registry: ProvidersRegistry): com.google.gson.JsonObject =
        JsonParser.parseString(registry.modelsFile().readText()).asJsonObject

    @Test
    fun `import writes the sidecar and a ccswitch entry into models json`() {
        val registry = registry()

        val summary = registry.import(outcome(provider("MiniMax")))

        assertEquals(1, summary.added)
        val saved = registry.list().single()
        assertEquals("ccswitch-minimax", saved.id)

        val piProvider = modelsJson(registry)
            .get("providers").asJsonObject.get("ccswitch-minimax").asJsonObject
        assertEquals("https://MiniMax.example.com", piProvider.get("baseUrl").asString)
        assertEquals("anthropic-messages", piProvider.get("api").asString)
        assertEquals("key-MiniMax", piProvider.get("apiKey").asString)
        assertEquals("model-one", piProvider.get("models").asJsonArray.single().asJsonObject.get("id").asString)
    }

    @Test
    fun `hand-written providers in models json survive an import`() {
        val registry = registry()
        val agentDir = registry.modelsFile().parentFile
        File(agentDir, "models.json").writeText(
            """
            { "providers": { "my-own": { "baseUrl": "http://localhost:1234/v1", "api": "openai-completions" } } }
            """.trimIndent()
        )

        registry.import(outcome(provider("Relay")))

        val providers = modelsJson(registry).get("providers").asJsonObject
        assertTrue(providers.has("my-own"))
        assertEquals("http://localhost:1234/v1", providers.get("my-own").asJsonObject.get("baseUrl").asString)
        assertTrue(providers.has("ccswitch-relay"))
    }

    @Test
    fun `re-import updates in place instead of duplicating`() {
        val registry = registry()
        registry.import(outcome(provider("Relay", baseUrl = "https://old.example.com")))

        val summary = registry.import(
            outcome(provider("Relay", baseUrl = "https://new.example.com", models = listOf("m1", "m2")))
        )

        assertEquals(0, summary.added)
        assertEquals(1, summary.updated)
        val saved = registry.list().single()
        assertEquals("https://new.example.com", saved.baseUrl)
        assertEquals(listOf("m1", "m2"), saved.models)
        assertTrue(registry.modelsFile().readText().contains("https://new.example.com"))
    }

    @Test
    fun `same name twice gets unique ids`() {
        val registry = registry()

        registry.import(outcome(provider("Relay", sourceId = "a"), provider("Relay", sourceId = "b")))

        assertEquals(
            setOf("ccswitch-relay", "ccswitch-relay-2"),
            registry.list().map { it.id }.toSet()
        )
    }

    @Test
    fun `update rewrites both files`() {
        val registry = registry()
        registry.import(outcome(provider("Relay")))
        val saved = registry.list().single()

        registry.update(saved.copy(name = "Relay2", baseUrl = "https://r2.example.com"))

        val after = registry.list().single()
        assertEquals("Relay2", after.name)
        assertEquals("https://r2.example.com", after.baseUrl)
        val providers = modelsJson(registry).get("providers").asJsonObject
        assertTrue(providers.has("ccswitch-relay"))
        assertTrue(!providers.has("ccswitch-relay2"))
    }

    @Test
    fun `delete removes the entry from both files`() {
        val registry = registry()
        registry.import(outcome(provider("Relay"), provider("Keep")))

        registry.delete("ccswitch-relay")

        assertEquals(listOf("ccswitch-keep"), registry.list().map { it.id })
        val providers = modelsJson(registry).get("providers").asJsonObject
        assertTrue(!providers.has("ccswitch-relay"))
        assertTrue(providers.has("ccswitch-keep"))
    }

    @Test
    fun `empty agent dir lists nothing and does not throw`() {
        assertTrue(registry().list().isEmpty())
    }
}
