package dev.pi.gui

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.pi.gui.commands.PiCommand
import dev.pi.gui.ui.ChatPanel

/**
 * The `/` command popup.
 *
 * Trigger rules mirror pi itself: `expandPromptTemplate` matches `^/([^\s]+)(?:\s+[\s\S]*)?$`
 * against the whole message, so a command only exists at offset 0 of the prompt.
 */
class SlashCommandTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The popup asserted here is the Swing one. The browser has its own, filtering the
        // same list the registry hands over.
        System.setProperty(ChatPanel.FORCE_SWING_PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            System.clearProperty(ChatPanel.FORCE_SWING_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    private fun sampleCommands() = listOf(
        PiCommand("llama", "Manage llama.cpp router models", "extension"),
        PiCommand("fix-tests", "Fix failing tests", "prompt", location = "project"),
        PiCommand("skill:xlsx", "Spreadsheets", "skill", location = "user"),
        PiCommand("skill:find-skills", "Discover skills", "skill", location = "user"),
    )

    // ------------------------------------------------------------ trigger rules

    fun testSlashAtTheStartOpensCompletion() {
        assertEquals("", PiCommand.queryAt("/", 1))
        assertEquals("fi", PiCommand.queryAt("/fix-tests", 3))
        assertEquals("fix-tests", PiCommand.queryAt("/fix-tests", 10))
    }

    /** `and/or`, a URL, a path — none of these are commands to pi, so none may pop up. */
    fun testSlashInsideTextDoesNotOpenCompletion() {
        assertNull(PiCommand.queryAt("read and/or write", 9))
        assertNull(PiCommand.queryAt("see https://pi.dev/docs", 20))
        assertNull(PiCommand.queryAt("  /fix-tests", 6))
    }

    /** Once the caret is in the arguments the command name is settled. */
    fun testCaretPastTheCommandClosesCompletion() {
        assertNull(PiCommand.queryAt("/fix-tests now", 14))
        assertNull(PiCommand.queryAt("/fix-tests now", 11))
        assertEquals("fix-tests", PiCommand.queryAt("/fix-tests now", 10))
    }

    fun testEmptyInputHasNoCompletion() {
        assertNull(PiCommand.queryAt("", 0))
        assertNull(PiCommand.queryAt("hello", 5))
    }

    // ------------------------------------------------------------------ filter

    fun testFilterMatchesNamePrefix() {
        val matches = PiCommand.filter(sampleCommands(), "fix")
        assertEquals(1, matches.size)
        assertEquals("fix-tests", matches.single().name)
    }

    /** pi prefixes skills with `skill:`; typing the bare name still has to find them. */
    fun testFilterLooksPastTheSkillPrefix() {
        val matches = PiCommand.filter(sampleCommands(), "xlsx")
        assertEquals("skill:xlsx", matches.first().name)
    }

    fun testFilterIsCaseInsensitiveAndRanksExactPrefixesFirst() {
        val matches = PiCommand.filter(sampleCommands(), "SKILL")
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.name.startsWith("skill:") })
    }

    fun testEmptyQueryKeepsEveryCommand() {
        assertEquals(4, PiCommand.filter(sampleCommands(), "").size)
    }

    fun testUnknownQueryMatchesNothing() {
        assertTrue(PiCommand.filter(sampleCommands(), "zzzz").isEmpty())
    }

    // ----------------------------------------------------------------- parsing

    /** The shape pi's RPC docs publish: `path` and `location` at the top level. */
    fun testParsesTheDocumentedResponseShape() {
        val json = JsonParser.parseString(
            """
            {"type":"response","command":"get_commands","success":true,"data":{"commands":[
              {"name":"session-name","description":"Set or clear session name","source":"extension",
               "path":"/home/user/.pi/agent/extensions/session.ts"},
              {"name":"fix-tests","description":"Fix failing tests","source":"prompt",
               "location":"project","path":"/home/user/p/.pi/agent/prompts/fix-tests.md"}
            ]}}
            """.trimIndent()
        ).asJsonObject

        val commands = PiCommand.parseResponse(json)
        assertEquals(2, commands.size)
        assertEquals("extension", commands[0].source)
        assertEquals("project", commands[1].location)
        assertEquals("/home/user/p/.pi/agent/prompts/fix-tests.md", commands[1].path)
    }

    /** The shape pi 0.84.2 actually emits, captured from the CLI on this machine. */
    fun testParsesTheRealResponseFixture() {
        val text = javaClass.getResourceAsStream("/fixtures/get_commands_response.json")
            ?.bufferedReader()?.readText()
        assertNotNull("fixture missing", text)
        val commands = PiCommand.parseResponse(JsonParser.parseString(text).asJsonObject)

        assertTrue("fixture should carry commands", commands.isNotEmpty())
        val llama = commands.first { it.name == "llama" }
        assertEquals("extension", llama.source)
        // `sourceInfo.path` is the nested spelling; the flat `path` is absent here.
        assertEquals("<inline:llama.cpp>", llama.path)

        val skill = commands.first { it.name.startsWith("skill:") }
        assertEquals("skill", skill.source)
        assertEquals("user", skill.location)
        assertTrue(skill.path!!.endsWith("SKILL.md"))
    }

    fun testMalformedResponseYieldsNoCommands() {
        assertTrue(PiCommand.parseResponse(JsonParser.parseString("{}").asJsonObject).isEmpty())
        assertTrue(
            PiCommand.parseResponse(
                JsonParser.parseString("""{"data":{"commands":"nope"}}""").asJsonObject
            ).isEmpty()
        )
        // Entries without a name are dropped rather than shown as `/`.
        assertEquals(
            1,
            PiCommand.parseResponse(
                JsonParser.parseString(
                    """{"data":{"commands":[{"description":"x"},{"name":"ok"}]}}"""
                ).asJsonObject
            ).size,
        )
    }

    // ------------------------------------------------------------ composer wiring

    fun testTypingSlashOffersEveryCommand() {
        val panel = ChatPanel(project)
        try {
            panel.commandRegistryForTest().seedForTest(sampleCommands())
            panel.inputForTest().text = "/"
            panel.inputForTest().caretPosition = 1
            UIUtil.dispatchAllInvocationEvents()

            val state = panel.commandCompletionForTest()
            assertTrue(state is ChatPanel.CommandCompletion.Matches)
            assertEquals(
                dev.pi.gui.commands.BuiltinCommands.ALL.size + 4,
                (state as ChatPanel.CommandCompletion.Matches).commands.size,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testTypingNarrowsTheOfferedCommands() {
        val panel = ChatPanel(project)
        try {
            panel.commandRegistryForTest().seedForTest(sampleCommands())
            panel.inputForTest().text = "/fix"
            panel.inputForTest().caretPosition = 4
            UIUtil.dispatchAllInvocationEvents()

            val state = panel.commandCompletionForTest() as ChatPanel.CommandCompletion.Matches
            assertEquals(listOf("fix-tests"), state.commands.map { it.name })
        } finally {
            panel.dispose()
        }
    }

    fun testOrdinaryTextLeavesTheCompletionClosed() {
        val panel = ChatPanel(project)
        try {
            panel.commandRegistryForTest().seedForTest(sampleCommands())
            panel.inputForTest().text = "refactor this and/or that"
            panel.inputForTest().caretPosition = 25
            UIUtil.dispatchAllInvocationEvents()

            assertTrue(
                panel.commandCompletionForTest() is ChatPanel.CommandCompletion.Hidden,
            )
        } finally {
            panel.dispose()
        }
    }

    /** An unknown command keeps the popup open on "no match" rather than silently closing it. */
    fun testUnknownCommandStaysOpenWithNoMatches() {
        val panel = ChatPanel(project)
        try {
            panel.commandRegistryForTest().seedForTest(sampleCommands())
            panel.inputForTest().text = "/zzzz"
            panel.inputForTest().caretPosition = 5
            UIUtil.dispatchAllInvocationEvents()

            val state = panel.commandCompletionForTest()
            assertTrue(state is ChatPanel.CommandCompletion.Matches)
            assertTrue((state as ChatPanel.CommandCompletion.Matches).commands.isEmpty())
        } finally {
            panel.dispose()
        }
    }

    fun testRegistryCachesAndInvalidates() {
        val builtins = dev.pi.gui.commands.BuiltinCommands.ALL.size
        val registry = dev.pi.gui.commands.CommandRegistry(null)
        assertFalse(registry.hasLoaded())
        // Built-ins are static, so they are offered before anything has been fetched.
        assertEquals(builtins, registry.snapshot().size)

        registry.seedForTest(sampleCommands())
        assertTrue(registry.hasLoaded())
        assertEquals(builtins + 4, registry.snapshot().size)

        registry.invalidate()
        assertFalse(registry.hasLoaded())
        assertEquals(builtins, registry.snapshot().size)
    }

    /** pi skips extension commands whose name collides with a built-in; so does the popup. */
    fun testFetchedCommandsNeverShadowABuiltin() {
        val registry = dev.pi.gui.commands.CommandRegistry(null)
        registry.seedForTest(
            listOf(PiCommand("compact", "an extension trying to take the name", "extension"))
        )
        val compact = registry.snapshot().filter { it.name == "compact" }
        assertEquals(1, compact.size)
        assertEquals(dev.pi.gui.commands.BuiltinCommands.SOURCE, compact.single().source)
    }

    // ------------------------------------------------------------- key bindings

    /**
     * With the popup closed these keys must behave exactly as before the feature existed. Swing
     * only leaves an event unconsumed when the bound action reports itself disabled — an
     * always-enabled Escape binding would permanently swallow the IDE's own Escape.
     */
    fun testNavigationKeysStayOutOfTheWayWhenTheListIsClosed() {
        val panel = ChatPanel(project)
        try {
            val am = panel.inputForTest().actionMap
            assertFalse(
                "Escape must fall through to the IDE while the list is closed",
                am.get("pi.command.cancel").isEnabled,
            )
            // Up/Down/Tab have real text-area actions behind them, so they stay enabled and
            // delegate rather than dropping the keystroke.
            assertTrue(am.get("pi.command.next").isEnabled)
            assertTrue(am.get("pi.command.previous").isEnabled)
            assertTrue(am.get("pi.command.complete").isEnabled)
        } finally {
            panel.dispose()
        }
    }

    fun testArrowKeysStillMoveTheCaretWhenTheListIsClosed() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            // The caret actions walk the text view, which only acquires real geometry (font
            // metrics, line heights) once it has been painted. setSize/doLayout alone leave the
            // view unlayed on some platforms, making "move down" a no-op; painting into an
            // offscreen image forces the layout without needing a visible window.
            panel.setSize(600, 400)
            panel.doLayout()
            input.setSize(400, 120)
            input.doLayout()
            val image = java.awt.image.BufferedImage(400, 120, java.awt.image.BufferedImage.TYPE_INT_RGB)
            input.text = "first\nsecond"
            input.paint(image.createGraphics())
            input.caretPosition = 0
            input.actionMap.get("pi.command.next")
                .actionPerformed(java.awt.event.ActionEvent(input, 0, "down"))
            assertTrue("caret should have moved down a line", input.caretPosition > 0)
        } finally {
            panel.dispose()
        }
    }

    // ---------------------------------------------------------------- insertion

    fun testChoosingACommandInsertsItWithATrailingSpace() {
        val panel = ChatPanel(project)
        try {
            panel.inputForTest().text = "/fi"
            panel.insertCommandForTest(PiCommand("fix-tests", null, "prompt"))
            assertEquals("/fix-tests ", panel.composerText())
        } finally {
            panel.dispose()
        }
    }

    /** Arguments already typed after the command must survive the completion. */
    fun testChoosingACommandKeepsTheArgumentsAlreadyTyped() {
        val panel = ChatPanel(project)
        try {
            panel.inputForTest().text = "/fi the auth suite"
            panel.insertCommandForTest(PiCommand("fix-tests", null, "prompt"))
            assertEquals("/fix-tests the auth suite", panel.composerText())
        } finally {
            panel.dispose()
        }
    }

    fun testSkillCommandKeepsItsPrefixWhenInserted() {
        val panel = ChatPanel(project)
        try {
            panel.inputForTest().text = "/xlsx"
            panel.insertCommandForTest(PiCommand("skill:xlsx", null, "skill"))
            assertEquals("/skill:xlsx ", panel.composerText())
        } finally {
            panel.dispose()
        }
    }

    // ------------------------------------------------------------ outgoing message

    /**
     * A command has to stay at offset 0 or pi stops seeing it as a command — so attachment
     * mentions move behind it instead of in front.
     */
    fun testAttachmentMentionsFollowACommandInsteadOfLeadingIt() {
        assertEquals(
            "/fix-tests @src/App.kt",
            ChatPanel.composeMessage("/fix-tests", "@src/App.kt"),
        )
    }

    fun testAttachmentMentionsStillLeadOrdinaryProse() {
        assertEquals(
            "@src/App.kt explain this",
            ChatPanel.composeMessage("explain this", "@src/App.kt"),
        )
    }

    fun testComposeMessageDropsEmptyParts() {
        assertEquals("/compact-notes", ChatPanel.composeMessage("/compact-notes", ""))
        assertEquals("@a.kt", ChatPanel.composeMessage("", "@a.kt"))
    }
}
