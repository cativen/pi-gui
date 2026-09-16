package dev.pi.gui.ui.transcript

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.PiMessage
import dev.pi.gui.rpc.asStringOrNull
import dev.pi.gui.ui.markdown.Markdown
import dev.pi.gui.web.HtmlRenderer
import dev.pi.gui.web.PiWebView
import dev.pi.gui.web.WebTheme
import javax.swing.JComponent

/**
 * The conversation rendered by Chromium instead of Swing.
 *
 * Messages arrive as HTML the plugin has already produced, so the browser only lays out and
 * paints — the two things a transcript of `JEditorPane`s was worst at. Scrolling, collapsing and
 * text selection come for free from the platform rather than from hand-written components.
 */
class WebTranscriptSurface(
    private val project: Project,
    /** Copying a code block goes through the IDE so it lands on the system clipboard. */
    private val onCopy: (String) -> Unit,
) : TranscriptSurface {

    override var onLoadEarlier: (() -> Unit)? = null

    private val view = PiWebView("transcript") { message -> handle(message) }

    /** Mirrors what the page is showing, for the windowing assertions in tests. */
    private var rendered = 0

    init {
        Disposer.register(this, view)
        pushTheme()
    }

    override val component: JComponent get() = view

    private fun handle(message: com.google.gson.JsonObject) {
        when (message.get("type")?.asStringOrNull()) {
            "ready" -> pushTheme()
            "loadEarlier" -> onLoadEarlier?.invoke()
            "copy" -> message.get("text")?.asStringOrNull()?.let(onCopy)
        }
    }

    private fun pushTheme() {
        view.post(mapOf("type" to "theme", "vars" to WebTheme.variables()))
    }

    override fun setMessages(messages: List<PiMessage>, hiddenCount: Int) {
        rendered = messages.size
        view.post(
            mapOf(
                "type" to "messages",
                "earlier" to earlierRow(hiddenCount),
                "html" to messages.joinToString("") { HtmlRenderer.render(project, it) },
            )
        )
    }

    override fun appendMessage(message: PiMessage) {
        rendered++
        view.post(mapOf("type" to "append", "html" to HtmlRenderer.render(project, message)))
    }

    override fun prependMessages(messages: List<PiMessage>, hiddenCount: Int) {
        rendered += messages.size
        view.post(
            mapOf(
                "type" to "prepend",
                "earlier" to earlierRow(hiddenCount),
                "html" to messages.joinToString("") { HtmlRenderer.render(project, it) },
            )
        )
    }

    override fun setStreaming(message: PiMessage?) {
        view.post(
            mapOf(
                "type" to "streaming",
                "html" to (message?.let { HtmlRenderer.render(project, it) } ?: ""),
            )
        )
    }

    override fun showEmptyState(projectPath: String?) {
        rendered = 0
        view.post(
            mapOf(
                "type" to "empty",
                "title" to PiBundle.message("chat.empty.title"),
                "path" to (projectPath ?: ""),
            )
        )
    }

    override fun applySettings() = pushTheme()

    override fun scrollToBottom() {
        view.post(mapOf("type" to "scrollToBottom"))
    }

    /**
     * The page keeps the scroll position, so the plugin does not track it. Reporting "following
     * the newest message" keeps the append path from second-guessing the view; the script only
     * scrolls when the reader was already at the bottom.
     */
    override fun isNearBottom(): Boolean = true

    override fun renderedCount(): Int = rendered

    private fun earlierRow(hiddenCount: Int): String {
        if (hiddenCount <= 0) return ""
        val label = Markdown.escapeHtml(PiBundle.message("chat.loadEarlier", hiddenCount))
        return """<div class="earlier-row"><button class="load-earlier">$label</button></div>"""
    }

    override fun dispose() {
        // `view` is registered with the Disposer; nothing else to unwind.
    }
}
