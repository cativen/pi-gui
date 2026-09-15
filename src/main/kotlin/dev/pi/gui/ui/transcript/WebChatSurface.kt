package dev.pi.gui.ui.transcript

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.PiMessage
import dev.pi.gui.rpc.PiJson
import dev.pi.gui.rpc.asStringOrNull
import dev.pi.gui.ui.markdown.Markdown
import dev.pi.gui.web.HtmlRenderer
import dev.pi.gui.web.PiWebView
import dev.pi.gui.web.WebPage
import dev.pi.gui.web.WebTheme
import javax.swing.JComponent

/**
 * The conversation, edits strip and composer, rendered by Chromium.
 *
 * One browser hosts all three because they are stacked and share a scroll context; two would mean
 * two render processes for one panel. The plugin still decides everything — it sends HTML for
 * messages and small JSON events for the rest — so the page holds no conversation state of its own.
 */
class WebChatSurface(
    private val project: Project,
    /** Copying a code block goes through the IDE so it reaches the system clipboard. */
    private val onCopy: (String) -> Unit,
    /** Swapped for a recorder in tests; nothing headless can start Chromium. */
    page: ((JsonObject) -> Unit) -> WebPage = { PiWebView("chat", it) },
) : ChatSurface {

    override var onLoadEarlier: (() -> Unit)? = null
    override var onSend: ((String) -> Unit)? = null
    override var onAbort: (() -> Unit)? = null
    override var onAttach: (() -> Unit)? = null
    override var onRemoveAttachment: ((String) -> Unit)? = null
    override var onDropFiles: ((List<String>) -> Unit)? = null
    override var onPasteClipboard: (() -> Unit)? = null
    override var onCompact: (() -> Unit)? = null
    override var onSelectProvider: ((String) -> Unit)? = null
    override var onSelectModel: ((String) -> Unit)? = null
    override var onSelectThinking: ((String) -> Unit)? = null
    override var onOpenDiff: ((String) -> Unit)? = null
    override var onRequestCommands: (() -> Unit)? = null
    override var onNewSession: (() -> Unit)? = null
    override var onRefreshSessions: (() -> Unit)? = null
    override var onToggleSidebar: (() -> Unit)? = null
    override var onOpenSettings: (() -> Unit)? = null
    override var onSelectSession: ((String) -> Unit)? = null
    override var onRenameSession: ((String) -> Unit)? = null
    override var onDeleteSession: ((String) -> Unit)? = null

    private val view: WebPage = page { handle(it) }

    /**
     * Last known composer contents.
     *
     * Updated when the page sends, and when the plugin sets the text — deliberately not on every
     * keystroke, which would put a bridge message behind each character.
     */
    private var composerText: String = ""

    /** What the page is showing, for the windowing assertions in tests. */
    private var rendered = 0

    init {
        Disposer.register(this, view)
        pushTheme()
        pushStrings()
    }

    override val component: JComponent get() = view.component

    private fun handle(message: JsonObject) {
        val text = { key: String -> message.get(key)?.asStringOrNull().orEmpty() }
        when (message.get("type")?.asStringOrNull()) {
            "ready" -> {
                pushTheme()
                pushStrings()
            }
            "loadEarlier" -> onLoadEarlier?.invoke()
            "copy" -> onCopy(text("text"))
            "send" -> {
                composerText = text("text")
                onSend?.invoke(composerText)
            }
            "abort" -> onAbort?.invoke()
            "attach" -> onAttach?.invoke()
            "removeAttachment" -> onRemoveAttachment?.invoke(text("id"))
            "pasteClipboard" -> onPasteClipboard?.invoke()
            "dropFiles" -> onDropFiles?.invoke(
                PiJson.asArray(message.get("paths"))?.mapNotNull { it.asStringOrNull() }.orEmpty()
            )
            "compact" -> onCompact?.invoke()
            "setProvider" -> onSelectProvider?.invoke(text("id"))
            "setModel" -> onSelectModel?.invoke(text("id"))
            "setThinking" -> onSelectThinking?.invoke(text("level"))
            "openDiff" -> onOpenDiff?.invoke(text("path"))
            "commands" -> onRequestCommands?.invoke()
            "newSession" -> onNewSession?.invoke()
            "refreshSessions" -> onRefreshSessions?.invoke()
            "toggleSidebar" -> onToggleSidebar?.invoke()
            "openSettings" -> onOpenSettings?.invoke()
            "selectSession" -> onSelectSession?.invoke(text("path"))
            "renameSession" -> onRenameSession?.invoke(text("path"))
            "deleteSession" -> onDeleteSession?.invoke(text("path"))
        }
    }

    private fun pushTheme() {
        view.post(mapOf("type" to "theme", "vars" to WebTheme.variables()))
    }

    private fun pushStrings() {
        view.post(
            mapOf(
                "type" to "i18n",
                "strings" to mapOf(
                    "placeholder" to PiBundle.message("chat.input.placeholder"),
                    "send" to PiBundle.message("chat.send"),
                    "queue" to PiBundle.message("chat.queue"),
                    "stop" to PiBundle.message("chat.stop"),
                    "attach" to PiBundle.message("chat.attach.short"),
                    "removeAttachment" to PiBundle.message("chat.attachment.remove"),
                    "provider" to PiBundle.message("chat.provider"),
                    "modelNone" to PiBundle.message("chat.model.none"),
                    "thinkingPrefix" to PiBundle.message("chat.thinking.prefix"),
                    "thinkingNone" to PiBundle.message("chat.thinking.none"),
                    "compactPrefix" to PiBundle.message("chat.compact.prefix"),
                    "compactAuto" to PiBundle.message("chat.compact.auto"),
                    "compactNow" to PiBundle.message("chat.compact.now"),
                    "contextPrefix" to PiBundle.message("chat.context.prefix"),
                    "emptyTitle" to PiBundle.message("chat.empty.title"),
                    "emptySubtitle" to PiBundle.message("chat.empty.subtitle"),
                    "working" to PiBundle.message("status.thinking"),
                    "noCommand" to PiBundle.message("chat.commands.none"),
                    "newSession" to PiBundle.message("toolbar.newSession"),
                    "refresh" to PiBundle.message("toolbar.refresh"),
                    "toggleSessions" to PiBundle.message("toolbar.toggleSessions"),
                    "settings" to PiBundle.message("toolbar.settings"),
                    "sessionsEmpty" to PiBundle.message("sessions.empty"),
                    "renameSession" to PiBundle.message("sessions.rename"),
                    "deleteSession" to PiBundle.message("sessions.delete"),
                ),
            )
        )
    }

    // ----------------------------------------------------------- transcript

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

    override fun applySettings() {
        pushTheme()
        pushStrings()
    }

    override fun scrollToBottom() {
        view.post(mapOf("type" to "scrollToBottom"))
    }

    /**
     * The page tracks its own scroll position and only follows the newest message when the reader
     * was already at the bottom, so the plugin does not second-guess it.
     */
    override fun isNearBottom(): Boolean = true

    override fun renderedCount(): Int = rendered

    // ------------------------------------------------------------- composer

    override fun composerText(): String = composerText

    override fun setComposerText(text: String, focus: Boolean) {
        composerText = text
        view.post(mapOf("type" to "composer", "text" to text, "focus" to focus))
        if (focus) view.requestBrowserFocus()
    }

    override fun appendComposerText(text: String) {
        view.post(mapOf("type" to "appendComposer", "text" to text))
        view.requestBrowserFocus()
    }

    override fun focusComposer() {
        view.post(mapOf("type" to "focusInput"))
        view.requestBrowserFocus()
    }

    override fun setRunning(running: Boolean) {
        view.post(mapOf("type" to "running", "running" to running))
    }

    override fun setAttachments(items: List<ChatSurface.Attachment>) {
        view.post(
            mapOf(
                "type" to "attachments",
                "items" to items.map { mapOf("id" to it.id, "name" to it.name) },
            )
        )
    }

    override fun setModels(models: ChatSurface.ModelChoices) {
        view.post(
            mapOf(
                "type" to "models",
                "providers" to models.providers.map { choice(it) },
                "provider" to models.provider,
                "models" to models.models.map { choice(it) },
                "model" to models.model,
                "thinking" to models.thinking.map { choice(it) },
                "thinkingLevel" to models.thinkingLevel,
            )
        )
    }

    override fun setContext(label: String?, tooltip: String?) {
        view.post(mapOf("type" to "context", "label" to label, "tooltip" to tooltip))
    }

    override fun setCommands(items: List<ChatSurface.Command>) {
        view.post(
            mapOf(
                "type" to "commands",
                "items" to items.map { mapOf("name" to it.name, "description" to it.description) },
            )
        )
    }

    override fun setEdits(items: List<ChatSurface.Edit>) {
        view.post(
            mapOf(
                "type" to "edits",
                "items" to items.map {
                    mapOf("path" to it.path, "added" to it.added, "removed" to it.removed)
                },
            )
        )
    }

    override fun setSendOnEnter(sendOnEnter: Boolean) {
        view.post(mapOf("type" to "settings", "sendOnEnter" to sendOnEnter))
    }

    // ---------------------------------------------------------------- shell

    override fun setSessions(items: List<ChatSurface.Session>, selectedPath: String?) {
        view.post(
            mapOf(
                "type" to "sessions",
                "items" to items.map {
                    mapOf("path" to it.path, "title" to it.title, "at" to it.at)
                },
                "selected" to selectedPath,
            )
        )
    }

    override fun setSidebarVisible(visible: Boolean) {
        view.post(mapOf("type" to "sidebar", "visible" to visible))
    }

    private fun choice(c: ChatSurface.Choice) = mapOf("id" to c.id, "label" to c.label)

    private fun earlierRow(hiddenCount: Int): String {
        if (hiddenCount <= 0) return ""
        val label = Markdown.escapeHtml(PiBundle.message("chat.loadEarlier", hiddenCount))
        return """<div class="earlier-row"><button class="load-earlier">$label</button></div>"""
    }

    override fun dispose() {
        // `view` is registered with the Disposer; nothing else to unwind.
    }
}
