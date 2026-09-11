package dev.pi.gui.commands

/**
 * pi's built-in commands, copied from its own `BUILTIN_SLASH_COMMANDS` table (pi 0.84.2,
 * `dist/core/slash-commands.js`) so the names and wording match what the CLI shows.
 *
 * These never come back from `get_commands` — pi's docs note they are "handled only in interactive
 * mode and would not execute if sent via prompt". So the plugin cannot forward them to the agent;
 * it has to *implement* them. [Availability] records which ones it can.
 */
object BuiltinCommands {

    enum class Availability {
        /** The plugin performs this itself, through RPC or its own UI. */
        NATIVE,

        /**
         * No RPC equivalent exists and the action needs pi's terminal UI (auth prompts, trust
         * decisions, interactive pickers). Choosing it explains that rather than sending the text
         * to the model as prose.
         */
        CLI_ONLY,
    }

    data class Builtin(
        val name: String,
        val description: String,
        val argumentHint: String? = null,
        val availability: Availability = Availability.NATIVE,
    )

    val ALL: List<Builtin> = listOf(
        Builtin("settings", "Open settings menu"),
        Builtin("model", "Select model (opens selector UI)", "<provider/model>"),
        Builtin("tree", "Navigate session tree (switch branches)"),
        Builtin("thinking", "Set thinking level", "<level>"),
        Builtin(
            "scoped-models", "Enable/disable models for Ctrl+P cycling",
            availability = Availability.CLI_ONLY,
        ),
        Builtin("export", "Export session (HTML default, or specify path: .html/.jsonl)", "[path]"),
        Builtin(
            "import", "Import and resume a session from a JSONL file",
            availability = Availability.CLI_ONLY,
        ),
        Builtin(
            "share", "Share session as a secret GitHub gist",
            availability = Availability.CLI_ONLY,
        ),
        Builtin("copy", "Copy last agent message to clipboard"),
        Builtin("name", "Set session display name", "[name]"),
        Builtin("session", "Show session info and stats"),
        Builtin(
            "changelog", "Show changelog entries",
            availability = Availability.CLI_ONLY,
        ),
        Builtin("hotkeys", "Show all keyboard shortcuts"),
        Builtin("fork", "Create a new fork from a previous user message"),
        Builtin("clone", "Duplicate the current session at the current position"),
        Builtin(
            "trust", "Save project trust decision for future sessions",
            availability = Availability.CLI_ONLY,
        ),
        Builtin(
            "login", "Configure provider authentication", "<provider>",
            availability = Availability.CLI_ONLY,
        ),
        Builtin(
            "logout", "Remove provider authentication",
            availability = Availability.CLI_ONLY,
        ),
        Builtin("new", "Start a new session"),
        Builtin("compact", "Manually compact the session context"),
        Builtin("resume", "Resume a different session"),
        Builtin("reload", "Reload keybindings, extensions, skills, prompts, themes, and context files"),
        Builtin("quit", "Quit pi"),
    )

    private val byName: Map<String, Builtin> = ALL.associateBy { it.name }

    fun find(name: String): Builtin? = byName[name]

    /** The completion entries. `builtin` is our own source tag; pi does not report one. */
    val AS_COMMANDS: List<PiCommand> = ALL.map { builtin ->
        PiCommand(
            name = builtin.name,
            description = builtin.argumentHint
                ?.let { "$it — ${builtin.description}" }
                ?: builtin.description,
            source = SOURCE,
        )
    }

    const val SOURCE = "builtin"

    /**
     * Splits `/name rest` into the command name and its arguments.
     * Returns null when the text is not a command at all.
     */
    fun parseInvocation(text: String): Pair<String, String>? {
        if (!text.startsWith("/")) return null
        val body = text.drop(1)
        val name = body.takeWhile { !it.isWhitespace() }
        if (name.isEmpty()) return null
        return name to body.drop(name.length).trim()
    }
}
