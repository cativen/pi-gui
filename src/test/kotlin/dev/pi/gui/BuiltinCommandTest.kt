package dev.pi.gui

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.pi.gui.commands.BuiltinCommands
import dev.pi.gui.commands.PiCommand
import dev.pi.gui.model.PiMessage
import dev.pi.gui.session.SessionStore
import dev.pi.gui.ui.ChatPanel

/**
 * pi's built-in commands.
 *
 * These never appear in `get_commands` and pi will not execute them when they arrive as a prompt,
 * so the plugin has to implement them. The table here is copied from pi's own
 * `BUILTIN_SLASH_COMMANDS`; these tests pin it against drift.
 */
class BuiltinCommandTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // These read the Swing composer directly; the browser composer answers over
        // the JS bridge, which no headless test can drive. The dispatch logic is the same either way.
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    /** The exact set pi 0.84.2 ships, in its own order. */
    private val expectedNames = listOf(
        "settings", "model", "tree", "thinking", "scoped-models", "export", "import", "share",
        "copy", "name", "session", "changelog", "hotkeys", "fork", "clone", "trust", "login",
        "logout", "new", "compact", "resume", "reload", "quit",
    )

    fun testTableMatchesPi() {
        assertEquals(expectedNames, BuiltinCommands.ALL.map { it.name })
    }

    /** The four the request called out by name must all be there and natively handled. */
    fun testTheNamedCommandsArePresentAndNative() {
        listOf("model", "new", "tree", "reload").forEach { name ->
            val builtin = BuiltinCommands.find(name)
            assertNotNull("/$name must exist", builtin)
            assertEquals(
                "/$name must be handled by the plugin, not deferred to the CLI",
                BuiltinCommands.Availability.NATIVE,
                builtin!!.availability,
            )
        }
    }

    /** Argument hints are surfaced the way pi's own autocomplete shows them. */
    fun testArgumentHintsReachTheCompletionList() {
        val model = BuiltinCommands.AS_COMMANDS.first { it.name == "model" }
        assertTrue(model.description!!.startsWith("<provider/model>"))
        assertEquals(BuiltinCommands.SOURCE, model.source)
    }

    fun testInvocationSplitsNameFromArguments() {
        assertEquals("name" to "release prep", BuiltinCommands.parseInvocation("/name release prep"))
        assertEquals("new" to "", BuiltinCommands.parseInvocation("/new"))
        assertNull(BuiltinCommands.parseInvocation("not a command"))
        assertNull(BuiltinCommands.parseInvocation("/"))
    }

    // ------------------------------------------------------------- completion

    fun testBuiltinsAreOfferedBeforeAnythingIsFetched() {
        val panel = ChatPanel(project)
        try {
            panel.commandRegistryForTest().invalidate()
            panel.inputForTest().text = "/mod"
            panel.inputForTest().caretPosition = 4
            UIUtil.dispatchAllInvocationEvents()

            val state = panel.commandCompletionForTest() as ChatPanel.CommandCompletion.Matches
            assertTrue("the list should say it is still fetching", state.loading)
            // A name prefix outranks a mid-name match, so `/mod` puts `model` above
            // `scoped-models` rather than burying it.
            assertEquals("model", state.commands.first().name)
            assertTrue(state.commands.map { it.name }.contains("scoped-models"))
        } finally {
            panel.dispose()
        }
    }

    fun testEveryBuiltinIsReachableByTypingItsName() {
        val all = BuiltinCommands.AS_COMMANDS
        BuiltinCommands.ALL.forEach { builtin ->
            val matches = PiCommand.filter(all, builtin.name)
            assertTrue(
                "/${builtin.name} must match itself",
                matches.any { it.name == builtin.name },
            )
        }
    }

    // --------------------------------------------------------------- dispatch

    /**
     * The whole point: a built-in must never be forwarded to the model as prose. It is consumed,
     * the composer is cleared, and the user gets an answer in the transcript.
     */
    fun testCliOnlyCommandExplainsItselfInsteadOfBeingSent() {
        val panel = ChatPanel(project)
        try {
            panel.inputForTest().text = "/login anthropic"
            assertTrue(panel.runBuiltinForTest("/login anthropic"))
            assertEquals("", panel.composerText())

            val notice = panel.messagesForTest().last()
            assertTrue(notice is PiMessage.Notice)
            assertTrue(
                "the notice should name the command",
                (notice as PiMessage.Notice).text.contains("/login"),
            )
            // Nothing was queued as a prompt.
            assertTrue(panel.messagesForTest().none { it is PiMessage.User })
        } finally {
            panel.dispose()
        }
    }

    fun testEveryCliOnlyCommandIsHandledRatherThanSent() {
        val panel = ChatPanel(project)
        try {
            BuiltinCommands.ALL
                .filter { it.availability == BuiltinCommands.Availability.CLI_ONLY }
                .forEach { builtin ->
                    assertTrue(
                        "/${builtin.name} must be consumed",
                        panel.runBuiltinForTest("/${builtin.name}"),
                    )
                }
            assertTrue(panel.messagesForTest().none { it is PiMessage.User })
        } finally {
            panel.dispose()
        }
    }

    fun testNewSessionCommandDrivesTheToolWindow() {
        val panel = ChatPanel(project)
        try {
            var called = 0
            panel.onNewSessionRequested = { called++ }
            panel.inputForTest().text = "/new"
            assertTrue(panel.runBuiltinForTest("/new"))
            assertEquals(1, called)
            assertEquals("", panel.composerText())
        } finally {
            panel.dispose()
        }
    }

    fun testResumeCommandOpensTheSessionList() {
        val panel = ChatPanel(project)
        try {
            var called = 0
            panel.onShowSessionsRequested = { called++ }
            assertTrue(panel.runBuiltinForTest("/resume"))
            assertEquals(1, called)
        } finally {
            panel.dispose()
        }
    }

    fun testUnknownSlashTextIsNotTreatedAsABuiltin() {
        val panel = ChatPanel(project)
        try {
            assertFalse(panel.runBuiltinForTest("/skill:xlsx do the thing"))
            assertFalse(panel.runBuiltinForTest("/not-a-command"))
            assertFalse(panel.runBuiltinForTest("ordinary question"))
        } finally {
            panel.dispose()
        }
    }

    // ----------------------------------------------------- /session and /tree

    /**
     * Captured from pi 0.84.2 on a real session. The RPC docs show a single `messageCount`; the
     * CLI actually sends a breakdown, and reading the documented name alone showed no count.
     */
    fun testSessionStatsAreRenderedFromTheRealPayloadShape() {
        val data = JsonParser.parseString(
            """
            {"sessionId":"01a05fe9","userMessages":3,"assistantMessages":103,
             "toolCalls":127,"toolResults":126,"totalMessages":232,
             "tokens":{"input":346656,"output":107437,"cacheRead":8161024,"cacheWrite":0,"total":8615117},
             "cost":3.0799074399999995,
             "contextUsage":{"tokens":128807,"contextWindow":1000000,"percent":12.8807}}
            """.trimIndent()
        ).asJsonObject

        val text = SessionStore.describeStats(data)
        assertTrue(text, text.contains("Messages: 232"))
        assertTrue(text, text.contains("3 from you"))
        assertTrue(text, text.contains("103 from pi"))
        assertTrue(text, text.contains("127 tool calls"))
        assertTrue(text, text.contains("8.6M"))
        assertTrue(text, text.contains("$3.0799"))
        assertTrue(text, text.contains("128.8k / 1.0M (13%)"))
    }

    /** The documented spelling still works, in case a future pi goes back to it. */
    fun testSessionStatsAlsoAcceptTheDocumentedMessageCount() {
        val data = JsonParser.parseString("""{"messageCount":42}""").asJsonObject
        assertTrue(SessionStore.describeStats(data).contains("Messages: 42"))
    }

    /** pi omits `contextUsage` until a model has answered; that must not read as "0 tokens". */
    fun testSessionStatsOmitMissingFieldsRatherThanShowingZero() {
        val data = JsonParser.parseString("""{"totalMessages":1}""").asJsonObject
        val text = SessionStore.describeStats(data)
        assertTrue(text.contains("Messages: 1"))
        assertFalse(text.contains("Context"))
        assertFalse(text.contains("Cost"))
    }

    fun testEmptyStatsSaySoInsteadOfRenderingNothing() {
        val text = SessionStore.describeStats(JsonParser.parseString("{}").asJsonObject)
        assertTrue(text.isNotBlank())
    }

    fun testTreeIsRenderedAsAnOutlineMarkingTheCurrentLeaf() {
        val data = JsonParser.parseString(
            """
            {"tree":[{"entry":{"type":"message","id":"a","parentId":null,
                               "message":{"role":"user","content":[{"type":"text","text":"first ask"}]}},
                      "children":[
                        {"entry":{"type":"message","id":"b","parentId":"a",
                                  "message":{"role":"user","content":[{"type":"text","text":"branch one"}]}},
                         "children":[]},
                        {"entry":{"type":"message","id":"c","parentId":"a",
                                  "message":{"role":"user","content":[{"type":"text","text":"branch two"}]}},
                         "children":[]}]}],
             "leafId":"c"}
            """.trimIndent()
        ).asJsonObject

        val text = SessionStore.describeTree(data)
        assertTrue(text.contains("first ask"))
        assertTrue(text.contains("branch one"))
        // The active leaf is marked, and the other branch is not.
        assertTrue(text.contains("▸ user: branch two"))
        assertTrue(text.contains("· user: branch one"))
        // Children are indented below their parent.
        assertTrue(text.contains("\n  "))
    }

    fun testEmptyTreeSaysSo() {
        val text = SessionStore.describeTree(JsonParser.parseString("""{"tree":[]}""").asJsonObject)
        assertTrue(text.contains("no entries"))
    }

    /**
     * A session is a chain — every entry is the child of the previous one — so indenting per level
     * would push a 200-message session 200 columns right. Indentation is reserved for branches.
     */
    fun testALinearSessionIsNotIndentedIntoOblivion() {
        val tree = JsonParser.parseString(buildLinearTree(120)).asJsonObject
        val text = SessionStore.describeTree(tree)

        val widestIndent = text.lines().maxOf { line -> line.takeWhile { it == ' ' }.length }
        assertEquals("a chain must stay flush left", 0, widestIndent)
        assertTrue(text.contains("step 0"))
        assertTrue(text.contains("▸ user: step 119"))
    }

    /** A fork point has to be visible even though the entry itself carries no text. */
    fun testBranchPointsAreLabelledAndIndentTheirChildren() {
        val tree = JsonParser.parseString(
            """
            {"tree":[{"entry":{"type":"message","id":"a",
                               "message":{"role":"toolResult","content":[]}},
                      "children":[
                        {"entry":{"type":"message","id":"b","message":{"role":"user",
                                  "content":[{"type":"text","text":"abandoned"}]}},"children":[]},
                        {"entry":{"type":"message","id":"c","message":{"role":"user",
                                  "content":[{"type":"text","text":"kept"}]}},"children":[]}]}],
             "leafId":"c"}
            """.trimIndent()
        ).asJsonObject

        val text = SessionStore.describeTree(tree)
        assertTrue(text, text.contains("⑂ 2 branches from here"))
        assertTrue(text, text.contains("  · user: abandoned"))
        assertTrue(text, text.contains("  ▸ user: kept"))
    }

    /** Tool traffic is the bulk of a session and tells you nothing about where it forked. */
    fun testToolResultsAreLeftOutOfTheOutline() {
        val tree = JsonParser.parseString(
            """
            {"tree":[{"entry":{"type":"message","id":"a","message":{"role":"user",
                               "content":[{"type":"text","text":"do it"}]}},
                      "children":[{"entry":{"type":"message","id":"b",
                                            "message":{"role":"toolResult","content":[]}},
                                   "children":[]}]}],
             "leafId":"a"}
            """.trimIndent()
        ).asJsonObject

        val text = SessionStore.describeTree(tree)
        assertTrue(text.contains("do it"))
        assertFalse("tool results should not be listed", text.contains("toolResult"))
    }

    /** Settings entries sit in the same chain as messages and get a short label, not a raw type. */
    fun testSettingsEntriesAreLabelledReadably() {
        val tree = JsonParser.parseString(
            """
            {"tree":[{"entry":{"type":"model_change","id":"a","provider":"zai-coding-cn","modelId":"glm-5.3"},
                      "children":[{"entry":{"type":"thinking_level_change","id":"b","thinkingLevel":"high"},
                                   "children":[]}]}],
             "leafId":"b"}
            """.trimIndent()
        ).asJsonObject

        val text = SessionStore.describeTree(tree)
        assertTrue(text, text.contains("model → zai-coding-cn/glm-5.3"))
        assertTrue(text, text.contains("▸ thinking → high"))
    }

    fun testVeryLargeTreeIsTruncatedRatherThanDumped() {
        val tree = JsonParser.parseString(buildLinearTree(400)).asJsonObject
        val text = SessionStore.describeTree(tree)
        assertTrue(text, text.contains("and 200 more entries"))
    }

    /** Builds `{"tree":[…]}` for a chain of [depth] user messages. */
    private fun buildLinearTree(depth: Int): String {
        val open = StringBuilder()
        val close = StringBuilder()
        repeat(depth) { i ->
            open.append(
                """{"entry":{"type":"message","id":"e$i","message":{"role":"user","content":""" +
                    """[{"type":"text","text":"step $i"}]}},"children":["""
            )
            close.append("]}")
        }
        return """{"tree":[$open$close],"leafId":"e${depth - 1}"}"""
    }
}
