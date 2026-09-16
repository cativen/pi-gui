package dev.pi.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pi.gui.mcp.McpConfigService
import dev.pi.gui.mcp.McpScope
import dev.pi.gui.mcp.McpServerInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpConfigServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun service(): Triple<McpConfigService, File, File> {
        val agent = tmp.newFolder("agent")
        val project = tmp.newFolder("project")
        return Triple(McpConfigService(agent, project), agent, project)
    }

    @Test
    fun `lists global and project servers without exposing secrets`() {
        val (service, agent, project) = service()
        File(agent, "mcp.json").writeText("""{"mcpServers":{"global-db":{"command":"npx","args":["db"],"env":{"TOKEN":"secret"}}}}""")
        File(project, ".pi").mkdirs()
        File(project, ".pi/mcp.json").writeText("""{"mcpServers":{"remote":{"transport":"sse","url":"https://example.test/sse"}}}""")

        val servers = service.list()
        assertEquals(2, servers.size)
        assertTrue(servers.first { it.name == "global-db" }.hasEnv)
        assertEquals(McpScope.PROJECT, servers.first { it.name == "remote" }.scope)
        assertTrue(servers.all { it.valid })
    }

    @Test
    fun `disable and enable move config without losing unknown fields`() {
        val (service, agent, _) = service()
        File(agent, "mcp.json").writeText("""{"mcpServers":{"db":{"command":"db-server","custom":42}}}""")
        val id = service.list().single().id

        assertTrue(service.setEnabled(id, false).isSuccess)
        assertFalse(service.list().single().enabled)
        assertFalse(JsonParser.parseString(File(agent, "mcp.json").readText()).asJsonObject.getAsJsonObject("mcpServers").has("db"))

        assertTrue(service.setEnabled(id, true).isSuccess)
        val restored = JsonParser.parseString(File(agent, "mcp.json").readText()).asJsonObject
            .getAsJsonObject("mcpServers").getAsJsonObject("db")
        assertEquals(42, restored["custom"].asInt)
        assertTrue(service.list().single().enabled)
    }

    @Test
    fun `editing preserves stored environment when field is blank`() {
        val (service, agent, _) = service()
        File(agent, "mcp.json").writeText("""{"mcpServers":{"db":{"command":"old","env":{"TOKEN":"secret"}}}}""")
        val current = service.list().single()
        val result = service.save(
            McpServerInput(current.id, "db", McpScope.GLOBAL, "stdio", "new", listOf("--port", "9"), "", "eager", null, null),
        )

        assertTrue(result.isSuccess)
        val saved = JsonParser.parseString(File(agent, "mcp.json").readText()).asJsonObject
            .getAsJsonObject("mcpServers").getAsJsonObject("db")
        assertEquals("new", saved["command"].asString)
        assertEquals("secret", saved.getAsJsonObject("env")["TOKEN"].asString)
    }

    @Test
    fun `creates and deletes project server`() {
        val (service, _, project) = service()
        val headers = JsonObject().apply { addProperty("Authorization", "Bearer token") }
        assertTrue(service.save(McpServerInput(null, "api", McpScope.PROJECT, "streamable-http", "", emptyList(), "https://example.test/mcp", "lazy", null, headers)).isSuccess)
        val server = service.list().single()
        assertEquals(McpScope.PROJECT, server.scope)
        assertTrue(File(project, ".pi/mcp.json").isFile)
        assertTrue(service.delete(server.id).isSuccess)
        assertTrue(service.list().isEmpty())
    }
}
