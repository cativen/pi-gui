package dev.pi.gui.commands

import com.google.gson.JsonObject
import dev.pi.gui.rpc.asStringOrNull

/**
 * A slash command pi will accept as the start of a prompt.
 *
 * Only the three kinds pi reports over RPC exist here. Built-in TUI commands (`/settings`,
 * `/hotkeys`, …) are deliberately absent: pi's docs state they are handled only in interactive
 * mode and "would not execute if sent via prompt", so offering them would insert text that
 * silently reaches the model as prose.
 */
data class PiCommand(
    /** Invoked as `/name`. Skills carry pi's own `skill:` prefix. */
    val name: String,
    val description: String?,
    /** `extension`, `prompt` or `skill`; anything else is passed through as reported. */
    val source: String?,
    /** Absolute path to the file the command came from, when pi reports one. */
    val path: String? = null,
    /** `user`, `project` or `path` — absent for extensions. */
    val location: String? = null,
) {
    val insertText: String get() = "/$name "

    companion object {
        /**
         * Reads a `get_commands` response.
         *
         * Two payload shapes are accepted: the flat `path`/`location` fields the RPC docs show,
         * and the nested `sourceInfo` object pi 0.84 actually emits. Neither is documented as
         * stable, so a command is kept as long as it has a name.
         */
        fun parseResponse(response: JsonObject): List<PiCommand> {
            val data = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return emptyList()
            val array = data.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()

            return array.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = obj.get("name")?.asStringOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val sourceInfo = obj.get("sourceInfo")?.takeIf { it.isJsonObject }?.asJsonObject

                PiCommand(
                    name = name,
                    description = obj.get("description")?.asStringOrNull()?.takeIf { it.isNotBlank() },
                    source = obj.get("source")?.asStringOrNull(),
                    path = obj.get("path")?.asStringOrNull()
                        ?: sourceInfo?.get("path")?.asStringOrNull(),
                    location = obj.get("location")?.asStringOrNull()
                        ?: sourceInfo?.get("scope")?.asStringOrNull(),
                )
            }
        }

        /**
         * The command token the user is typing, or null when the caret is not in one.
         *
         * pi matches `^/([^\s]+)(?:\s+[\s\S]*)?$` against the *whole* message, so a command only
         * counts at offset 0 — `and/or` mid-sentence must not open the popup, and neither must a
         * caret that has already moved past the command into its arguments.
         */
        fun queryAt(text: String, caret: Int): String? {
            if (!text.startsWith("/")) return null
            if (caret !in 1..text.length) return null
            val token = text.drop(1).takeWhile { !it.isWhitespace() }
            // Caret must still be inside `/token`, i.e. at most just after its last character.
            if (caret > token.length + 1) return null
            return token.take(caret - 1)
        }

        /**
         * Prefix match on the name, then on the name with pi's `skill:` prefix dropped, so typing
         * `/xlsx` still finds `skill:xlsx`. Case-insensitive; exact prefixes rank first.
         */
        fun filter(commands: List<PiCommand>, query: String): List<PiCommand> {
            if (query.isEmpty()) return commands
            val needle = query.lowercase()
            return commands
                .mapNotNull { command ->
                    val name = command.name.lowercase()
                    val bare = name.substringAfter(':', name)
                    when {
                        name.startsWith(needle) -> 0 to command
                        bare.startsWith(needle) -> 1 to command
                        name.contains(needle) -> 2 to command
                        else -> null
                    }
                }
                .sortedBy { it.first }
                .map { it.second }
        }
    }
}
