package dev.pi.gui.providers

/**
 * Which cc-switch family an imported provider belongs to — this decides which settings
 * page section lists it and which wire API pi uses for it.
 */
enum class ProviderKind { CLAUDE_CODE, CODEX }

/**
 * One provider imported from cc-switch, mirrored into pi's `models.json`.
 *
 * [id] is the pi provider id and always carries the managed `ccswitch-` prefix, so the
 * registry can clean up its own entries without ever touching providers the user wrote
 * by hand.
 */
data class ImportedProvider(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    /** Model ids served by this provider; at least one for a usable provider. */
    val models: List<String>,
    val defaultModel: String? = null,
    /** cc-switch's own provider id (a UUID), used to match on re-import. */
    val sourceId: String? = null,
    /** pi wire API: `anthropic-messages`, `openai-completions` or `openai-responses`. */
    val api: String,
)
