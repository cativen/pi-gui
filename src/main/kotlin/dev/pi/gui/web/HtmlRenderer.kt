package dev.pi.gui.web

import com.intellij.openapi.project.Project
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.markdown.CodeHighlighter
import dev.pi.gui.ui.markdown.Markdown

/**
 * Turns one transcript message into the HTML the web view displays.
 *
 * Markdown and syntax highlighting stay on the Kotlin side deliberately. Producing the HTML was
 * never the expensive part — 300 lines of Kotlin highlight in under 2ms — and going through the
 * IDE's own lexers colours a snippet exactly the way the editor would, which no bundled
 * JavaScript highlighter can match. What the browser takes over is the part Swing was bad at:
 * laying out and painting that HTML.
 */
object HtmlRenderer {

    /** Caps content that would otherwise stall rendering; agents routinely emit thousands of lines. */
    private const val MAX_RENDERED_LINES = 800
    private const val MAX_RENDERED_CHARS = 120_000

    fun render(project: Project?, message: PiMessage): String = when (message) {
        is PiMessage.User -> renderUser(message)
        is PiMessage.Assistant -> renderAssistant(project, message)
        is PiMessage.ToolResult -> renderToolResult(project, message)
        is PiMessage.Notice -> renderNotice(message)
    }

    private fun renderUser(message: PiMessage.User): String = buildString {
        val author = esc(PiBundle.message("message.you"))
        append("""<article class="msg msg-user" aria-label="$author"><div class="user-stack">""")
        append("""<div class="message-heading user-heading"><span>$author</span></div><div class="bubble"><div class="message-content">""")
        append(Markdown.proseToHtml(message.text))
        if (message.imageCount > 0) {
            append("""<p class="attached"><i>""")
            append(esc(PiBundle.message("message.imagesAttached", message.imageCount)))
            append("</i></p>")
        }
        append("</div></div></div></article>")
    }

    private fun renderAssistant(project: Project?, message: PiMessage.Assistant): String = buildString {
        val settings = PiSettings.getInstance()
        val author = esc(PiBundle.message("message.assistant"))
        append("""<article class="msg msg-assistant" aria-label="$author">""")
        append("""<div class="message-heading"><span class="assistant-mark" aria-hidden="true">π</span><span>$author</span></div>""")
        append("""<div class="message-content">""")

        message.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text ->
                    if (block.text.isNotBlank()) append(prose(project, block.text))

                is ContentBlock.Thinking ->
                    if (settings.showThinking && block.thinking.isNotBlank()) {
                        append(
                            details(
                                cls = "thinking",
                                title = esc(PiBundle.message("message.thinking")),
                                subtitle = null,
                                open = settings.expandThinking,
                                body = """<div class="thinking-body">${
                                    esc(truncate(block.thinking)).replace("\n", "<br>")
                                }</div>""",
                            )
                        )
                    }

                is ContentBlock.ToolCall -> append(renderToolCall(project, block, settings))

                is ContentBlock.Image -> append("""<p class="inline-image"><i>[image]</i></p>""")
            }
        }

        message.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            append("""<div class="error-box">""")
            append(esc(error).replace("\n", "<br>"))
            append("</div>")
        }

        footerFor(message)?.let { append(it) }
        append("</div></article>")
    }

    private fun renderToolCall(
        project: Project?,
        block: ContentBlock.ToolCall,
        settings: PiSettings,
    ): String {
        val args = truncate(block.argumentsText().takeIf { it.isNotBlank() } ?: "{}")
        return details(
            cls = "toolcall",
            title = esc(block.toolName),
            subtitle = esc(summarizeArgs(args)),
            open = settings.autoExpandToolCalls,
            body = codeBlock(project, "json", args),
        )
    }

    private fun renderToolResult(project: Project?, message: PiMessage.ToolResult): String {
        val text = truncate(message.text.ifBlank { "(no output)" })
        val lines = text.count { it == '\n' } + 1
        return """<article class="msg msg-tool">""" + details(
            cls = if (message.isError) "toolresult error" else "toolresult",
            title = esc(
                if (message.isError) PiBundle.message("message.error")
                else PiBundle.message("message.result")
            ),
            subtitle = esc(PiBundle.message("message.lines", lines)),
            open = false,
            body = codeBlock(project, null, text),
        ) + "</article>"
    }

    private fun renderNotice(message: PiMessage.Notice): String =
        """<div class="msg msg-notice" role="status"><span>${esc(message.text).replace("\n", "<br>")}</span></div>"""

    // ------------------------------------------------------------------ pieces

    /**
     * `<details>` rather than a scripted toggle: the browser handles the open/closed state, and
     * a closed block costs nothing until it is opened.
     */
    private fun details(
        cls: String,
        title: String,
        subtitle: String?,
        open: Boolean,
        body: String,
    ): String = buildString {
        append("""<details class="sec $cls"""").append(if (open) " open" else "").append(">")
        append("""<summary><span class="sec-title">""").append(title).append("</span>")
        if (!subtitle.isNullOrBlank()) {
            append("""<span class="sec-sub">""").append(subtitle).append("</span>")
        }
        append("</summary>")
        append(body)
        append("</details>")
    }

    /** Prose plus fenced code, each fence highlighted by the IDE's lexer for that language. */
    private fun prose(project: Project?, markdown: String): String = buildString {
        Markdown.split(truncate(markdown)).forEach { segment ->
            when (segment) {
                is Markdown.Segment.Prose -> append(Markdown.proseToHtml(segment.markdown))
                is Markdown.Segment.Code -> append(codeBlock(project, segment.language, segment.code))
            }
        }
    }

    private fun codeBlock(project: Project?, language: String?, code: String): String {
        val body = CodeHighlighter.toHtml(project, language, truncate(code))
        val label = language?.takeIf { it.isNotBlank() }?.let { esc(it) }.orEmpty()
        return buildString {
            append("""<div class="code-wrap"><div class="code-head"><span class="code-lang">""")
            append(label)
            val copy = esc(PiBundle.message("code.copy"))
            append("""</span><button class="code-copy" data-copy="1" type="button" title="$copy" aria-label="$copy">""")
            append("""<svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="6" width="8.5" height="8.5" rx="1.5"/><path d="M4.5 11.5h-1V3.5h8v1"/></svg><span>""")
            append(copy)
            append("""</span></button></div><pre class="code">""")
            append(body)
            append("</pre></div>")
        }
    }

    /** Model, tokens, cache hit rate, elapsed time and cost, when the message carries them. */
    private fun footerFor(message: PiMessage.Assistant): String? {
        val usage = message.usage
        val model = message.model
        if (usage == null && model == null && message.durationMs == null) return null

        val parts = mutableListOf<String>()
        model?.let { parts.add(it) }
        usage?.let {
            if (it.totalTokens() > 0) parts.add("${SessionStore.formatTokens(it.totalTokens())} tokens")
            // Only meaningful once the provider actually reported cached prompt tokens.
            if (it.cacheRead > 0) {
                it.cacheHitRate()?.let { rate -> parts.add("cache ${SessionStore.formatPercent(rate)}") }
            }
        }
        message.durationMs?.let { parts.add(SessionStore.formatDuration(it)) }
        usage?.let { if (it.costTotal > 0) parts.add(String.format("$%.4f", it.costTotal)) }
        if (parts.isEmpty()) return null

        val tooltip = buildString {
            usage?.let {
                append("Prompt ").append(SessionStore.formatTokens(it.promptTokens()))
                append(" · output ").append(SessionStore.formatTokens(it.output))
                if (it.cacheRead > 0) {
                    append("\nCache read ").append(SessionStore.formatTokens(it.cacheRead))
                    append(" of ").append(SessionStore.formatTokens(it.promptTokens())).append(" prompt tokens")
                }
                if (it.cacheWrite > 0) {
                    append("\nCache written ").append(SessionStore.formatTokens(it.cacheWrite))
                }
            }
            message.durationMs?.let { append("\nTook ").append(SessionStore.formatDuration(it)) }
        }.trim()

        return """<div class="usage" title="${esc(tooltip)}">${esc(parts.joinToString("  ·  "))}</div>"""
    }

    private fun truncate(text: String): String {
        val lines = text.lineSequence().take(MAX_RENDERED_LINES + 1).toList()
        val overLines = lines.size > MAX_RENDERED_LINES
        val overChars = text.length > MAX_RENDERED_CHARS
        if (!overLines && !overChars) return text

        val clipped = if (overLines) lines.take(MAX_RENDERED_LINES).joinToString("\n") else text
        val body = if (clipped.length > MAX_RENDERED_CHARS) clipped.take(MAX_RENDERED_CHARS) else clipped
        val totalLines = text.count { it == '\n' } + 1
        return body + "\n\n… " + PiBundle.message("message.truncated", totalLines)
    }

    /** One-line hint of what a tool call is doing, shown next to the collapsed header. */
    private fun summarizeArgs(args: String): String {
        val flat = args.replace(Regex("\\s+"), " ").trim()
            .removePrefix("{").removeSuffix("}").trim()
        return if (flat.length > 90) flat.take(90) + "…" else flat
    }

    private fun esc(text: String): String = Markdown.escapeHtml(text)
}
