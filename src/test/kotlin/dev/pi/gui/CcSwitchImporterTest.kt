package dev.pi.gui

import dev.pi.gui.providers.CcSwitchImporter
import dev.pi.gui.providers.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * cc-switch import, against both storage generations: the v2 `config.json` and the v3
 * SQLite `cc-switch.db`. Entries without usable credentials must be skipped, not half-imported.
 */
class CcSwitchImporterTest {

    private fun tempFile(content: String): File =
        File.createTempFile("ccswitch", ".json").apply { writeText(content); deleteOnExit() }

    // ------------------------------------------------------------- v2 JSON

    @Test
    fun `claude provider with env credentials is imported with its models`() {
        val file = tempFile(
            """
            {
              "claude": {
                "providers": {
                  "u1": {
                    "name": "MiniMax",
                    "settingsConfig": {
                      "env": {
                        "ANTHROPIC_BASE_URL": "https://api.example.com/anthropic",
                        "ANTHROPIC_AUTH_TOKEN": "sk-token",
                        "ANTHROPIC_MODEL": "m-pro",
                        "ANTHROPIC_DEFAULT_SONNET_MODEL": "m-pro",
                        "ANTHROPIC_DEFAULT_OPUS_MODEL": "m-max"
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val outcome = CcSwitchImporter.importFromJson(file)

        assertEquals(0, outcome.skipped.size)
        val provider = outcome.imported.single()
        assertEquals(ProviderKind.CLAUDE_CODE, provider.kind)
        assertEquals("MiniMax", provider.name)
        assertEquals("https://api.example.com/anthropic", provider.baseUrl)
        assertEquals("sk-token", provider.apiKey)
        assertEquals(listOf("m-pro", "m-max"), provider.models)
        assertEquals("m-pro", provider.defaultModel)
        assertEquals("anthropic-messages", provider.api)
        assertEquals("u1", provider.sourceId)
    }

    @Test
    fun `official claude login without credentials is skipped`() {
        val file = tempFile(
            """
            {
              "claude": {
                "providers": {
                  "official": {
                    "name": "Claude Official",
                    "settingsConfig": { "env": {} }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val outcome = CcSwitchImporter.importFromJson(file)

        assertTrue(outcome.imported.isEmpty())
        assertEquals(1, outcome.skipped.size)
        assertEquals("Claude Official", outcome.skipped.single().name)
        assertEquals(CcSwitchImporter.Skipped.Reason.NO_BASE_URL, outcome.skipped.single().reason)
    }

    @Test
    fun `third-party codex provider is read from auth and toml config`() {
        val file = tempFile(
            """
            {
              "codex": {
                "providers": {
                  "c1": {
                    "name": "My Relay",
                    "settingsConfig": {
                      "auth": { "OPENAI_API_KEY": "rk-key" },
                      "config": "model = \"gpt-relay\"\nbase_url = \"https://relay.example.com/v1\"\nwire_api = \"responses\"\n"
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val outcome = CcSwitchImporter.importFromJson(file)

        assertEquals(0, outcome.skipped.size)
        val provider = outcome.imported.single()
        assertEquals(ProviderKind.CODEX, provider.kind)
        assertEquals("https://relay.example.com/v1", provider.baseUrl)
        assertEquals("rk-key", provider.apiKey)
        assertEquals(listOf("gpt-relay"), provider.models)
        assertEquals("openai-responses", provider.api)
    }

    @Test
    fun `chatgpt-login codex entry without api key is skipped`() {
        val file = tempFile(
            """
            {
              "codex": {
                "providers": {
                  "default": {
                    "name": "OpenAI Official",
                    "settingsConfig": {
                      "auth": { "auth_mode": "chatgpt", "OPENAI_API_KEY": null },
                      "config": "model = \"gpt-5\""
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val outcome = CcSwitchImporter.importFromJson(file)

        assertTrue(outcome.imported.isEmpty())
        assertEquals(CcSwitchImporter.Skipped.Reason.NO_BASE_URL, outcome.skipped.single().reason)
    }

    @Test
    fun `unparseable or missing json yields a distinct outcome`() {
        assertEquals(
            CcSwitchImporter.Skipped.Reason.BAD_DATA,
            CcSwitchImporter.importFromJson(tempFile("not json")).skipped.single().reason,
        )
        val missing = File.createTempFile("ccswitch", ".json").apply { delete(); deleteOnExit() }
        assertEquals(
            CcSwitchImporter.Skipped.Reason.NOT_FOUND,
            CcSwitchImporter.importFromJson(missing).skipped.single().reason,
        )
    }

    // --------------------------------------------------------------- v3 DB

    @Test
    fun `v3 sqlite database providers are imported by app_type`() {
        val db = File.createTempFile("ccswitch", ".db").apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "create table providers (id text, app_type text, name text, " +
                        "settings_config text, sort_index integer)"
                )
                st.executeUpdate(
                    """insert into providers values ('u9', 'claude', 'Relay A',
                       '{"env":{"ANTHROPIC_BASE_URL":"https://a.example.com","ANTHROPIC_AUTH_TOKEN":"t1","ANTHROPIC_MODEL":"model-a"}}', 1)"""
                )
                st.executeUpdate(
                    """insert into providers values ('d9', 'codex', 'Relay C',
                       '{"auth":{"OPENAI_API_KEY":"k9"},"config":"model = \"cx\"\nbase_url = \"https://c.example.com/v1\""}', 2)"""
                )
                st.executeUpdate(
                    """insert into providers values ('g9', 'gemini', 'Gemini X', '{}', 3)"""
                )
            }
        }

        val outcome = CcSwitchImporter.importFromDb(db)

        assertEquals(0, outcome.skipped.size)
        assertEquals(listOf("Relay A", "Relay C"), outcome.imported.map { it.name })
        assertEquals(listOf(ProviderKind.CLAUDE_CODE, ProviderKind.CODEX), outcome.imported.map { it.kind })
    }

    @Test
    fun `sqlite file without a providers table is reported as bad data`() {
        val db = File.createTempFile("ccswitch", ".db").apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { it.executeUpdate("create table other (x text)") }
        }

        val outcome = CcSwitchImporter.importFromDb(db)

        assertTrue(outcome.imported.isEmpty())
        assertEquals(CcSwitchImporter.Skipped.Reason.BAD_DATA, outcome.skipped.single().reason)
    }

    // ---------------------------------------------------------- auto-detect

    private fun tempDir(): File = File.createTempFile("ccswitch-dir", "").let { dir ->
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        dir
    }

    private fun tempDirWithDb(): File = tempDir().also { dir ->
        val db = File(dir, "cc-switch.db")
        db.createNewFile()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "create table providers (id text, app_type text, name text, " +
                        "settings_config text, sort_index integer)"
                )
                st.executeUpdate(
                    """insert into providers values ('a1', 'claude', 'Auto Relay',
                       '{"env":{"ANTHROPIC_BASE_URL":"https://auto.example.com","ANTHROPIC_AUTH_TOKEN":"t","ANTHROPIC_MODEL":"m"}}', 1)"""
                )
            }
        }
    }

    @Test
    fun `auto import prefers the v3 database and reports its source`() {
        val dir = tempDirWithDb()
        File(dir, "config.json").writeText("{\"claude\":{\"providers\":{\"z\":{\"name\":\"Stale v2\",\"settingsConfig\":{\"env\":{}}}}}}")

        val outcome = CcSwitchImporter.importAuto(dir)

        assertEquals(listOf("Auto Relay"), outcome.imported.map { it.name })
        assertEquals("cc-switch.db", outcome.source)
    }

    @Test
    fun `auto import falls back to the v2 json when only it exists`() {
        val dir = tempDir()
        File(dir, "config.json").writeText(
            """{"claude":{"providers":{"j1":{"name":"Json Only","nonsense":0,"settingsConfig":{"env":{}}}}}}"""
        )

        val outcome = CcSwitchImporter.importAuto(dir)

        assertEquals("config.json", outcome.source)
        assertEquals(1, outcome.skipped.size) // "Json Only": no base URL, but the file was read
    }

    @Test
    fun `auto import with no cc-switch directory yields an empty outcome`() {
        val outcome = CcSwitchImporter.importAuto(tempDir())

        assertTrue(outcome.imported.isEmpty())
        assertTrue(outcome.skipped.isEmpty())
        assertEquals(null, outcome.source)
    }

    // -------------------------------------------------------------- helpers

    @Test
    fun `toml scalars are read with either quote style`() {
        assertEquals("chat", CcSwitchImporter.tomlString("wire_api = \"chat\"", "wire_api"))
        assertEquals("b", CcSwitchImporter.tomlString("base_url = 'b'", "base_url"))
        assertEquals(null, CcSwitchImporter.tomlString("model = \"m\"", "base_url"))
    }
}
