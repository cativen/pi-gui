package dev.pi.gui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.components.CodeBlock
import dev.pi.gui.ui.components.CollapsibleSection
import dev.pi.gui.ui.components.HtmlBlock
import dev.pi.gui.ui.components.RoundedPanel
import dev.pi.gui.ui.components.StackPanel
import java.awt.BorderLayout
import javax.swing.JPanel

/** Builds the native component tree for one transcript message. */
object MessageRenderer {

    fun render(project: Project?, message: PiMessage): StackPanel = when (message) {
        is PiMessage.User -> renderUser(project, message)
        is PiMessage.Assistant -> renderAssistant(project, message)
        is PiMessage.ToolResult -> renderToolResult(project, message)
        is PiMessage.Notice -> renderNotice(message)
    }

    private fun renderUser(project: Project?, message: PiMessage.User): StackPanel {
        val outer = StackPanel(0).apply { border = JBUI.Borders.empty(4, 0) }
        val bubble = RoundedPanel(
            background = PiTheme.userBubbleBg,
            outline = PiTheme.userBubbleBorder,
            gap = 2,
        )
        bubble.add(MarkdownView(project, message.text))
        if (message.imageCount > 0) {
            bubble.add(
                HtmlBlock(
                    "<p><i>" + PiBundle.message("message.imagesAttached", message.imageCount) + "</i></p>"
                )
            )
        }
        outer.add(bubble)
        return outer
    }

    private fun renderAssistant(project: Project?, message: PiMessage.Assistant): StackPanel {
        val settings = PiSettings.getInstance()
        val panel = StackPanel(2).apply { border = JBUI.Borders.empty(4, 0, 8, 0) }

        message.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text ->
                    if (block.text.isNotBlank()) panel.add(MarkdownView(project, block.text))

                is ContentBlock.Thinking -> {
                    if (settings.showThinking && block.thinking.isNotBlank()) {
                        val thinking = truncate(block.thinking)
                        panel.add(
                            CollapsibleSection(
                                title = PiBundle.message("message.thinking"),
                                contentFactory = {
                                    StackPanel(0).apply {
                                        add(
                                            HtmlBlock(
                                                "<p><span style=\"color:${PiTheme.toHex(PiTheme.thinkingFg)}\">" +
                                                    dev.pi.gui.ui.markdown.Markdown.escapeHtml(thinking)
                                                        .replace("\n", "<br>") +
                                                    "</span></p>"
                                            )
                                        )
                                    }
                                },
                                expanded = settings.expandThinking,
                                accent = PiTheme.thinkingFg,
                            )
                        )
                    }
                }

                is ContentBlock.ToolCall -> panel.add(renderToolCall(project, block, settings))

                is ContentBlock.Image ->
                    panel.add(HtmlBlock("<p><i>[image]</i></p>"))
            }
        }

        message.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            val box = RoundedPanel(background = PiTheme.errorBg, outline = null, gap = 0)
            box.add(
                HtmlBlock(
                    "<p><span style=\"color:${PiTheme.toHex(PiTheme.errorFg)}\">" +
                        dev.pi.gui.ui.markdown.Markdown.escapeHtml(error).replace("\n", "<br>") +
                        "</span></p>"
                )
            )
            panel.add(box)
        }

        footerFor(message)?.let { panel.add(it) }
        return panel
    }

    private fun renderToolCall(
        project: Project?,
        block: ContentBlock.ToolCall,
        settings: PiSettings,
    ): CollapsibleSection {
        val args = truncate(block.argumentsText().takeIf { it.isNotBlank() } ?: "{}")
        return CollapsibleSection(
            title = block.toolName,
            contentFactory = { StackPanel(0).apply { add(CodeBlock(project, "json", args)) } },
            expanded = settings.autoExpandToolCalls,
            accent = PiTheme.linkFg(),
            icon = AllIcons.Nodes.Plugin,
        ).apply {
            setSubtitle(summarizeArgs(args))
        }
    }

    /**
     * Caps content that would otherwise stall the UI. Agents routinely emit thousands of lines,
     * and both the lexer and Swing's HTML layout are linear in the input.
     */
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

    private const val MAX_RENDERED_LINES = 800
    private const val MAX_RENDERED_CHARS = 120_000

    /** One-line hint of what a tool call is doing, shown next to the collapsed header. */
    private fun summarizeArgs(args: String): String {
        val flat = args.replace(Regex("\\s+"), " ").trim()
            .removePrefix("{").removeSuffix("}").trim()
        return if (flat.length > 90) flat.take(90) + "…" else flat
    }

    private fun renderToolResult(project: Project?, message: PiMessage.ToolResult): StackPanel {
        val panel = StackPanel(0).apply { border = JBUI.Borders.empty(0, 0, 4, 0) }
        val text = truncate(message.text.ifBlank { "(no output)" })

        val title = if (message.isError) PiBundle.message("message.error") else PiBundle.message("message.result")
        panel.add(
            CollapsibleSection(
                title = title,
                contentFactory = { StackPanel(0).apply { add(CodeBlock(project, null, text)) } },
                expanded = false,
                accent = if (message.isError) PiTheme.errorFg else PiTheme.mutedFg(),
                icon = if (message.isError) AllIcons.General.Error else AllIcons.General.Information,
            ).apply {
                val lines = text.count { it == '\n' } + 1
                setSubtitle(PiBundle.message("message.lines", lines))
            }
        )
        return panel
    }

    private fun renderNotice(message: PiMessage.Notice): StackPanel {
        val panel = StackPanel(0).apply { border = JBUI.Borders.empty(6, 0) }
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBLabel(message.text).apply {
                    foreground = PiTheme.noticeFg
                    font = font.deriveFont(font.size2D - 1f)
                },
                BorderLayout.CENTER,
            )
        }
        panel.add(row)
        return panel
    }

    /** Model, tokens, cache hit rate, elapsed time and cost, when the message carries them. */
    private fun footerFor(message: PiMessage.Assistant): JPanel? {
        val usage = message.usage
        val model = message.model
        if (usage == null && model == null && message.durationMs == null) return null

        val parts = mutableListOf<String>()
        model?.let { parts.add(it) }
        usage?.let {
            if (it.totalTokens() > 0) parts.add("${SessionStore.formatTokens(it.totalTokens())} tokens")
            // Only meaningful once the provider actually reported cached prompt tokens.
            if (it.cacheRead > 0) {
                it.cacheHitRate()?.let { rate ->
                    parts.add("cache ${SessionStore.formatPercent(rate)}")
                }
            }
        }
        message.durationMs?.let { parts.add(SessionStore.formatDuration(it)) }
        usage?.let { if (it.costTotal > 0) parts.add(String.format("$%.4f", it.costTotal)) }
        if (parts.isEmpty()) return null

        val tooltip = buildString {
            append("<html>")
            usage?.let {
                append("Prompt ").append(SessionStore.formatTokens(it.promptTokens()))
                append(" · output ").append(SessionStore.formatTokens(it.output)).append("<br>")
                if (it.cacheRead > 0) {
                    append("Cache read ").append(SessionStore.formatTokens(it.cacheRead))
                    append(" of ").append(SessionStore.formatTokens(it.promptTokens()))
                    append(" prompt tokens<br>")
                }
                if (it.cacheWrite > 0) {
                    append("Cache written ").append(SessionStore.formatTokens(it.cacheWrite)).append("<br>")
                }
            }
            message.durationMs?.let { append("Took ").append(SessionStore.formatDuration(it)) }
            append("</html>")
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            add(
                JBLabel(parts.joinToString("  ·  ")).apply {
                    foreground = PiTheme.mutedFg()
                    font = font.deriveFont(font.size2D - 2f)
                    toolTipText = tooltip
                },
                BorderLayout.WEST,
            )
        }
    }
}
