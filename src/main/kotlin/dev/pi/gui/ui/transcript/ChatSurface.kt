package dev.pi.gui.ui.transcript

/**
 * Everything below the status strip: the conversation, the edits strip and the composer.
 *
 * [TranscriptSurface] covers the conversation alone, which was phase one. This adds the composer,
 * because a wrapping Swing text area re-wraps its whole document on every keystroke to answer the
 * viewport's preferred-size query — the allocation that made typing stutter under memory
 * pressure. Everything above this interface (the agent lifecycle, commands, windowing, every
 * service) stays where it is.
 */
interface ChatSurface : TranscriptSurface {

    // ------------------------------------------------------------- composer

    /**
     * The composer's contents as last reported.
     *
     * A web view reports on send rather than on every keystroke, so this is the last known text,
     * not a live read. Callers that need the exact current value get it from [onSend].
     */
    fun composerText(): String

    /**
     * Replace the composer's contents. [focus] moves the caret there, which the "send path to Pi"
     * actions want and a plain clear does not.
     */
    fun setComposerText(text: String, focus: Boolean = false)

    /**
     * Append to whatever is already typed, separating with a space.
     *
     * The view does the joining rather than the plugin reading the text and writing it back:
     * keeping a live mirror of the composer on the Kotlin side would mean a bridge message per
     * keystroke, which is the cost this move was meant to remove.
     */
    fun appendComposerText(text: String)

    fun focusComposer()

    /** Stop button visible, compact disabled: a turn is in flight. */
    fun setRunning(running: Boolean)

    fun setAttachments(items: List<Attachment>)

    fun setModels(models: ModelChoices)

    /** Context-window usage, or null to hide the readout. */
    fun setContext(label: String?, tooltip: String?)

    /** Completion candidates for the `/` popup. */
    fun setCommands(items: List<Command>)

    fun setEdits(items: List<Edit>)

    /** Enter sends, or Shift+Enter does — mirrors the setting. */
    fun setSendOnEnter(sendOnEnter: Boolean)

    // ------------------------------------------------------------------ shell

    /**
     * The sessions recorded for this project, newest first, and which one is loaded.
     *
     * The sidebar and its toolbar sit in the same page as the conversation so they follow the
     * same stylesheet — a Swing list beside a Chromium transcript never matched, whichever theme
     * was picked. The plugin still reads, renames and deletes the session files; the page draws
     * the rows and reports which one was clicked.
     */
    fun setSessions(items: List<Session>, selectedPath: String?)

    fun setSidebarVisible(visible: Boolean)

    // ------------------------------------------------------------ callbacks

    var onSend: ((String) -> Unit)?
    var onAbort: (() -> Unit)?
    var onAttach: (() -> Unit)?
    var onRemoveAttachment: ((String) -> Unit)?
    var onDropFiles: ((List<String>) -> Unit)?
    var onPasteClipboard: (() -> Unit)?
    var onCompact: (() -> Unit)?
    var onSelectProvider: ((String) -> Unit)?
    var onSelectModel: ((String) -> Unit)?
    var onSelectThinking: ((String) -> Unit)?
    var onOpenDiff: ((String) -> Unit)?
    /** The popup opened and wants an up-to-date command list. */
    var onRequestCommands: (() -> Unit)?

    // The toolbar above the sidebar. Renaming and deleting still raise the IDE's own dialogs —
    // a modal built in HTML would not be the dialog the rest of the IDE shows.
    var onNewSession: (() -> Unit)?
    var onRefreshSessions: (() -> Unit)?
    var onToggleSidebar: (() -> Unit)?
    var onOpenSettings: (() -> Unit)?
    var onSelectSession: ((String) -> Unit)?
    var onRenameSession: ((String) -> Unit)?
    var onDeleteSession: ((String) -> Unit)?

    // --------------------------------------------------------------- types

    data class Attachment(val id: String, val name: String)

    data class Command(val name: String, val description: String)

    data class Edit(val path: String, val added: Int, val removed: Int)

    /** [path] is the session file, which is the row's identity on both sides of the bridge. */
    data class Session(val path: String, val title: String, val at: String)

    data class Choice(val id: String, val label: String)

    data class ModelChoices(
        val providers: List<Choice>,
        val provider: String?,
        val models: List<Choice>,
        val model: String?,
        val thinking: List<Choice>,
        val thinkingLevel: String?,
    )
}
