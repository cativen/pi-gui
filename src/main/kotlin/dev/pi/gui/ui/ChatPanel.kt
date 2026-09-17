package dev.pi.gui.ui

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import dev.pi.gui.PiLocator
import dev.pi.gui.commands.BuiltinCommands
import dev.pi.gui.commands.CommandRegistry
import dev.pi.gui.commands.PiCommand
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.git.GitInfo
import dev.pi.gui.model.AgentPhase
import dev.pi.gui.model.Attachment
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.ModelOption
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.SessionInfo
import dev.pi.gui.providers.ProvidersRegistry
import dev.pi.gui.rpc.PiJson
import dev.pi.gui.rpc.PiRpcClient
import dev.pi.gui.rpc.StreamingAssistant
import dev.pi.gui.rpc.asDoubleOrNull
import dev.pi.gui.rpc.asLongOrNull
import dev.pi.gui.rpc.asStringOrNull
import dev.pi.gui.rpc.getAsJsonObjectOrNull
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.components.PiButton
import dev.pi.gui.ui.transcript.ChatSurface
import dev.pi.gui.ui.transcript.SwingTranscriptSurface
import dev.pi.gui.ui.transcript.WebChatSurface
import dev.pi.gui.web.PiWebView
import dev.pi.gui.ui.settings.PiSettingsDialog
import dev.pi.gui.ui.components.StackPanel
import dev.pi.gui.ui.components.RoundedBorder
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The conversation view: transcript, status strip and composer, wired to a `pi --mode rpc`
 * process. History is read straight from the session file; the agent process is only started
 * when the user actually sends something.
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(ChatPanel::class.java)

    /**
     * The conversation area. Chromium when the IDE has JCEF, the original Swing view otherwise —
     * some distributions ship without it and users can switch it off, and the plugin has to keep
     * working there.
     */
    private val surface: ChatSurface =
        if (useWebTranscript()) WebChatSurface(project, onCopy = { copyToClipboard(it) })
        else SwingChatAdapter()

    /** True when the conversation and composer live in the browser. */
    private val webMode: Boolean get() = surface !is SwingChatAdapter
    /**
     * Overriding `paste()` catches every route that ends at `JTextComponent.paste()` — the
     * right-click Paste menu item and programmatic callers. The keyboard route itself needs the
     * local shortcut registered by [installPasteShortcut] because the IDE keymap can consume
     * Cmd/Ctrl+V before Swing ever sees it.
     */
    private val input = object : JBTextArea() {
        override fun paste() {
            if (pasteAttachmentsFromClipboard()) return
            super.paste()
        }
    }
    /**
     * Icon-only footer buttons. They size themselves from the icon plus [PiButton]'s padding —
     * a fixed square here once clipped the icon (16px icon + 20px padding > 28px).
     */
    private val sendButton = PiButton(
        null, AllIcons.Actions.Execute, PiButton.Style.PRIMARY,
    ).apply {
        toolTipText = PiBundle.message("chat.send")
    }
    private val stopButton = PiButton(
        null, AllIcons.Actions.Suspend, PiButton.Style.SECONDARY,
    ).apply {
        toolTipText = PiBundle.message("chat.stop")
    }
    private val compactButton = PiButton(
        null, AllIcons.Actions.Collapseall, PiButton.Style.SECONDARY,
    ).apply {
        toolTipText = PiBundle.message("chat.compact")
    }

    /** Share of the model's context window the session currently occupies, when pi reports it. */
    private val contextLabel = JBLabel("")
    private var contextPercent: Double? = null
    private var contextTooltip: String? = null

    private val statusLabel = JBLabel(" ")
    private val branchLabel = JBLabel("")

    private val center = JPanel(java.awt.BorderLayout())

    /** Start of the in-flight assistant step, used for the live timer and the message footer. */
    private var stepStartedAt: Long? = null
    private var lastStepDurationMs: Long? = null

    /** How many trailing messages are currently materialised. */
    private var visibleLimit = VISIBLE_PAGE_SIZE

    /** Lines of message content the transcript window may lay out at once. */
    private var visibleLineBudget = INITIAL_LINE_BUDGET

    /** Bumped whenever the transcript is replaced, so stale chunk fills drop themselves. */
    private var fillGeneration = 0
    /** The "load earlier" row currently on top, if any; moved as the window grows. */
    private var loadEarlierRow: JComponent? = null
    /** Components produced by the last synchronous rebuild, before any chunk was filled in. */
    private var eagerComponentCount = 0

    private val attachments = mutableListOf<Attachment>()
    private val attachmentStrip = AttachmentStrip { removeAttachment(it) }

    private val commandRegistry = CommandRegistry(project.basePath?.let(::File))
    private val commandPopup = CommandPopup { insertCommand(it) }
    /** The composer shell the popup hangs above; set once the composer is built. */
    private var commandAnchor: JComponent? = null

    private val editsPanel = EditsPanel(project)
    private val providerCombo = JComboBox<ProviderOption>()
    private val modelCombo = JComboBox<ModelOption>()
    private val thinkingCombo = JComboBox<String>()

    /** Every model the agent offers, unfiltered; the model combo shows one provider's slice. */
    private var allModels: List<ModelOption> = emptyList()

    /** Friendly names for imported (`ccswitch-*`) providers, refreshed with the model list. */
    private var importedProviderLabels: Map<String, String> = emptyMap()

    /** Set once the saved provider/model selection has been (or decided not to be) restored. */
    private var restoredForClient: PiRpcClient? = null
    private var lastLiveProvider: String? = null
    private var lastLiveModel: String? = null
    private var lastLiveThinking: String? = null

    /** True while a `pi --mode rpc` process is being spawned; collapses concurrent starts. */
    private var agentStarting = false

    /** Bumped every time a start actually begins spawning; stale landings stand down. */
    private var startEpoch = 0

    /**
     * Set when the agent is stopped while a start is still in flight: the landing client is
     * discarded and, when another start is wanted, a fresh one is spawned for the new session.
     */
    private var discardInFlightStart = false

    /** Start requests waiting for an in-flight start; all see the same client (or null). */
    private val waitingForStart = ArrayDeque<(PiRpcClient?) -> Unit>()

    /**
     * Fingerprint of pi's provider files when the running agent started. pi reads models.json
     * once per process, so a registry change can only reach a live agent through a restart.
     */
    private var agentProvidersFingerprint: String? = null

    private val messages = mutableListOf<PiMessage>()
    private val streaming = StreamingAssistant()
    private var streamingComponent: JComponent? = null

    private var rpc: PiRpcClient? = null
    private var session: SessionInfo? = null

    /**
     * The session file the live agent is actually writing to.
     *
     * A brand-new chat has no [session] until pi creates the file and reports it on `get_state`,
     * and that path is what a backgrounded agent is keyed by — so it is remembered here.
     */
    private var liveSessionFile: String? = null

    /**
     * Agents left running after the user moved on, keyed by the session file they are writing.
     *
     * Starting or opening another session used to kill the agent mid-turn, throwing away the
     * reply the user was waiting for. A turn in flight is handed off here instead: pi finishes
     * it and writes the result to its own session file, and the plugin stops the process once
     * the turn ends. Keying by file means reopening that session adopts the running agent rather
     * than starting a second one on top of the same file.
     */
    private val detached = LinkedHashMap<String, PiRpcClient>()
    private var isRunning = false
    private var phase: AgentPhase = AgentPhase.Idle
    private var pendingUserText: String? = null
    private var suppressModelEvents = false
    private var disposed = false

    /** Coalesces streaming deltas so a fast model does not force a repaint per token. */
    private val repaintTimer = Timer(80) { flushStreaming() }.apply { isRepeats = false }

    /**
     * Collapses eager agent starts (session switches, tool-window shows) into one spawn.
     *
     * Every session switch stops the old agent and boots a fresh `pi --mode rpc` — a ~3s node
     * startup. Rapidly clicking through the list used to kill each booting process and start
     * another, serially wasting several spawns; a short one-shot delay turns a click-storm
     * into exactly one. User-initiated starts (sending a prompt) bypass this and start now.
     */
    private val eagerStartTimer = Timer(EAGER_START_DEBOUNCE_MS) {
        if (!disposed) ensureAgentRunning()
    }.apply { isRepeats = false }

    /** Drives the live elapsed counter and picks up branch switches while the panel is open. */
    private val tickTimer = Timer(500) {
        if (isRunning) setStatus(statusSummary())
        refreshBranch()
    }.apply { isRepeats = true }

    var onSessionChanged: (() -> Unit)? = null

    /** Set by the tool window so `/new` and `/resume` can drive the sidebar too. */
    var onNewSessionRequested: (() -> Unit)? = null
    var onShowSessionsRequested: (() -> Unit)? = null
    var onShowSettingsRequested: (() -> Unit)? = null

    init {
        buildUi()
        showEmptyState()
        tickTimer.start()
        // Opening the tool window should greet the user with the remembered provider and its
        // models, not empty combos; boot the agent quietly in the background.
        ensureAgentRunning()
    }

    // ---------------------------------------------------------------- UI setup

    private fun buildUi() {
        background = PiTheme.chatBg
        isOpaque = true

        wireSurface()
        center.isOpaque = true
        center.background = PiTheme.chatBg
        center.add(surface.component, java.awt.BorderLayout.CENTER)

        add(buildStatusStrip(), BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        // In web mode the composer and edits strip are inside the browser, below the transcript.
        if (!webMode) {
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(editsPanel, BorderLayout.NORTH)
                    add(buildComposer(), BorderLayout.CENTER)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    /**
     * Point the surface's callbacks at the handlers that already exist.
     *
     * The Swing composer calls these directly, so in that mode every callback but
     * `onLoadEarlier` stays null and nothing is duplicated.
     */
    private fun wireSurface() {
        surface.onLoadEarlier = { loadEarlierMessages() }
        if (!webMode) return

        // The Swing strip is kept off-screen in web mode: it still collects the files and runs
        // the git stats, and it is what `openDiffFor` resolves against.
        editsPanel.onFilesChanged = { files, stats ->
            surface.setEdits(
                files.map {
                    val stat = stats[it.absolutePath]
                    ChatSurface.Edit(it.displayPath, stat?.added ?: 0, stat?.removed ?: 0)
                }
            )
        }

        surface.onSend = { text -> sendComposed(text) }
        surface.onAbort = { rpc?.abort() }
        surface.onAttach = { chooseAttachments() }
        surface.onRemoveAttachment = { id ->
            attachments.firstOrNull { attachmentId(it) == id }?.let { removeAttachment(it) }
        }
        surface.onDropFiles = { paths -> addAttachments(paths.map(::File).filter { it.exists() }) }
        surface.onPasteClipboard = { pasteAttachmentsFromClipboard() }
        surface.onCompact = { compactNow() }
        surface.onSelectProvider = { id ->
            if (!suppressModelEvents) {
                selectProvider(id, PiSettings.getInstance().activeModel.takeIf { it.isNotBlank() })
                    ?.let { applySelectionToAgent(it) }
            }
        }
        // The combos stay the source of truth, so a pick has to land in them before it goes to
        // the agent — otherwise the pill keeps showing the old value until some later refresh
        // happens to correct it, and a switch that worked looks like one that did nothing.
        surface.onSelectModel = { id ->
            if (!suppressModelEvents) {
                val showing = (providerCombo.selectedItem as? ProviderOption)?.id
                val option = allModels.firstOrNull { it.id == id && it.provider == showing }
                    ?: allModels.firstOrNull { it.id == id }
                option?.let {
                    suppressModelEvents = true
                    modelCombo.selectedItem = it
                    suppressModelEvents = false
                    pushModels()
                    applySelectionToAgent(it)
                }
            }
        }
        surface.onSelectThinking = { level ->
            if (!suppressModelEvents) {
                suppressModelEvents = true
                thinkingCombo.selectedItem = level
                suppressModelEvents = false
                pushModels()
                PiSettings.getInstance().activeThinking = level
                rpc?.setThinkingLevel(level)
            }
        }
        surface.onOpenDiff = { path -> editsPanel.openDiffFor(path) }
        surface.onRequestCommands = {
            commandRegistry.ensureLoaded(rpc) { pushCommands() }
            pushCommands()
        }
        surface.onNewSession = { onNewSessionRequested?.invoke() ?: startNewSession() }
        surface.onRefreshSessions = { onRefreshSessionsRequested?.invoke() }
        surface.onToggleSidebar = { onToggleSidebarRequested?.invoke() }
        surface.onOpenSettings = { onShowSettingsRequested?.invoke() }
        surface.onSelectSession = { path -> onSelectSessionRequested?.invoke(path) }
        surface.onRenameSession = { path -> onRenameSessionRequested?.invoke(path) }
        surface.onDeleteSession = { path -> onDeleteSessionRequested?.invoke(path) }

        surface.setSendOnEnter(PiSettings.getInstance().sendOnEnter)
    }

    /**
     * The sessions the sidebar shows, when the sidebar is part of the page.
     *
     * [PiMainPanel] owns the session list either way; in web mode it hands the rows over rather
     * than drawing them, so the sidebar and the conversation share one stylesheet.
     */
    fun setSessions(items: List<ChatSurface.Session>, selectedPath: String?) {
        if (!webMode) return
        surface.setSessions(items, selectedPath)
    }

    fun setSidebarVisible(visible: Boolean) {
        if (!webMode) return
        surface.setSidebarVisible(visible)
    }

    /** True when the shell is drawn by the page, so the native toolbar and sidebar stand down. */
    fun hostsShell(): Boolean = webMode

    var onRefreshSessionsRequested: (() -> Unit)? = null
    var onToggleSidebarRequested: (() -> Unit)? = null
    var onSelectSessionRequested: ((String) -> Unit)? = null
    var onRenameSessionRequested: ((String) -> Unit)? = null
    var onDeleteSessionRequested: ((String) -> Unit)? = null

    /** The completion list the browser's `/` popup filters. */
    private fun pushCommands() {
        if (disposed || !webMode) return
        surface.setCommands(
            commandRegistry.snapshot().map {
                ChatSurface.Command(it.name, it.description.orEmpty())
            }
        )
    }

    /**
     * Mirror the footer combos into the browser.
     *
     * The combos stay the source of truth even in web mode: they hold the real [ModelOption]
     * objects, and every path that changes a selection — restoring a saved one, the agent
     * reporting what it actually runs, a `thinking_level_changed` event — already goes through
     * them. The page gets plain ids and labels.
     */
    private fun pushModels() {
        if (disposed || !webMode) return
        surface.setModels(
            ChatSurface.ModelChoices(
                providers = comboItems(providerCombo).map { ChatSurface.Choice(it.id, it.label) },
                provider = (providerCombo.selectedItem as? ProviderOption)?.id,
                models = comboItems(modelCombo).map { ChatSurface.Choice(it.id, it.id) },
                model = (modelCombo.selectedItem as? ModelOption)?.id,
                thinking = comboItems(thinkingCombo).map { ChatSurface.Choice(it, it) },
                thinkingLevel = thinkingCombo.selectedItem as? String,
            )
        )
    }

    private fun <T> comboItems(combo: JComboBox<T>): List<T> =
        combo.model.let { model -> (0 until model.size).map { model.getElementAt(it) } }

    /** Stable identity for an attachment chip, so the view can ask for one to be removed. */
    private fun attachmentId(attachment: Attachment): String =
        System.identityHashCode(attachment).toString()

    private fun buildStatusStrip(): JComponent {
        val strip = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PiTheme.chatBg
            border = JBUI.Borders.empty(6, 12, 5, 12)
        }
        statusLabel.foreground = PiTheme.mutedFg()
        statusLabel.font = statusLabel.font.deriveFont(statusLabel.font.size2D - 1f)

        branchLabel.foreground = PiTheme.mutedFg()
        branchLabel.font = branchLabel.font.deriveFont(branchLabel.font.size2D - 1f)
        branchLabel.icon = AllIcons.Vcs.Branch
        branchLabel.iconTextGap = JBUI.scale(3)
        branchLabel.toolTipText = PiBundle.message("chat.branch.tooltip")

        strip.add(statusLabel, BorderLayout.WEST)
        strip.add(branchLabel, BorderLayout.EAST)
        refreshBranch()
        return strip
    }

    /** Re-read `.git/HEAD`; cheap enough to call on a timer since it is mtime-cached. */
    private fun refreshBranch() {
        val branch = try {
            GitInfo.currentBranch(project.basePath)
        } catch (e: Exception) {
            null
        }
        branchLabel.text = branch ?: ""
        branchLabel.isVisible = branch != null
    }

    /** Centered watermark shown until the conversation has content. */
    private fun buildEmptyState(): JComponent {
        val wrapper = JPanel(GridBagLayout()).apply {
            isOpaque = true
            background = PiTheme.chatBg
        }
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val glyph = JBLabel("π").apply {
            font = PiTheme.uiFont().deriveFont(Font.BOLD, JBUI.scale(56).toFloat())
            foreground = PiTheme.logoFg()
            alignmentX = CENTER_ALIGNMENT
        }
        val title = JBLabel(PiBundle.message("chat.empty.title")).apply {
            font = PiTheme.uiFont().deriveFont(Font.PLAIN, JBUI.scale(15).toFloat())
            foreground = PiTheme.mutedFg()
            alignmentX = CENTER_ALIGNMENT
        }
        val hint = JBLabel(project.basePath ?: "").apply {
            font = PiTheme.uiFont().deriveFont(PiTheme.uiFont().size2D - 1f)
            foreground = PiTheme.noticeFg
            alignmentX = CENTER_ALIGNMENT
        }

        column.add(glyph)
        column.add(Box.createVerticalStrut(JBUI.scale(10)))
        column.add(title)
        column.add(Box.createVerticalStrut(JBUI.scale(4)))
        column.add(hint)

        wrapper.add(column, GridBagConstraints())
        return wrapper
    }

    private fun buildComposer(): JComponent {
        val composer = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PiTheme.chatBg
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(PiTheme.toolBorder),
                JBUI.Borders.empty(9, 10, 8, 10),
            )
        }

        input.lineWrap = true
        input.wrapStyleWord = true
        input.rows = 3
        input.font = PiTheme.uiFont()
        input.border = JBUI.Borders.empty()
        input.isOpaque = false
        input.background = PiTheme.inputBg
        input.caretColor = PiTheme.textFg()
        input.emptyText.text = PiBundle.message("chat.input.placeholder")
        installInputKeys()

        inputScroll = JBScrollPane(
            null,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        // The composer starts without a scroll pane and grows with what is typed. A viewport
        // around a wrapping text area re-wraps the whole document on every keystroke to answer
        // the preferred-size query, which allocated ~121 KB per character against ~25 KB for the
        // bare area — garbage that turns typing into a stutter once the heap is under pressure.
        // The scroll pane is therefore only installed once the message outgrows the composer.
        inputHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(input, BorderLayout.CENTER)
        }

        // Rounded shell around the text area; the outline brightens while the field has focus.
        val shell = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = PiTheme.inputBg
                    val a = JBUI.scale(8)
                    g2.fillRoundRect(0, 0, width, height, a, a)
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = false
            border = RoundedBorder(
                colorProvider = { if (input.hasFocus()) PiTheme.accent else PiTheme.toolBorder },
                arc = 8,
                padding = JBUI.insets(8, 10),
            )
            preferredSize = Dimension(100, JBUI.scale(84))
            add(inputHost, BorderLayout.CENTER)
        }
        composerShell = shell
        input.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = shell.repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) {
                // The popup is not focusable, so losing the composer means leaving the composer.
                commandPopup.hide()
                shell.repaint()
            }
        })
        commandAnchor = shell

        installAttachmentDropTarget(shell)
        installPasteShortcut()

        composer.add(attachmentStrip, BorderLayout.NORTH)
        composer.add(shell, BorderLayout.CENTER)
        composer.add(buildControlRow(), BorderLayout.SOUTH)
        return composer
    }

    // ------------------------------------------------------------ attachments

    /**
     * Claims Cmd/Ctrl+V for the composer as a *local* shortcut.
     *
     * `IdeKeyEventDispatcher` lets plain letters through to a focused text component but hands
     * modified keystrokes to the keymap first, so the IDE's global `$Paste` action could consume
     * the key and paste into the code editor behind the tool window — silently dropping an image
     * clipboard. Local shortcuts registered on the component are consulted before the keymap, so
     * this action wins, and text paste still behaves exactly as before via [input].paste().
     */
    private fun installPasteShortcut() {
        object : AnAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = input.isEditable && input.isEnabled
            }

            override fun actionPerformed(e: AnActionEvent) {
                input.paste()
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                com.intellij.openapi.actionSystem.KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), null),
                com.intellij.openapi.actionSystem.KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK), null),
                com.intellij.openapi.actionSystem.KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), null),
            ),
            input,
            this,
        )
    }

    /**
     * Turns a clipboard carrying files or an image into attachments. Mirrors the flavor priority
     * of [ComposerTransferHandler.importData]: files first, then raw images, so every entry point
     * behaves identically. Returns true when the clipboard was consumed as attachments.
     */
    private fun pasteAttachmentsFromClipboard(): Boolean {
        val contents = try {
            systemClipboard()?.getContents(this)
        } catch (e: IllegalStateException) {
            null // Clipboard is held by another process; fall back to the default text paste.
        } ?: return false

        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            try {
                @Suppress("UNCHECKED_CAST")
                val files =
                    contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                if (!files.isNullOrEmpty()) {
                    addAttachments(files)
                    return true
                }
            } catch (e: Exception) {
                log.debug("Clipboard file list unreadable", e)
            }
        }

        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            try {
                val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
                if (image != null) {
                    addClipboardImage(image)
                    return true
                }
            } catch (e: Exception) {
                log.debug("Clipboard image unreadable", e)
            }
        }
        return false
    }

    private fun systemClipboard(): Clipboard? = try {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
    } catch (e: Exception) {
        null
    }

    /** Routes pasted/dropped files and images into attachments, on the composer and the input. */
    private fun installAttachmentDropTarget(target: JComponent) {
        target.transferHandler =
            ComposerTransferHandler(target.transferHandler, ::addAttachments, ::addClipboardImage)
        // The text area's own handler must be kept and delegated to: copy, cut and paste all go
        // through TransferHandler, so replacing it outright silently breaks them.
        input.transferHandler =
            ComposerTransferHandler(input.transferHandler, ::addAttachments, ::addClipboardImage)
    }

    /**
     * Intercepts files and images on drag-and-drop and on the Swing paste route, forwarding
     * everything else — including all clipboard text traffic — to the component's original
     * handler.
     *
     * Swing's own `ActionMap` maps "paste" to `TransferHandler.getPasteAction()`, which calls
     * `importData` on this handler, so keyboard paste that reaches Swing lands here. The IDE
     * keymap route — which can consume Cmd/Ctrl+V before Swing ever sees the event — is claimed
     * by [installPasteShortcut] instead.
     */
    private class ComposerTransferHandler(
        private val delegate: javax.swing.TransferHandler?,
        private val onFiles: (List<File>) -> Unit,
        private val onImage: (java.awt.Image) -> Unit,
    ) : javax.swing.TransferHandler() {

        private val fileFlavor = java.awt.datatransfer.DataFlavor.javaFileListFlavor
        private val imageFlavor = java.awt.datatransfer.DataFlavor.imageFlavor

        override fun canImport(support: TransferSupport): Boolean =
            support.isDataFlavorSupported(fileFlavor) ||
                support.isDataFlavorSupported(imageFlavor) ||
                delegate?.canImport(support) == true

        override fun importData(support: TransferSupport): Boolean {
            // Files first: an image copied in Finder arrives as a file list, and keeping the real
            // file gives us its name and avoids a needless re-encode.
            if (support.isDataFlavorSupported(fileFlavor)) {
                return try {
                    @Suppress("UNCHECKED_CAST")
                    onFiles(support.transferable.getTransferData(fileFlavor) as List<File>)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (support.isDataFlavorSupported(imageFlavor)) {
                return try {
                    val image = support.transferable.getTransferData(imageFlavor) as? java.awt.Image
                    if (image == null) false else { onImage(image); true }
                } catch (e: Exception) {
                    false
                }
            }
            return delegate?.importData(support) ?: false
        }

        override fun getSourceActions(c: JComponent): Int = delegate?.getSourceActions(c) ?: NONE

        override fun exportToClipboard(c: JComponent, clip: java.awt.datatransfer.Clipboard, action: Int) {
            if (delegate != null) delegate.exportToClipboard(c, clip, action)
            else super.exportToClipboard(c, clip, action)
        }

        override fun exportAsDrag(c: JComponent, e: java.awt.event.InputEvent, action: Int) {
            if (delegate != null) delegate.exportAsDrag(c, e, action)
            else super.exportAsDrag(c, e, action)
        }
    }

    /**
     * Encodes a clipboard image into an attachment. Shared by paste and drag-and-drop.
     * Encoding happens off the EDT because a full-screen screenshot is several megabytes.
     */
    private fun addClipboardImage(image: java.awt.Image) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val attachment = try {
                val width = image.getWidth(null).coerceAtLeast(1)
                val height = image.getHeight(null).coerceAtLeast(1)
                val buffered = java.awt.image.BufferedImage(
                    width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB,
                )
                buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                val bytes = java.io.ByteArrayOutputStream()
                    .also { javax.imageio.ImageIO.write(buffered, "png", it) }
                    .toByteArray()

                if (bytes.size > Attachment.MAX_IMAGE_BYTES) null
                else Attachment.Image(
                    displayName = "pasted-image.png",
                    mimeType = "image/png",
                    base64 = java.util.Base64.getEncoder().encodeToString(bytes),
                    byteSize = bytes.size.toLong(),
                    sourcePath = null,
                )
            } catch (e: Exception) {
                log.warn("Could not read the pasted image", e)
                null
            }
            onEdt {
                if (attachment == null) notifyAttachmentProblem(PiBundle.message("error.pastedTooLarge"))
                else addAttachment(attachment)
            }
        }
    }

    private fun chooseAttachments() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().also {
            it.title = "Attach Files"
            it.description = "Images are sent to the model; other files are referenced by path"
        }
        FileChooser.chooseFiles(descriptor, project, null) { chosen ->
            addAttachments(chosen.mapNotNull { vf -> vf.path.let(::File).takeIf { f -> f.exists() } })
        }
    }

    private fun addAttachments(files: List<File>) {
        if (files.isEmpty()) return
        val problems = mutableListOf<String>()

        // Reading and base64-encoding images can be slow for large files.
        ApplicationManager.getApplication().executeOnPooledThread {
            val built = mutableListOf<Attachment>()
            files.forEach { file ->
                if (Attachment.isImage(file)) {
                    val image = Attachment.imageFrom(file)
                    if (image == null) {
                        problems.add(PiBundle.message("error.attachUnreadable", file.name))
                    } else {
                        built.add(image)
                    }
                } else {
                    built.add(Attachment.fileRefFrom(file, project.basePath))
                }
            }
            onEdt {
                built.forEach { addAttachment(it) }
                if (problems.isNotEmpty()) notifyAttachmentProblem(problems.joinToString("\n"))
            }
        }
    }

    private fun addAttachment(attachment: Attachment) {
        if (attachment is Attachment.Image) {
            val imageCount = attachments.count { it is Attachment.Image }
            if (imageCount >= Attachment.MAX_IMAGES) {
                notifyAttachmentProblem(PiBundle.message("error.attachTooLarge", Attachment.MAX_IMAGES))
                return
            }
        }
        attachments.add(attachment)
        pushAttachments()
    }

    private fun removeAttachment(attachment: Attachment) {
        attachments.remove(attachment)
        pushAttachments()
    }

    private fun clearAttachments() {
        attachments.clear()
        pushAttachments()
    }

    private fun pushAttachments() {
        surface.setAttachments(
            attachments.map { ChatSurface.Attachment(attachmentId(it), it.displayName) }
        )
        revalidate()
        repaint()
    }

    private fun notifyAttachmentProblem(message: String) {
        setStatus(message.lineSequence().first())
        log.info("Attachment rejected: $message")
    }

    private fun buildControlRow(): JComponent {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyTop(8)
            isOpaque = false
        }

        modelCombo.toolTipText = PiBundle.message("chat.model")
        modelCombo.isEnabled = false
        // Without an explicit minimum an empty combo collapses to a bare arrow stub.
        modelCombo.preferredSize = Dimension(JBUI.scale(190), JBUI.scale(28))
        modelCombo.minimumSize = Dimension(JBUI.scale(140), JBUI.scale(28))
        modelCombo.maximumSize = Dimension(JBUI.scale(230), JBUI.scale(28))
        modelCombo.renderer = placeholderRenderer(PiBundle.message("chat.model.none"))
        modelCombo.addActionListener {
            if (suppressModelEvents) return@addActionListener
            val option = modelCombo.selectedItem as? ModelOption ?: return@addActionListener
            applySelectionToAgent(option)
        }

        providerCombo.toolTipText = PiBundle.message("chat.provider")
        providerCombo.isEnabled = false
        providerCombo.preferredSize = Dimension(JBUI.scale(150), JBUI.scale(28))
        providerCombo.minimumSize = Dimension(JBUI.scale(110), JBUI.scale(28))
        providerCombo.maximumSize = Dimension(JBUI.scale(190), JBUI.scale(28))
        providerCombo.renderer = placeholderRenderer(PiBundle.message("chat.provider"))
        providerCombo.addActionListener {
            if (suppressModelEvents) return@addActionListener
            val option = providerCombo.selectedItem as? ProviderOption ?: return@addActionListener
            val pick = selectProvider(option.id, PiSettings.getInstance().activeModel.takeIf { it.isNotBlank() })
                ?: return@addActionListener
            applySelectionToAgent(pick)
        }

        thinkingCombo.toolTipText = PiBundle.message("chat.thinking")
        thinkingCombo.isEnabled = false
        thinkingCombo.preferredSize = Dimension(JBUI.scale(110), JBUI.scale(28))
        thinkingCombo.minimumSize = Dimension(JBUI.scale(90), JBUI.scale(28))
        thinkingCombo.maximumSize = Dimension(JBUI.scale(130), JBUI.scale(28))
        thinkingCombo.renderer = placeholderRenderer(PiBundle.message("chat.thinking"))
        thinkingCombo.addActionListener {
            if (suppressModelEvents) return@addActionListener
            val level = thinkingCombo.selectedItem as? String ?: return@addActionListener
            PiSettings.getInstance().activeThinking = level
            rpc?.setThinkingLevel(level)
        }

        stopButton.isVisible = false
        stopButton.addActionListener { rpc?.abort() }
        sendButton.addActionListener { send() }

        val attachButton = PiButton(null, AllIcons.General.Add, PiButton.Style.GHOST).apply {
            toolTipText = PiBundle.message("chat.attach")
            addActionListener { chooseAttachments() }
        }

        row.add(attachButton)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(providerCombo)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(modelCombo)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        contextLabel.foreground = PiTheme.mutedFg()
        contextLabel.font = contextLabel.font.deriveFont(contextLabel.font.size2D - 1f)
        compactButton.addActionListener { compactNow() }
        updateContextLabel()

        row.add(thinkingCombo)
        row.add(Box.createHorizontalGlue())
        row.add(contextLabel)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(compactButton)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(stopButton)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(sendButton)
        return row
    }

    // ------------------------------------------------------- context / compaction

    /**
     * Compact on demand, but refuse when there is barely anything to compact.
     *
     * The percentage is re-read instead of trusting the label: compaction costs a model call and
     * discards detail, so the decision is made on what the agent reports right now rather than on
     * whatever the footer happened to be showing.
     */
    private fun compactNow() {
        withAgent("compact") { client ->
            client.getSessionStats { response ->
                val data = response.getAsJsonObjectOrNull("data")
                onEdt {
                    applyContextUsage(data)
                    val percent = contextPercent
                    if (isTooSmallToCompact(percent)) {
                        appendMessage(
                            PiMessage.Notice(
                                PiBundle.message(
                                    "compact.tooSmall",
                                    formatContextPercent(percent!!),
                                    COMPACT_MIN_PERCENT.toInt(),
                                )
                            )
                        )
                        return@onEdt
                    }
                    phase = AgentPhase.Compacting("manual")
                    setStatus(statusSummary())
                    client.compact { r -> reportIfFailed("compact", r) }
                }
            }
        }
    }

    /** Ask the running agent where the context stands; a no-op when nothing is running. */
    private fun refreshContextUsage() {
        val client = rpc?.takeIf { it.isRunning } ?: return
        client.getSessionStats { response ->
            val data = response.getAsJsonObjectOrNull("data")
            onEdt { applyContextUsage(data) }
        }
    }

    private fun applyContextUsage(data: JsonObject?) {
        val usage = data?.getAsJsonObjectOrNull("contextUsage")
        contextPercent = usage?.get("percent")?.asDoubleOrNull()
        val tokens = usage?.get("tokens")?.asLongOrNull()
        val window = usage?.get("contextWindow")?.asLongOrNull()
        contextTooltip = if (tokens != null && window != null) {
            PiBundle.message(
                "chat.context.tooltip",
                SessionStore.formatTokens(tokens),
                SessionStore.formatTokens(window),
            )
        } else {
            PiBundle.message("chat.compact")
        }
        updateContextLabel()
    }

    private fun updateContextLabel() {
        val percent = contextPercent
        surface.setContext(
            percent?.let { PiBundle.message("chat.context", formatContextPercent(it)) },
            contextTooltip,
        )
    }


    /** Shows dimmed placeholder text while a combo has no items yet. */
    private fun placeholderRenderer(placeholder: String): javax.swing.DefaultListCellRenderer =
        object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                selected: Boolean,
                focused: Boolean,
            ): java.awt.Component {
                val label = super.getListCellRendererComponent(list, value, index, selected, focused)
                    as javax.swing.JLabel
                if (value == null) {
                    label.text = placeholder
                    label.foreground = PiTheme.mutedFg()
                } else {
                    label.text = value.toString()
                }
                label.border = JBUI.Borders.empty(0, 6)
                return label
            }
        }

    private fun installInputKeys() {
        val im = input.inputMap
        val am = input.actionMap
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pi.send")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "pi.newline")
        am.put("pi.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (acceptCommandCompletion()) return
                if (PiSettings.getInstance().sendOnEnter) send() else input.insert("\n", input.caretPosition)
            }
        })
        am.put("pi.newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (acceptCommandCompletion()) return
                if (PiSettings.getInstance().sendOnEnter) input.insert("\n", input.caretPosition) else send()
            }
        })

        installCommandKeys()
    }

    // ------------------------------------------------------------ slash commands

    /**
     * Arrow keys, Tab and Escape drive the completion list while it is open and behave normally
     * otherwise, so each binding falls back to whatever the text area already had.
     */
    private fun installCommandKeys() {
        val popupOpen = { commandPopup.isShowing }
        overrideKey(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "pi.command.next", popupOpen) {
            commandPopup.moveSelection(1)
        }
        overrideKey(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "pi.command.previous", popupOpen) {
            commandPopup.moveSelection(-1)
        }
        overrideKey(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "pi.command.complete", popupOpen) {
            acceptCommandCompletion()
        }
        overrideKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "pi.command.cancel", popupOpen) {
            commandPopup.hide()
        }

        // Both the text and the caret decide what is being completed, and their update order
        // relative to this listener is not guaranteed — re-check once the event has settled.
        input.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onComposerChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onComposerChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })
        input.addCaretListener { onComposerChanged() }
    }

    /**
     * One coalesced follow-up per keystroke.
     *
     * A character fires both listeners above, and the text area's view has not caught up with the
     * document by the time they run — reading the composer's height there yields the previous
     * layout. Deferring once settles both problems: the view is current, and the two listeners
     * collapse into a single runnable instead of two.
     */
    private fun onComposerChanged() {
        if (composerUpdateQueued) return
        composerUpdateQueued = true
        SwingUtilities.invokeLater {
            composerUpdateQueued = false
            if (disposed) return@invokeLater
            updateComposerHeight()
            // Ordinary prose needs no completion work at all.
            if (commandPopup.isShowing || composerPrefix(1) == "/") updateCommandPopup()
        }
    }

    private var composerUpdateQueued = false

    /** Set while a popup refresh is already queued, so one keystroke posts at most one. */
    private var commandPopupUpdateQueued = false

    private lateinit var inputScroll: JBScrollPane
    private lateinit var inputHost: JPanel
    private var composerShell: JComponent? = null
    /** True while the message is long enough to need the scroll pane. */
    private var composerScrolling = false

    /**
     * A keystroke fires both listeners above, so an un-coalesced refresh posted two runnables per
     * character. Ordinary prose does not need either: unless the composer holds a command, or a
     * popup is open that may have to close, there is nothing for the update to do — so the common
     * case never reaches the event queue at all.
     */
    private fun scheduleCommandPopupUpdate() {
        if (!commandPopup.isShowing && composerPrefix(1) != "/") return
        if (commandPopupUpdateQueued) return
        commandPopupUpdateQueued = true
        SwingUtilities.invokeLater {
            commandPopupUpdateQueued = false
            updateCommandPopup()
        }
    }

    /**
     * The first [max] characters of the composer.
     *
     * `JTextComponent.getText()` copies the entire document, so reading it on every keystroke
     * allocated in proportion to what the user had already typed — 88 KB per character into a
     * 1000-character message. Nothing here needs more than the leading command token, and under
     * memory pressure that garbage is what turns typing into a stutter.
     */
    /**
     * Resize the composer to fit what has been typed, switching to a scroll pane only once the
     * message outgrows [COMPOSER_MAX_HEIGHT].
     *
     * The switch has hysteresis: dropping back to the bare area needs the content to fall a whole
     * row below the threshold, so a message hovering on the boundary cannot re-parent the focused
     * text area on every keystroke.
     */
    private fun updateComposerHeight() {
        val shell = composerShell ?: return
        val padding = shell.insets.let { it.top + it.bottom }
        val minHeight = JBUI.scale(COMPOSER_MIN_HEIGHT)
        val maxHeight = JBUI.scale(COMPOSER_MAX_HEIGHT)
        // Cheap on a bare text area; it is the viewport around one that makes this expensive.
        val content = input.preferredSize.height

        val rowHeight = input.getFontMetrics(input.font).height.coerceAtLeast(1)
        val wantScroll = when {
            content > maxHeight -> true
            composerScrolling -> content > maxHeight - rowHeight
            else -> false
        }
        if (wantScroll != composerScrolling) setComposerScrolling(wantScroll)

        val target = content.coerceIn(minHeight, maxHeight) + padding
        if (shell.preferredSize.height != target) {
            shell.preferredSize = Dimension(100, target)
            shell.revalidate()
        }
    }

    /**
     * Move [input] between the bare host and the scroll pane, keeping focus and caret intact —
     * re-parenting a focused component otherwise drops the keystroke that triggered it.
     */
    private fun setComposerScrolling(scrolling: Boolean) {
        composerScrolling = scrolling
        val hadFocus = input.isFocusOwner
        val caret = input.caretPosition
        val shell = composerShell ?: return

        if (scrolling) {
            inputHost.remove(input)
            inputScroll.setViewportView(input)
            shell.remove(inputHost)
            shell.add(inputScroll, BorderLayout.CENTER)
        } else {
            inputScroll.viewport.view = null
            shell.remove(inputScroll)
            inputHost.add(input, BorderLayout.CENTER)
            shell.add(inputHost, BorderLayout.CENTER)
        }
        shell.revalidate()
        shell.repaint()
        runCatching { input.caretPosition = caret }
        if (hadFocus) input.requestFocusInWindow()
    }

    private fun composerPrefix(max: Int): String {
        val document = input.document
        val length = minOf(max, document.length)
        if (length <= 0) return ""
        return try {
            document.getText(0, length)
        } catch (e: javax.swing.text.BadLocationException) {
            ""
        }
    }

    /**
     * Shadow an existing key binding: while [active] the key runs [handler], otherwise the text
     * area's original action does.
     *
     * When there is no original action the binding reports itself disabled, which makes Swing
     * leave the event unconsumed — without that, binding Escape here would permanently swallow the
     * IDE's own Escape (return focus to the editor) even with the popup closed.
     */
    private fun overrideKey(
        stroke: KeyStroke,
        name: String,
        active: () -> Boolean,
        handler: () -> Unit,
    ) {
        val previous = input.inputMap.get(stroke)?.let { input.actionMap.get(it) }
        input.inputMap.put(stroke, name)
        input.actionMap.put(name, object : AbstractAction() {
            override fun isEnabled(): Boolean = active() || previous != null

            override fun actionPerformed(e: ActionEvent) {
                if (active()) handler() else previous?.actionPerformed(e)
            }
        })
    }

    private fun acceptCommandCompletion(): Boolean =
        commandPopup.isShowing && commandPopup.chooseSelected()

    /** What the composer's current contents should put in front of the user. */
    sealed class CommandCompletion {
        object Hidden : CommandCompletion()

        /** [loading] means the extension/prompt/skill list is still on its way in. */
        data class Matches(
            val commands: List<PiCommand>,
            val loading: Boolean = false,
        ) : CommandCompletion()
    }

    private var lastCompletion: CommandCompletion = CommandCompletion.Hidden

    private fun computeCompletion(): CommandCompletion {
        // Only the leading token can be a command, so the rest of the message is never read.
        val query = PiCommand.queryAt(composerPrefix(COMMAND_PREFIX_CHARS), input.caretPosition)
            ?: return CommandCompletion.Hidden
        // The built-ins are static, so there is always something to show immediately.
        return CommandCompletion.Matches(
            commands = PiCommand.filter(commandRegistry.snapshot(), query),
            loading = !commandRegistry.hasLoaded(),
        )
    }

    /** Open, refilter or close the popup for whatever the caret is sitting in. */
    private fun updateCommandPopup() {
        if (disposed) return
        val state = computeCompletion()
        lastCompletion = state
        when (state) {
            is CommandCompletion.Hidden -> commandPopup.hide()
            is CommandCompletion.Matches -> {
                if (state.loading) {
                    // Reuses the running agent when there is one, else probes a throwaway process.
                    commandRegistry.ensureLoaded(rpc) { updateCommandPopup() }
                }
                val empty = PiBundle.message(
                    if (state.loading) "chat.commands.loading" else "chat.commands.none"
                )
                showCommandPopup(state.commands, empty)
            }
        }
    }

    private fun showCommandPopup(commands: List<PiCommand>, emptyMessage: String) {
        // `locationOnScreen` is only defined once the composer is on a real window.
        val anchor = commandAnchor?.takeIf { it.isShowing } ?: return
        commandPopup.show(anchor, commands, emptyMessage)
    }

    /**
     * Replace the typed `/prefix` with the chosen command, leaving the caret after it so the user
     * can type arguments. Only the command token is touched — anything already typed after it
     * survives.
     */
    // -------------------------------------------------------- built-in commands

    /**
     * Run one of pi's built-in commands, or report that it needs the CLI.
     *
     * pi handles these only in its terminal UI — sending `/new` as a prompt would reach the model
     * as the literal text "/new". So the plugin performs the equivalent itself, mostly through the
     * RPC commands that back the same behaviour in pi.
     *
     * @return true when the text was a built-in and has been dealt with.
     */
    private fun runBuiltinCommand(text: String): Boolean {
        val (name, args) = BuiltinCommands.parseInvocation(text) ?: return false
        val builtin = BuiltinCommands.find(name) ?: return false

        if (builtin.availability == BuiltinCommands.Availability.CLI_ONLY) {
            appendMessage(PiMessage.Notice(PiBundle.message("command.cliOnly", "/$name")))
            surface.setComposerText("")
            return true
        }

        // Everything below either needs no agent or brings one up on demand.
        when (name) {
            "new" -> onNewSessionRequested?.invoke() ?: startNewSession()
            "settings" -> onShowSettingsRequested?.invoke() ?: PiSettingsDialog(project).show()
            "resume" -> onShowSessionsRequested?.invoke()
            "hotkeys" -> showHotkeys()
            "quit" -> {
                stopAgent()
                setStatus(statusSummary())
            }
            "model" -> if (modelCombo.isEnabled) modelCombo.showPopup() else needsAgent(name)
            "thinking" -> if (thinkingCombo.isEnabled) thinkingCombo.showPopup() else needsAgent(name)
            "reload" -> reloadAgent()
            else -> withAgent(name) { client -> runAgentBuiltin(name, args, client) }
        }
        surface.setComposerText("")
        commandPopup.hide()
        return true
    }

    /** Built-ins that are really RPC calls; they need a live agent. */
    private fun runAgentBuiltin(name: String, args: String, client: PiRpcClient) {
        when (name) {
            "compact" -> {
                phase = AgentPhase.Compacting("manual")
                setStatus(statusSummary())
                client.compact { response -> reportIfFailed(name, response) }
            }

            "session" -> client.getSessionStats { response ->
                onEdt {
                    val data = response.getAsJsonObjectOrNull("data")
                    if (data == null) reportIfFailed(name, response)
                    else appendMessage(PiMessage.Notice(SessionStore.describeStats(data)))
                }
            }

            "name" -> {
                val chosen = args.ifBlank {
                    Messages.showInputDialog(
                        project, PiBundle.message("command.name.prompt"), "Pi GUI", null,
                        session?.displayTitle().orEmpty(), null,
                    ).orEmpty()
                }
                if (chosen.isBlank()) return
                client.setSessionName(chosen) { response ->
                    onEdt {
                        reportIfFailed(name, response)
                        onSessionChanged?.invoke()
                    }
                }
            }

            "copy" -> client.send("get_last_assistant_text", {}) { response ->
                val text = response.getAsJsonObjectOrNull("data")?.get("text")?.asStringOrNull()
                onEdt {
                    if (text.isNullOrBlank()) {
                        appendMessage(PiMessage.Notice(PiBundle.message("command.copy.empty")))
                    } else {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(text), null,
                        )
                        appendMessage(PiMessage.Notice(PiBundle.message("command.copy.done")))
                    }
                }
            }

            "export" -> client.send("export_html", {
                if (args.isNotBlank()) addProperty("outputPath", args)
            }) { response ->
                val path = response.getAsJsonObjectOrNull("data")?.get("path")?.asStringOrNull()
                onEdt {
                    if (path == null) reportIfFailed(name, response)
                    else appendMessage(PiMessage.Notice(PiBundle.message("command.export.done", path)))
                }
            }

            "clone" -> client.send("clone", {}) { response ->
                onEdt {
                    reportIfFailed(name, response)
                    onSessionChanged?.invoke()
                }
            }

            "fork" -> client.send("get_fork_messages", {}) { response ->
                val entries = response.getAsJsonObjectOrNull("data")
                    ?.let { PiJson.asArray(it.get("messages")) }
                    ?.mapNotNull { element ->
                        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                            ?: return@mapNotNull null
                        val id = obj.get("entryId")?.asStringOrNull() ?: return@mapNotNull null
                        id to (obj.get("text")?.asStringOrNull() ?: id)
                    }.orEmpty()
                onEdt { chooseForkPoint(entries, client) }
            }

            "tree" -> client.send("get_tree", {}) { response ->
                val data = response.getAsJsonObjectOrNull("data")
                onEdt {
                    if (data == null) reportIfFailed(name, response)
                    else appendMessage(PiMessage.Notice(SessionStore.describeTree(data)))
                }
            }
        }
    }

    private fun chooseForkPoint(entries: List<Pair<String, String>>, client: PiRpcClient) {
        if (entries.isEmpty()) {
            appendMessage(PiMessage.Notice(PiBundle.message("command.fork.empty")))
            return
        }
        val labels = entries.map { (_, text) ->
            text.replace(Regex("\\s+"), " ").trim().take(80)
        }
        val choice = Messages.showDialog(
            project, PiBundle.message("command.fork.prompt"), "Pi GUI",
            labels.toTypedArray(), labels.size - 1, null,
        )
        if (choice < 0) return
        client.send("fork", { addProperty("entryId", entries[choice].first) }) { response ->
            onEdt {
                reportIfFailed("fork", response)
                onSessionChanged?.invoke()
                // The transcript is now a different branch; re-read it from the session file.
                session?.let(::loadSession)
            }
        }
    }

    /** `/reload` re-reads extensions, skills and prompts — which is what a fresh process does. */
    private fun reloadAgent() {
        val hadAgent = rpc?.isRunning == true
        // A restart follows immediately; queued start results survive it.
        stopAgent(expectRestart = true)
        commandRegistry.invalidate()
        if (!hadAgent) {
            appendMessage(PiMessage.Notice(PiBundle.message("command.reload.done")))
            return
        }
        startAgent { client ->
            appendMessage(
                PiMessage.Notice(
                    PiBundle.message(
                        if (client == null) "command.reload.failed" else "command.reload.done"
                    )
                )
            )
        }
    }

    private fun showHotkeys() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "preferences.keymap")
    }

    /** Bring an agent up if needed, then hand it to [body]. */
    private fun withAgent(name: String, body: (PiRpcClient) -> Unit) {
        val client = rpc
        if (client != null && client.isRunning) {
            body(client)
            return
        }
        startAgent { started ->
            if (started == null) needsAgent(name) else body(started)
        }
    }

    private fun needsAgent(name: String) {
        appendMessage(PiMessage.Notice(PiBundle.message("command.needsAgent", "/$name")))
    }

    private fun reportIfFailed(name: String, response: JsonObject) {
        if (response.get("success")?.asBoolean == true) return
        val error = response.get("error")?.asStringOrNull() ?: "unknown error"
        onEdt { appendMessage(PiMessage.Notice("/$name failed: $error")) }
    }

    /** Extension commands are the only kind pi refuses to queue as steering. */
    private fun isExtensionCommand(text: String): Boolean {
        if (!text.startsWith("/")) return false
        val name = text.drop(1).takeWhile { !it.isWhitespace() }
        return commandRegistry.snapshot().any { it.name == name && it.source == "extension" }
    }

    private fun insertCommand(command: PiCommand) {
        val text = input.text
        if (!text.startsWith("/")) return
        val token = text.drop(1).takeWhile { !it.isWhitespace() }
        val rest = text.substring(1 + token.length)
        val replacement = command.insertText
        input.text = replacement + rest.trimStart()
        input.caretPosition = replacement.length
        input.requestFocusInWindow()
    }

    // ------------------------------------------------------------ session load

    /** Path of the session whose transcript is loaded (or currently loading). */
    private var loadedSessionPath: String? = null

    /** Show an existing session's transcript, read from disk. */
    fun loadSession(info: SessionInfo) {
        // Re-clicking the session that is already on screen must not churn: a reload kills and
        // re-spawns the agent (~3s) and re-renders the whole transcript, which made clicking
        // around the list feel laggy. Pick up disk changes via the refresh button instead.
        if (loadedSessionPath == info.filePath) return
        loadedSessionPath = info.filePath
        if (!detachRunningAgent()) {
            // A restart is coming right away for the new session; queued start results survive it.
            stopAgent(expectRestart = true)
        }
        session = info
        messages.clear()
        // Another session's percentage must not linger while the new one loads.
        applyContextUsage(null)
        resetTranscriptWindow()
        setStatus(PiBundle.message("status.loading"))
        val file = File(info.filePath)
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = SessionStore.readTranscript(file)
            onEdt {
                if (session?.filePath != info.filePath) return@onEdt
                messages.addAll(loaded)
                rebuildTranscript()
                scrollToBottom()
                setStatus(statusSummary())
            }
        }
        // This session may still have an agent finishing a turn in the background. Adopting it
        // shows the rest of that turn live, and keeps two processes off one session file.
        val resumed = detached.remove(info.filePath)
        if (resumed != null) {
            rpc = resumed
            liveSessionFile = info.filePath
            setRunning(true)
            phase = AgentPhase.WaitingModel
            setStatus(statusSummary())
            refreshModels(resumed)
            refreshContextUsage()
            return
        }
        // Resuming binds a fresh process to this session; having it ready repopulates the
        // footer combos before the first prompt.
        scheduleEagerAgentStart()
    }

    private fun scheduleEagerAgentStart() {
        // restart() (not a plain start) so a burst of switches keeps pushing the spawn back
        // until the clicking settles.
        eagerStartTimer.restart()
    }

    /** Discard the current conversation and begin a fresh one in this project. */
    fun startNewSession() {
        // A turn already running belongs to the session being left, not to this one: let it
        // finish in the background rather than throwing away the reply being waited for.
        if (!detachRunningAgent()) {
            // A restart is coming right away; queued start results survive it.
            stopAgent(expectRestart = true)
        }
        commandPopup.hide()
        // Skills and extensions may have been added since; re-ask rather than serve a stale list.
        commandRegistry.invalidate()
        applyContextUsage(null)
        session = null
        loadedSessionPath = null
        messages.clear()
        resetTranscriptWindow()
        rebuildTranscript()
        showEmptyState()
        setStatus(PiBundle.message("status.newSession"))
        input.requestFocusInWindow()
        // The fresh chat should immediately show (and run with) the remembered provider/model.
        ensureAgentRunning()
    }

    private fun showEmptyState() {
        surface.showEmptyState(project.basePath)
    }

    /** Swap the watermark out for the transcript the moment there is anything to show. */
    private fun showTranscript() {
        // The surface reveals itself as soon as it has content.
    }

    // ------------------------------------------------------------- transcript

    /**
     * Renders only the tail of the conversation.
     *
     * The window is bounded twice: by message count and by the volume of text. Every message
     * costs an HTML parse plus a full-document layout pass, and a heavy session (dozens of
     * pasted files) laid out 36,000+ lines at once — a multi-second EDT freeze on every
     * switch. Bounding by lines keeps that at one screenful no matter how big each message is.
     */
    private fun rebuildTranscript() {
        // Any fill still queued belongs to the transcript being replaced.
        fillGeneration++
        val hidden = hiddenMessageCount()
        surface.setMessages(messages.drop(hidden), hidden)

        if (messages.isEmpty()) showEmptyState() else showTranscript()
        eagerComponentCount = surface.renderedCount()
        editsPanel.update(messages)
        scheduleTranscriptFill(fillGeneration)
    }

    /**
     * Window state; reset whenever a different conversation is shown.
     *
     * Only a screenful is rendered up front. Each message costs a fixed ~2.5ms to build —
     * an HTML parse and document construction, near enough independent of its size — so
     * materialising the whole 60-message window synchronously put 150-270ms on the EDT for
     * every session switch, while the viewport showed about three messages. The rest of the
     * window is filled in afterwards by [scheduleTranscriptFill].
     */
    private fun resetTranscriptWindow() {
        visibleLimit = EAGER_PAGE_SIZE
        visibleLineBudget = EAGER_LINE_BUDGET
    }

    /**
     * Grow the rendered window back up to the full page, a chunk per event-queue turn.
     *
     * The work is the same; what changes is that no single turn blocks long enough to be seen.
     * Anything queued for a previous transcript is dropped by the generation check, so clicking
     * quickly through sessions never stacks up fills.
     */
    private fun scheduleTranscriptFill(generation: Int) {
        if (visibleLimit >= VISIBLE_PAGE_SIZE && visibleLineBudget >= INITIAL_LINE_BUDGET) return
        if (hiddenMessageCount() == 0) return
        SwingUtilities.invokeLater {
            if (disposed || generation != fillGeneration) return@invokeLater
            val before = hiddenMessageCount()
            visibleLimit = minOf(visibleLimit + FILL_CHUNK_MESSAGES, VISIBLE_PAGE_SIZE)
            visibleLineBudget = minOf(visibleLineBudget + FILL_CHUNK_LINES, INITIAL_LINE_BUDGET)
            val after = hiddenMessageCount()
            if (after < before) prependMessages(firstIndex = after, untilIndex = before)
            scheduleTranscriptFill(generation)
        }
    }

    /**
     * Insert `messages[firstIndex until untilIndex]` above what is already rendered.
     *
     * Growing the window by re-running [rebuildTranscript] would re-render everything already on
     * screen on every chunk, turning a linear fill into a quadratic one.
     */
    private fun prependMessages(firstIndex: Int, untilIndex: Int) {
        surface.prependMessages(messages.subList(firstIndex, untilIndex), firstIndex)
    }

    /**
     * How many leading messages stay unrendered. Walks from the newest message back until
     * either the message cap or the line budget is spent; the newest message always renders,
     * even alone, so there is always something to see.
     */
    private fun hiddenMessageCount(): Int {
        var lines = 0
        var shown = 0
        for (i in messages.indices.reversed()) {
            if (shown >= visibleLimit) break
            val cost = renderedLineCost(messages[i])
            if (shown > 0 && lines + cost > visibleLineBudget) break
            lines += cost
            shown++
        }
        return messages.size - shown
    }

    /**
     * Lines of HTML a message will actually lay out. Collapsed sections (thinking off, tool
     * calls/results) build nothing until opened, so they cost a token amount.
     */
    private fun renderedLineCost(message: PiMessage): Int {
        val settings = PiSettings.getInstance()
        return when (message) {
            is PiMessage.User -> message.text.count { it == '\n' } + 1
            is PiMessage.Assistant -> 1 + message.blocks.sumOf { block: ContentBlock ->
                when (block) {
                    is ContentBlock.Text -> block.text.count { it == '\n' } + 1
                    is ContentBlock.Thinking ->
                        if (settings.showThinking) block.thinking.count { it == '\n' } + 1 else 1
                    is ContentBlock.ToolCall ->
                        if (settings.autoExpandToolCalls) block.argumentsText().count { it == '\n' } + 1 else 2
                    is ContentBlock.Image -> 1
                }
            }
            is PiMessage.ToolResult -> 2
            is PiMessage.Notice -> 1
        }
    }

    /** "Load earlier messages" widens the window by another page. */
    private fun loadEarlierMessages() {
        visibleLimit += VISIBLE_PAGE_SIZE
        visibleLineBudget += PAGE_LINE_BUDGET
        rebuildTranscript()
    }

    private fun appendMessage(message: PiMessage) {
        messages.add(message)
        visibleLimit++
        visibleLineBudget += renderedLineCost(message).coerceAtLeast(1)
        editsPanel.update(messages)
        showTranscript()
        surface.appendMessage(message)
    }

    private fun flushStreaming() {
        surface.setStreaming(streaming.current)
    }

    private fun scheduleStreamingRepaint() {
        if (!repaintTimer.isRunning) repaintTimer.restart()
    }

    private fun isNearBottom(): Boolean = surface.isNearBottom()

    private fun maybeScrollToBottom() {
        if (isNearBottom()) scrollToBottom()
    }

    /**
     * Jump straight to the newest message.
     *
     * The viewport is moved directly rather than through `verticalScrollBar.value`, because the
     * platform's scrollbar is a `JBScrollBar` whose `setValue` runs the IDE's smooth-scrolling
     * interpolator. That turned every session switch into an animated glide down the whole
     * transcript — on a long session ~30 000px of travel, repainting the component tree on every
     * frame, purely to land somewhere it could have jumped to in one step. `JViewport` has no
     * such animation, so the destination is identical and the travel disappears.
     */
    private fun scrollToBottom() = surface.scrollToBottom()

    // ----------------------------------------------------------------- sending

    /** The Swing composer's send button and Enter binding. */
    private fun send() {
        commandPopup.hide()
        sendComposed(input.text.trim())
    }

    /**
     * Send what the composer holds. The text is passed in rather than read back, because in web
     * mode the view is the one that has it — the plugin does not mirror every keystroke.
     */
    private fun sendComposed(text: String) {
        if (text.isEmpty() && attachments.isEmpty()) return

        // pi's built-ins never reach the agent as prompts; the plugin performs them itself.
        if (runBuiltinCommand(text)) return

        // Non-image attachments become @mentions in the message; images ride along as base64.
        val mentions = attachments.filterIsInstance<Attachment.FileRef>()
            .joinToString(" ") { "@" + it.mentionPath }
        val outgoing = composeMessage(text, mentions)
        val images = attachments.filterIsInstance<Attachment.Image>()
            .map { PiRpcClient.ImagePayload(it.base64, it.mimeType) }

        val client = rpc
        if (client != null && client.isRunning) {
            dispatchPrompt(client, outgoing, images)
            return
        }
        startAgent { started ->
            if (started != null) dispatchPrompt(started, outgoing, images)
        }
    }

    private fun dispatchPrompt(
        client: PiRpcClient,
        text: String,
        images: List<PiRpcClient.ImagePayload> = emptyList(),
    ) {
        // Capture this before setRunning() flips the flag. Extension commands are excluded because
        // pi rejects them as steering ("use prompt instead") — they run immediately instead.
        val midRun = isRunning && streaming.isStreaming && !isExtensionCommand(text)

        surface.setComposerText("")
        appendMessage(PiMessage.User(text, imageCount = images.size))
        clearAttachments()
        pendingUserText = text
        setRunning(true)
        phase = AgentPhase.WaitingModel
        setStatus(statusSummary())

        if (midRun) {
            // Mid-run input is queued as steering rather than starting a second turn.
            client.steer(text, images) { response ->
                if (response.get("success")?.asBoolean != true) {
                    val error = response.get("error")?.asStringOrNull() ?: "unknown error"
                    onEdt { appendMessage(PiMessage.Notice("Steering failed: $error")) }
                }
            }
        } else {
            client.prompt(text, images) { response ->
                if (response.get("success")?.asBoolean != true) {
                    val error = response.get("error")?.asStringOrNull() ?: "unknown error"
                    onEdt {
                        setRunning(false)
                        appendMessage(PiMessage.Notice("Prompt failed: $error"))
                        setStatus(statusSummary())
                    }
                }
            }
        }
        scrollToBottom()
    }

    // ------------------------------------------------------------ agent process

    /**
     * Bring the footer to life without waiting for the first prompt. Starts the agent in the
     * background when it is not running, which repopulates the provider/model combos and
     * restores the remembered selection. Called when the tool window opens or is shown again.
     */
    fun ensureAgentRunning() {
        if (disposed || agentStarting) return
        if (rpc?.isRunning == true) return
        startAgent(quiet = true)
    }

    /**
     * Start `pi --mode rpc`, resuming the loaded session when there is one.
     *
     * Concurrent callers coalesce into one process: while a start is in flight, later callers
     * only queue their [onReady] and receive the same client (or null). [quiet] keeps failures
     * on the status line — for background starts — while user-initiated starts raise a dialog.
     */
    private fun startAgent(quiet: Boolean = false, onReady: (PiRpcClient?) -> Unit = {}) {
        rpc?.takeIf { it.isRunning }?.let { live -> onReady(live); return }

        waitingForStart.addLast(onReady)
        if (agentStarting) return
        agentStarting = true
        startEpoch++
        val epoch = startEpoch

        /** Fail every queued waiter; the start they were waiting for will not happen. */
        fun failQueued() {
            agentStarting = false
            discardInFlightStart = false
            while (waitingForStart.isNotEmpty()) waitingForStart.removeFirst()(null)
        }

        val workDir = project.basePath?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))

        setStatus(PiBundle.message("status.startingPi"))
        ApplicationManager.getApplication().executeOnPooledThread {
            // Discovery can end up asking a login shell where pi lives; keep that off the EDT.
            val executable = PiLocator.findPi()
            if (executable == null) {
                onEdt {
                    setStatus(PiBundle.message("status.piNotFound"))
                    if (!quiet) {
                        Messages.showErrorDialog(
                            project,
                            "The pi CLI was not found.\n\nInstall it with:\n    npm i -g @earendil-works/pi-coding-agent\n\n" +
                                "If it is installed somewhere unusual, set the path in Settings → Tools → Pi GUI.",
                            "Pi Not Found",
                        )
                    }
                    failQueued()
                }
                return@executeOnPooledThread
            }

            val client = PiRpcClient(
                piExecutable = executable,
                workingDir = workDir,
                environment = PiLocator.shellEnvironment(),
                sessionFile = session?.filePath,
                extraArgs = PiSettings.getInstance().parsedExtraArgs(),
            )
            client.addListener(AgentListener(client))
            try {
                client.start()
            } catch (e: Exception) {
                log.warn("Failed to start pi", e)
                onEdt {
                    setStatus("Failed to start pi")
                    if (!quiet) {
                        Messages.showErrorDialog(project, "Could not start pi:\n${e.message}", "Pi GUI")
                    }
                    failQueued()
                }
                return@executeOnPooledThread
            }
            onEdt {
                if (epoch != startEpoch) {
                    // Superseded by a newer start (stop + restart raced this landing).
                    client.stop()
                    return@onEdt
                }
                val discarded = discardInFlightStart
                discardInFlightStart = false
                agentStarting = false
                if (disposed || discarded) {
                    // Stopped while booting (session switch, /quit, disposal): nobody wants
                    // this client. Waiters that still expect an agent get a fresh start below.
                    client.stop()
                    if (!disposed && discarded && waitingForStart.isNotEmpty()) startAgent(quiet = true)
                    return@onEdt
                }
                rpc = client
                agentProvidersFingerprint = providersFingerprint()
                // A new process reads its own defaults; until get_state answers nothing is live.
                lastLiveProvider = null
                lastLiveModel = null
                lastLiveThinking = null
                while (waitingForStart.isNotEmpty()) waitingForStart.removeFirst()(client)
                refreshModels(client)
                // A live agent is the authoritative source, and costs nothing extra to ask.
                commandRegistry.refreshFrom(client) { if (!disposed) updateCommandPopup() }
                // Resuming a session inherits its context; show where it stands before the
                // first prompt rather than leaving the footer blank until the next reply.
                refreshContextUsage()
            }
        }
    }

    /**
     * Hand the live agent off to the background instead of killing it.
     *
     * Only worth doing while a turn is in flight — an idle agent has nothing to lose, and leaving
     * processes about costs memory. Returns true when an agent was backgrounded.
     */
    private fun detachRunningAgent(): Boolean {
        val client = rpc ?: return false
        if (!isRunning) return false
        // No file yet means pi has not created one; key it uniquely so it is still stopped later,
        // it just cannot be adopted back.
        val key = liveSessionFile ?: session?.filePath ?: "pi.detached.${System.nanoTime()}"

        detached[key]?.takeIf { it !== client }?.stop()
        detached[key] = client
        client.addListener(DetachedListener(client))

        // Hand over without stopping the process: stopAgent() would kill the very turn this is
        // trying to save, so only the panel's own state is wound down here.
        rpc = null
        repaintTimer.stop()
        eagerStartTimer.stop()
        streaming.end()
        streamingComponent = null
        setRunning(false)
        phase = AgentPhase.Idle
        providerCombo.isEnabled = false
        modelCombo.isEnabled = false
        thinkingCombo.isEnabled = false
        liveSessionFile = null
        return true
    }

    /** Watches a backgrounded agent so its process does not outlive the turn it was kept for. */
    private inner class DetachedListener(private val client: PiRpcClient) : PiRpcClient.Listener {
        override fun onEvent(event: JsonObject) {
            val type = event.get("type")?.asStringOrNull() ?: return
            if (type != "agent_end") return
            if (event.get("willRetry")?.asBoolean == true) return
            onEdt { releaseDetached(client) }
        }

        override fun onExit(exitCode: Int, stderr: String) {
            onEdt { detached.values.remove(client) }
        }
    }

    /** The turn finished: stop the process, unless the user came back and it is live again. */
    private fun releaseDetached(client: PiRpcClient) {
        if (rpc === client) return
        if (detached.values.remove(client)) {
            client.stop()
            onSessionChanged?.invoke()
        }
    }

    private fun stopAgent(expectRestart: Boolean = false) {
        liveSessionFile = null
        repaintTimer.stop()
        eagerStartTimer.stop()
        rpc?.stop()
        rpc = null
        streaming.end()
        streamingComponent = null
        setRunning(false)
        phase = AgentPhase.Idle
        providerCombo.isEnabled = false
        modelCombo.isEnabled = false
        thinkingCombo.isEnabled = false
        if (agentStarting) {
            // A start is still in flight; its client belongs to the era being stopped now.
            if (expectRestart) {
                discardInFlightStart = true
            } else {
                startEpoch++
                agentStarting = false
                discardInFlightStart = false
                while (waitingForStart.isNotEmpty()) waitingForStart.removeFirst()(null)
            }
        }
    }

    /** Friendly label for a pi provider id: imported ones carry their cc-switch name. */
    private fun providerLabel(id: String): String =
        importedProviderLabels[id] ?: id.removePrefix(ProvidersRegistry.ID_PREFIX)

    /** Change marker over the registry sidecar and pi's models.json. */
    private fun providersFingerprint(): String {
        if (!PiSettings.getInstance().credentialAccessGranted) return "credential-access-denied"
        val registry = ProvidersRegistry.default()
        return listOf(registry.sidecarFile(), registry.modelsFile())
            .joinToString("|") { "${it.length()}:${it.lastModified()}" }
    }

    /** Provider ids currently offered by the footer combo. */
    private fun providerIds(): List<String> {
        val comboModel = providerCombo.model
        return (0 until comboModel.size).map { comboModel.getElementAt(it).id }
    }

    /**
     * Points the footer at one provider: the model combo is refiltered to its models and the
     * preferred (or first) model is selected. Returns the selected model, or null when the
     * provider is unknown.
     */
    private fun selectProvider(provider: String, preferredModel: String?): ModelOption? {
        val models = allModels.filter { it.provider == provider }
        if (models.isEmpty()) return null
        suppressModelEvents = true
        providerCombo.selectedItem = providerCombo.model.let { m ->
            (0 until m.size).map { m.getElementAt(it) }.firstOrNull { it.id == provider }
        }
        modelCombo.model = DefaultComboBoxModel(models.toTypedArray())
        val pick = models.firstOrNull { it.id == preferredModel } ?: models.first()
        modelCombo.selectedItem = pick
        suppressModelEvents = false
        pushModels()
        return pick
    }

    /** Applies a provider/model choice to the live agent and remembers it for next time. */
    private fun applySelectionToAgent(option: ModelOption) {
        val settings = PiSettings.getInstance()
        settings.activeProvider = option.provider
        settings.activeModel = option.id
        rpc?.setModel(option.provider, option.id) { response ->
            if (response.get("success")?.asBoolean == true) {
                onEdt {
                    lastLiveProvider = option.provider
                    lastLiveModel = option.id
                }
            } else {
                onEdt {
                    setStatus("Could not switch model: ${response.get("error")?.asStringOrNull() ?: "unknown error"}")
                    // The agent kept its previous model; line the combos back up with it.
                    rpc?.let { refreshState(it) }
                }
            }
        }
    }

    /** Provider entry in the footer combo; carries the friendly label shown to the user. */
    private data class ProviderOption(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private fun refreshModels(client: PiRpcClient) {
        // Reader thread: pick up friendly names for imported providers before hopping to the EDT.
        importedProviderLabels = runCatching { ProvidersRegistry.default().list() }
            .getOrDefault(emptyList())
            .associate { it.id to it.name }

        client.getAvailableModels { response ->
            val models = response.getAsJsonObjectOrNull("data")
                ?.let { PiJson.asArray(it.get("models")) }
                ?.mapNotNull { element ->
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val provider = obj.get("provider")?.asStringOrNull() ?: return@mapNotNull null
                    val id = obj.get("id")?.asStringOrNull() ?: return@mapNotNull null
                    ModelOption(provider, id, obj.get("reasoning")?.asBoolean ?: false)
                } ?: emptyList()
            onEdt { applyModelList(models, client) }
        }
        client.getAvailableThinkingLevels { response ->
            val levels = response.getAsJsonObjectOrNull("data")
                ?.let { PiJson.asArray(it.get("levels")) }
                ?.mapNotNull { it.asStringOrNull() } ?: emptyList()
            onEdt {
                if (levels.isEmpty()) return@onEdt
                suppressModelEvents = true
                thinkingCombo.model = DefaultComboBoxModel(levels.toTypedArray())
                thinkingCombo.isEnabled = true
                PiSettings.getInstance().activeThinking
                    .takeIf { saved -> saved.isNotBlank() && levels.contains(saved) }
                    ?.let { thinkingCombo.selectedItem = it }
                suppressModelEvents = false
                pushModels()
            }
        }
    }

    /**
     * Populates the footer combos from a model list and restores the remembered selection.
     *
     * Runs on the EDT with the parsed `get_available_models` payload. An empty list means the
     * agent offered nothing to choose from; the combos stay as they are and live state is
     * still synced (thinking level, session file).
     */
    private fun applyModelList(models: List<ModelOption>, client: PiRpcClient) {
        if (models.isEmpty()) {
            refreshState(client)
            return
        }
        allModels = models
        val settings = PiSettings.getInstance()

        suppressModelEvents = true
        providerCombo.model = DefaultComboBoxModel(
            models.map { it.provider }.distinct()
                .map { ProviderOption(it, providerLabel(it)) }
                .toTypedArray()
        )
        providerCombo.isEnabled = true
        suppressModelEvents = false

        val savedProvider = settings.activeProvider.takeIf { p -> providerIds().contains(p) }
        selectProvider(savedProvider ?: models.first().provider, settings.activeModel.takeIf { it.isNotBlank() })
        // selectProvider() refilled the model combo with one provider's slice; without this it
        // stays disabled from the last stopAgent()/onExit() and looks dead even though the agent
        // is live and switching would work.
        modelCombo.isEnabled = true

        // First contact with a fresh agent: make the remembered selection take effect.
        // The state query only goes out once this push has landed — a get_state answered
        // before the set_model would report the startup default and clobber the very
        // selection it is meant to confirm.
        val firstContact = restoredForClient !== client
        restoredForClient = client
        val selected = modelCombo.selectedItem as? ModelOption
        val hasSavedChoice = settings.activeProvider.isNotBlank() && selected != null &&
            selected.provider == settings.activeProvider
        val needsSwitch = selected != null &&
            (lastLiveProvider == null || lastLiveModel == null ||
                selected.provider != lastLiveProvider || selected.id != lastLiveModel)
        // A resumed session keeps the model it was recorded with; a fresh chat runs with
        // the remembered provider/model.
        val restore = selected?.takeIf {
            firstContact && session == null && hasSavedChoice && needsSwitch
        }
        if (restore != null) {
            client.setModel(restore.provider, restore.id) { response ->
                onEdt {
                    if (response.get("success")?.asBoolean != true) {
                        setStatus("Could not switch model: ${response.get("error")?.asStringOrNull() ?: "unknown error"}")
                    }
                }
                refreshState(client)
            }
        } else {
            refreshState(client)
        }
        if (firstContact) {
            val savedThinking = settings.activeThinking
            if (savedThinking.isNotBlank() && savedThinking != lastLiveThinking) {
                client.setThinkingLevel(savedThinking)
            }
        }
    }

    private fun refreshState(client: PiRpcClient) {
        client.getState { response ->
            val data = response.getAsJsonObjectOrNull("data") ?: return@getState
            val model = data.getAsJsonObjectOrNull("model")
            val provider = model?.get("provider")?.asStringOrNull()
            val modelId = model?.get("id")?.asStringOrNull()
            val level = data.get("thinkingLevel")?.asStringOrNull()
            val sessionFile = data.get("sessionFile")?.asStringOrNull()
            onEdt {
                suppressModelEvents = true
                if (provider != null && modelId != null) {
                    // Refilter when the live provider is not the one currently displayed.
                    val showing = (providerCombo.selectedItem as? ProviderOption)?.id
                    if (showing != provider && providerIds().contains(provider)) {
                        selectProvider(provider, modelId)
                    }
                    val target = ModelOption(provider, modelId)
                    val model2 = modelCombo.model
                    for (i in 0 until model2.size) {
                        val item = model2.getElementAt(i)
                        if (item.provider == target.provider && item.id == target.id) {
                            modelCombo.selectedIndex = i
                            break
                        }
                    }
                }
                level?.let { thinkingCombo.selectedItem = it }
                suppressModelEvents = false
                pushModels()

                // Once the remembered selection has been restored, the live agent is the source
                // of truth: whatever it actually runs with becomes the remembered default.
                val settings = PiSettings.getInstance()
                if (restoredForClient === rpc) {
                    if (provider != null && modelId != null &&
                        (settings.activeProvider != provider || settings.activeModel != modelId)
                    ) {
                        settings.activeProvider = provider
                        settings.activeModel = modelId
                    }
                    level?.takeIf { it.isNotBlank() && it != settings.activeThinking }?.let {
                        settings.activeThinking = it
                    }
                }
                lastLiveProvider = provider
                lastLiveModel = modelId
                lastLiveThinking = level

                sessionFile?.let { liveSessionFile = it }

                // A brand-new session gets its file only once pi creates it.
                if (session == null && sessionFile != null) {
                    onSessionChanged?.invoke()
                }
                setStatus(statusSummary())
            }
        }
    }

    // --------------------------------------------------------------- events

    /**
     * Routes one agent's events into the UI — and only while that agent is the live one.
     *
     * A backgrounded agent keeps this listener attached, so without the identity check its
     * messages would stream into whatever conversation the user had moved on to.
     */
    private inner class AgentListener(private val client: PiRpcClient) : PiRpcClient.Listener {
        override fun onEvent(event: JsonObject) {
            val type = event.get("type")?.asStringOrNull() ?: return
            onEdt { if (rpc === client) handleEvent(type, event) }
        }

        override fun onExit(exitCode: Int, stderr: String) {
            onEdt {
                if (disposed || rpc !== client) return@onEdt
                setRunning(false)
                streaming.end()
                flushStreaming()
                rpc = null
                providerCombo.isEnabled = false
                modelCombo.isEnabled = false
                thinkingCombo.isEnabled = false
                if (exitCode != 0) {
                    val detail = stderr.trim().lines().takeLast(4).joinToString("\n")
                    appendMessage(
                        PiMessage.Notice(
                            "pi exited (code $exitCode)" + if (detail.isNotBlank()) ": $detail" else ""
                        )
                    )
                }
                setStatus(statusSummary())
            }
        }
    }

    private fun handleEvent(type: String, event: JsonObject) {
        if (disposed) return
        when (type) {
            "agent_start" -> {
                setRunning(true)
                phase = AgentPhase.WaitingModel
            }

            "message_start" -> {
                val message = event.getAsJsonObjectOrNull("message") ?: return
                if (message.get("role")?.asStringOrNull() == "assistant") {
                    stepStartedAt = System.currentTimeMillis()
                    streaming.snapshot(message)
                    scheduleStreamingRepaint()
                }
            }

            "message_update" -> {
                val delta = event.getAsJsonObjectOrNull("assistantMessageEvent") ?: return
                if (streaming.applyDelta(delta)) scheduleStreamingRepaint()
            }

            "message_end" -> {
                val message = event.getAsJsonObjectOrNull("message") ?: return
                streaming.end()
                repaintTimer.stop()
                flushStreaming()

                val role = message.get("role")?.asStringOrNull()
                if (role == "user") {
                    // The prompt we already added optimistically comes back here; skip that echo
                    // but still render later queued deliveries.
                    val parsed = PiJson.parseUserMessage(message)
                    val pending = pendingUserText
                    if (pending != null && parsed?.text?.trim() == pending.trim()) {
                        pendingUserText = null
                        return
                    }
                }
                val parsed = PiJson.parseMessage(message)
                if (parsed is PiMessage.Assistant) {
                    // Measured here rather than inferred from timestamps: this is the real
                    // wall-clock latency of the step the user just watched.
                    stepStartedAt?.let { started ->
                        val elapsed = System.currentTimeMillis() - started
                        parsed.durationMs = elapsed
                        lastStepDurationMs = elapsed
                    }
                    stepStartedAt = null
                }
                parsed?.let { appendMessage(it) }
            }

            "tool_execution_start", "tool_execution_update" -> {
                val name = event.get("toolName")?.asStringOrNull() ?: "tool"
                val existing = (phase as? AgentPhase.RunningTools)?.tools.orEmpty()
                phase = AgentPhase.RunningTools((existing + name).distinct())
                setStatus(statusSummary())
            }

            "tool_execution_end" -> {
                val name = event.get("toolName")?.asStringOrNull()
                val remaining = (phase as? AgentPhase.RunningTools)?.tools.orEmpty().filter { it != name }
                phase = if (remaining.isEmpty()) AgentPhase.WaitingModel else AgentPhase.RunningTools(remaining)
                setStatus(statusSummary())
            }

            "compaction_start", "auto_compaction_start" -> {
                phase = AgentPhase.Compacting(event.get("reason")?.asStringOrNull() ?: "threshold")
                setStatus(statusSummary())
            }

            "compaction_end", "auto_compaction_end" -> {
                phase = AgentPhase.WaitingModel
                appendMessage(PiMessage.Notice(PiBundle.message("message.contextCompacted")))
                setStatus(statusSummary())
                // pi reports a null percentage until the next assistant reply, so the footer
                // clears here rather than keeping the pre-compaction figure on screen.
                refreshContextUsage()
            }

            "auto_retry_start" -> {
                phase = AgentPhase.Retrying(
                    event.get("attempt")?.asInt ?: 1,
                    event.get("maxAttempts")?.asInt ?: 1,
                    event.get("errorMessage")?.asStringOrNull() ?: "",
                )
                setStatus(statusSummary())
            }

            "auto_retry_end" -> {
                phase = AgentPhase.WaitingModel
                setStatus(statusSummary())
            }

            "prompt_error" -> {
                val error = event.get("error")?.asStringOrNull() ?: "The prompt failed"
                appendMessage(PiMessage.Notice("Error: $error"))
                setRunning(false)
            }

            "extension_error" -> {
                val error = event.get("error")?.asStringOrNull() ?: return
                appendMessage(PiMessage.Notice("Extension error: $error"))
            }

            "prompt_done", "agent_settled" -> {
                setRunning(false)
                phase = AgentPhase.Idle
                streaming.end()
                repaintTimer.stop()
                flushStreaming()
                setStatus(statusSummary())
                // The turn just grew the context; the footer figure is stale until re-read.
                refreshContextUsage()
                // pi writes the session file as the run completes; refresh the sidebar.
                onSessionChanged?.invoke()
            }

            "agent_end" -> {
                if (event.get("willRetry")?.asBoolean == true) return
                phase = AgentPhase.Idle
                setStatus(statusSummary())
            }

            "thinking_level_changed" -> {
                val level = event.get("level")?.asStringOrNull() ?: return
                suppressModelEvents = true
                thinkingCombo.selectedItem = level
                suppressModelEvents = false
                pushModels()
            }

            "session_info_changed" -> onSessionChanged?.invoke()

            "extension_ui_request" -> handleExtensionUiRequest(event)
        }
    }

    /** Extensions can ask for input mid-run; answer with the IDE's own dialogs. */
    private fun handleExtensionUiRequest(event: JsonObject) {
        val id = event.get("id")?.asStringOrNull() ?: return
        val method = event.get("method")?.asStringOrNull() ?: return
        val title = event.get("title")?.asStringOrNull() ?: "pi"

        fun reply(build: JsonObject.() -> Unit) {
            val response = JsonObject()
            response.addProperty("type", "extension_ui_response")
            response.addProperty("id", id)
            response.build()
            rpc?.sendExtensionUiResponse(response)
        }

        when (method) {
            "select" -> {
                val options = PiJson.asArray(event.get("options"))
                    ?.mapNotNull { it.asStringOrNull() }.orEmpty()
                if (options.isEmpty()) { reply { addProperty("cancelled", true) }; return }
                // Returns the chosen index, or -1 when the dialog is dismissed.
                val choice = Messages.showDialog(
                    project, title, "Pi", options.toTypedArray(), 0, null,
                )
                if (choice < 0) reply { addProperty("cancelled", true) }
                else reply { addProperty("value", options[choice]) }
            }

            "confirm" -> {
                val message = event.get("message")?.asStringOrNull() ?: title
                val result = Messages.showYesNoDialog(project, message, title, null)
                reply { addProperty("confirmed", result == Messages.YES) }
            }

            "input", "editor" -> {
                val prefill = event.get("prefill")?.asStringOrNull()
                    ?: event.get("placeholder")?.asStringOrNull() ?: ""
                val value = Messages.showInputDialog(project, title, "Pi", null, prefill, null)
                if (value == null) reply { addProperty("cancelled", true) }
                else reply { addProperty("value", value) }
            }

            "notify" -> {
                val message = event.get("message")?.asStringOrNull() ?: return
                appendMessage(PiMessage.Notice(message))
            }

            "setStatus" -> {
                event.get("statusText")?.asStringOrNull()?.let { setStatus(it) }
            }

            // Widgets and titles have no native equivalent here; acknowledge so pi does not block.
            else -> reply { addProperty("cancelled", true) }
        }
    }

    // ---------------------------------------------------------------- status

    private fun setRunning(running: Boolean) {
        isRunning = running
        // Compacting mid-turn fights the run that is already using the context; the surface
        // hides the stop button and disables compact accordingly.
        surface.setRunning(running)
    }

    private fun statusSummary(): String {
        val parts = mutableListOf<String>()
        parts.add(
            when (val p = phase) {
                is AgentPhase.Idle -> if (rpc?.isRunning == true) PiBundle.message("status.ready") else PiBundle.message("status.idle")
                is AgentPhase.WaitingModel -> PiBundle.message("status.thinking")
                is AgentPhase.RunningTools -> PiBundle.message("status.runningTools", p.tools.joinToString(", "))
                is AgentPhase.Compacting -> PiBundle.message("status.compacting", p.reason)
                is AgentPhase.Retrying -> PiBundle.message("status.retrying", p.attempt, p.maxAttempts)
            }
        )

        if (isRunning) {
            // Counts from the start of the current step, so the user can see it is not stuck.
            stepStartedAt?.let { parts.add(SessionStore.formatDuration(System.currentTimeMillis() - it)) }
        } else {
            lastStepDurationMs?.let { parts.add(PiBundle.message("status.took", SessionStore.formatDuration(it))) }
        }

        session?.let { parts.add(it.displayTitle().take(40)) }
        return parts.joinToString("   ·   ")
    }

    private fun setStatus(text: String) {
        statusLabel.text = text.ifBlank { " " }
    }

    /**
     * Presents the existing Swing widgets as a [ChatSurface].
     *
     * The fallback path is left exactly as it was rather than extracted into its own class: its
     * composer carries key bindings, a transfer handler and a completion popup that cannot be
     * verified without a running IDE, and moving them would risk the one path that has to keep
     * working where JCEF does not. The adapter only forwards.
     */
    private inner class SwingChatAdapter : ChatSurface {

        private val transcript = SwingTranscriptSurface(project)

        override val component: JComponent get() = transcript.component

        override var onLoadEarlier: (() -> Unit)?
            get() = transcript.onLoadEarlier
            set(value) { transcript.onLoadEarlier = value }

        // The Swing composer talks to ChatPanel directly, so these stay unused.
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

        override fun setMessages(messages: List<PiMessage>, hiddenCount: Int) =
            transcript.setMessages(messages, hiddenCount)

        override fun appendMessage(message: PiMessage) = transcript.appendMessage(message)

        override fun prependMessages(messages: List<PiMessage>, hiddenCount: Int) =
            transcript.prependMessages(messages, hiddenCount)

        override fun setStreaming(message: PiMessage?) = transcript.setStreaming(message)

        override fun showEmptyState(projectPath: String?) = transcript.showEmptyState(projectPath)

        override fun applySettings() = transcript.applySettings()

        override fun scrollToBottom() = transcript.scrollToBottom()

        override fun isNearBottom(): Boolean = transcript.isNearBottom()

        override fun renderedCount(): Int = transcript.renderedCount()

        override fun composerText(): String = input.text

        override fun setComposerText(text: String, focus: Boolean) {
            input.text = text
            if (focus) input.requestFocusInWindow()
        }

        override fun appendComposerText(text: String) {
            val existing = input.text
            val needsSpace = existing.isNotEmpty() && !existing.last().isWhitespace()
            input.text = buildString {
                append(existing)
                if (needsSpace) append(' ')
                append(text)
                append(' ')
            }
            input.caretPosition = input.text.length
        }

        override fun focusComposer() { input.requestFocusInWindow() }

        override fun setRunning(running: Boolean) {
            compactButton.isEnabled = !running
            sendButton.isEnabled = true
            sendButton.toolTipText =
                PiBundle.message(if (running) "chat.queue" else "chat.send")
            stopButton.isVisible = running
        }

        override fun setAttachments(items: List<ChatSurface.Attachment>) {
            attachmentStrip.setAttachments(attachments)
        }

        override fun setModels(models: ChatSurface.ModelChoices) {
            // The Swing combos are driven directly by applyModelList, which knows the real
            // ModelOption/ProviderOption objects rather than these plain ids.
        }

        override fun setContext(label: String?, tooltip: String?) {
            contextLabel.text = label.orEmpty()
            contextLabel.isVisible = label != null
            contextLabel.toolTipText = tooltip
            contextLabel.foreground = PiTheme.mutedFg()
        }

        override fun setCommands(items: List<ChatSurface.Command>) {
            // The Swing popup reads the registry itself.
        }

        override fun setEdits(items: List<ChatSurface.Edit>) = editsPanel.update(messages)

        override fun setSendOnEnter(sendOnEnter: Boolean) {
            // Read from settings at keypress time by the Swing key bindings.
        }

        override fun setSessions(items: List<ChatSurface.Session>, selectedPath: String?) {
            // The sidebar and toolbar stay native in this mode; PiMainPanel drives them directly.
        }

        override fun setSidebarVisible(visible: Boolean) {
            // As above — the splitter is the sidebar here.
        }

        override fun dispose() = transcript.dispose()
    }

    /** Copying from a code block in the web view still goes through the system clipboard. */
    private fun copyToClipboard(text: String) {
        try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        } catch (e: Exception) {
            log.warn("Could not copy to the clipboard", e)
        }
    }

    private fun onEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block)
    }

    fun focusInput() {
        surface.focusComposer()
    }

    /**
     * Re-applies theme, font and language after the settings dialog is accepted.
     * The transcript is re-rendered because message components bake in colors and fonts.
     */
    fun applySettings() {
        background = PiTheme.chatBg
        center.background = PiTheme.chatBg
        surface.applySettings()
        surface.setSendOnEnter(PiSettings.getInstance().sendOnEnter)

        statusLabel.foreground = PiTheme.mutedFg()
        branchLabel.foreground = PiTheme.mutedFg()
        branchLabel.toolTipText = PiBundle.message("chat.branch.tooltip")

        input.font = PiTheme.uiFont()
        input.background = PiTheme.inputBg
        input.caretColor = PiTheme.textFg()
        input.emptyText.text = PiBundle.message("chat.input.placeholder")

        sendButton.toolTipText = if (isRunning) PiBundle.message("chat.queue") else PiBundle.message("chat.send")
        stopButton.toolTipText = PiBundle.message("chat.stop")
        compactButton.toolTipText = PiBundle.message("chat.compact")
        contextLabel.font = PiTheme.uiFont().deriveFont(PiTheme.uiFont().size2D - 1f)
        updateContextLabel()
        providerCombo.toolTipText = PiBundle.message("chat.provider")
        modelCombo.toolTipText = PiBundle.message("chat.model")
        thinkingCombo.toolTipText = PiBundle.message("chat.thinking")
        providerCombo.renderer = placeholderRenderer(PiBundle.message("chat.provider"))
        modelCombo.renderer = placeholderRenderer(PiBundle.message("chat.model.none"))
        thinkingCombo.renderer = placeholderRenderer(PiBundle.message("chat.thinking"))

        // Provider data changed under us (import/edit/delete): the running agent read
        // models.json at process start and cannot see new providers, so switching to them
        // fails with "Model not found". Restart it — same as /reload — and refreshModels
        // will apply the remembered selection to the fresh agent and its combos.
        val settings = PiSettings.getInstance()
        if (rpc?.isRunning == true && providersFingerprint() != agentProvidersFingerprint) {
            agentProvidersFingerprint = providersFingerprint()
            if (isRunning) {
                appendMessage(PiMessage.Notice(PiBundle.message("chat.providers.pendingReload")))
            } else {
                reloadAgent()
            }
        } else {
            // A provider enabled in the settings dialog takes effect here immediately.
            settings.activeProvider.takeIf { it.isNotBlank() }?.let { provider ->
                if (providerIds().contains(provider)) {
                    val pick = selectProvider(provider, settings.activeModel.takeIf { it.isNotBlank() })
                    if (pick != null && rpc?.isRunning == true &&
                        (pick.provider != lastLiveProvider || pick.id != lastLiveModel)
                    ) {
                        rpc?.setModel(pick.provider, pick.id)
                        settings.activeThinking.takeIf { it.isNotBlank() && it != lastLiveThinking }?.let {
                            rpc?.setThinkingLevel(it)
                        }
                    }
                }
            }
        }

        if (messages.isEmpty()) showEmptyState() else rebuildTranscript()
        setStatus(statusSummary())
        revalidate()
        repaint()
    }

    /**
     * Append text to the composer without disturbing what the user already typed.
     * Used by the "Send … to Pi GUI" actions to drop in `@path` mentions.
     */
    fun appendToInput(text: String) {
        if (text.isBlank()) return
        surface.appendComposerText(text)
    }

    fun currentSession(): SessionInfo? = session

    /**
     * A rename from the sidebar for the currently loaded session: swap the stored info so the
     * status strip (which shows the title) stays truthful. Other sessions need nothing.
     */
    fun applySessionRename(info: SessionInfo) {
        val current = session ?: return
        if (current.filePath != info.filePath) return
        session = info
        setStatus(statusSummary())
    }

    @org.jetbrains.annotations.TestOnly
    fun composerText(): String = input.text

    @org.jetbrains.annotations.TestOnly
    fun loadedSessionPathForTest(): String? = loadedSessionPath

    @org.jetbrains.annotations.TestOnly
    fun transcriptChildCountForTest(): Int = surface.renderedCount()

    /** What the switch put on screen before yielding the EDT. */
    @org.jetbrains.annotations.TestOnly
    fun eagerComponentCountForTest(): Int = eagerComponentCount

    @org.jetbrains.annotations.TestOnly
    fun inputForTest(): JBTextArea = input

    @org.jetbrains.annotations.TestOnly
    fun attachmentsForTest(): List<Attachment> = attachments.toList()

    @org.jetbrains.annotations.TestOnly
    fun addAttachmentForTest(attachment: Attachment) = addAttachment(attachment)

    @org.jetbrains.annotations.TestOnly
    fun clearAttachmentsForTest() = clearAttachments()

    @org.jetbrains.annotations.TestOnly
    fun applyContextUsageForTest(data: JsonObject?) = applyContextUsage(data)

    @org.jetbrains.annotations.TestOnly
    fun contextLabelForTest(): JBLabel = contextLabel

    @org.jetbrains.annotations.TestOnly
    fun compactButtonForTest(): PiButton = compactButton

    @org.jetbrains.annotations.TestOnly
    fun commandPopupForTest(): CommandPopup = commandPopup

    @org.jetbrains.annotations.TestOnly
    fun commandCompletionForTest(): CommandCompletion = lastCompletion

    @org.jetbrains.annotations.TestOnly
    fun commandRegistryForTest(): CommandRegistry = commandRegistry

    @org.jetbrains.annotations.TestOnly
    fun providerComboForTest(): JComboBox<*> = providerCombo

    @org.jetbrains.annotations.TestOnly
    fun modelComboForTest(): JComboBox<*> = modelCombo

    @org.jetbrains.annotations.TestOnly
    fun sendButtonForTest(): dev.pi.gui.ui.components.PiButton = sendButton

    @org.jetbrains.annotations.TestOnly
    fun stopButtonForTest(): dev.pi.gui.ui.components.PiButton = stopButton

    @org.jetbrains.annotations.TestOnly
    fun setRunningForTest(running: Boolean) = setRunning(running)

    @org.jetbrains.annotations.TestOnly
    fun tickForTest() { if (isRunning) setStatus(statusSummary()); refreshBranch() }

    @org.jetbrains.annotations.TestOnly
    fun editsUpdateForTest() = editsPanel.update(messages)

    /** Drives one real streaming flush, including revalidate and the bottom re-pin. */
    @org.jetbrains.annotations.TestOnly
    fun flushStreamingForTest(text: String) {
        streaming.seedForTest(dev.pi.gui.model.PiMessage.Assistant(
            blocks = mutableListOf<dev.pi.gui.model.ContentBlock>(
                dev.pi.gui.model.ContentBlock.Text(text)
            ),
            model = "m",
        ))
        flushStreaming()
    }

    @org.jetbrains.annotations.TestOnly
    fun scrollToBottomForTest() = scrollToBottom()

    /** Applies a parsed `get_available_models` payload exactly as the live path would. */
    @org.jetbrains.annotations.TestOnly
    fun applyModelsForTest(models: List<ModelOption>, client: PiRpcClient) = applyModelList(models, client)

    @org.jetbrains.annotations.TestOnly
    fun insertCommandForTest(command: PiCommand) = insertCommand(command)

    @org.jetbrains.annotations.TestOnly
    fun runBuiltinForTest(text: String): Boolean = runBuiltinCommand(text)

    @org.jetbrains.annotations.TestOnly
    fun messagesForTest(): List<PiMessage> = messages.toList()

    /** Puts a client in place as the live agent, mid-turn, without spawning anything. */
    fun attachAgentForTest(client: PiRpcClient, sessionFile: String? = null) {
        rpc = client
        liveSessionFile = sessionFile
        setRunning(true)
    }

    fun liveAgentForTest(): PiRpcClient? = rpc

    fun detachedAgentsForTest(): List<PiRpcClient> = detached.values.toList()

    @org.jetbrains.annotations.TestOnly
    fun setTranscriptForPreview(preview: List<PiMessage>) {
        messages.clear()
        messages.addAll(preview)
        rebuildTranscript()
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_CHAT = "chat"
        private const val VISIBLE_PAGE_SIZE = 60

        /**
         * Below this share of the context window there is nothing worth compacting: the run costs
         * a model call and throws away detail, so the button says so instead of doing it.
         */
        const val COMPACT_MIN_PERCENT = 20.0

        /**
         * A null percentage means pi has no figure yet — no model bound, or straight after a
         * compaction. Unknown is not proof of "too small", so it is allowed through rather than
         * blocking a legitimate compaction.
         */
        fun isTooSmallToCompact(percent: Double?): Boolean =
            percent != null && percent < COMPACT_MIN_PERCENT

        /** Sub-1% shows as `<1%`, so a session with real content never reads as an empty one. */
        fun formatContextPercent(percent: Double): String =
            if (percent > 0.0 && percent < 1.0) "<1%" else "${Math.round(percent)}%"

        /**
         * How many lines of message content the window may lay out initially, and how much a
         * "load earlier" click adds. ~130µs per line of HTML parse+layout puts 2000 lines at a
         * roughly quarter-second one-time cost on switch; a heavy session before this budget
         * measured 4.7s of frozen EDT.
         */
        private const val INITIAL_LINE_BUDGET = 2_000

        /**
         * Rendered synchronously on a session switch — roughly a screenful. Everything past this
         * arrives via the chunked fill, which keeps the visible switch at a few milliseconds.
         */
        /**
         * How much of the composer the completion logic reads. No command name approaches this,
         * and a caret past it means the user is in the message body, where `queryAt` returns
         * nothing anyway.
         */
        private const val COMMAND_PREFIX_CHARS = 256

        /** Composer bounds, in unscaled pixels: roughly three rows up to about ten. */
        private const val COMPOSER_MIN_HEIGHT = 60
        private const val COMPOSER_MAX_HEIGHT = 180

        private const val EAGER_PAGE_SIZE = 8
        private const val EAGER_LINE_BUDGET = 400

        /** Per event-queue turn during the fill; small enough to stay invisible. */
        private const val FILL_CHUNK_MESSAGES = 6
        private const val FILL_CHUNK_LINES = 300
        private const val PAGE_LINE_BUDGET = 4_000

        /** Long enough to absorb a burst of session switches, short enough to stay invisible. */
        private const val EAGER_START_DEBOUNCE_MS = 250

        /**
         * Join the typed text with its `@path` mentions.
         *
         * pi only recognises a command when the message *starts* with `/`, so a command has to
         * stay in front and the mentions become its arguments; anywhere else the leading `@path`
         * would demote the command to ordinary prose.
         */
        /**
         * Whether the conversation renders in the browser.
         *
         * `-Dpi.gui.forceSwingTranscript=true` pins the Swing view. It exists because JCEF is not
         * universally available — some distributions omit it, users can switch it off, and a
         * Chromium fault should not cost someone their chat history — and it is how the Swing
         * fallback gets exercised in tests.
         */
        fun useWebTranscript(): Boolean =
            PiWebView.isAvailable() && !java.lang.Boolean.getBoolean(FORCE_SWING_PROPERTY)

        const val FORCE_SWING_PROPERTY = "pi.gui.forceSwingTranscript"

        fun composeMessage(text: String, mentions: String): String {
            val parts = if (text.startsWith("/")) listOf(text, mentions) else listOf(mentions, text)
            return parts.filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    override fun dispose() {
        disposed = true
        commandPopup.hide()
        repaintTimer.stop()
        tickTimer.stop()
        eagerStartTimer.stop()
        stopAgent()
        // Nothing is left to read a backgrounded reply, so the processes go with the panel.
        detached.values.forEach { it.stop() }
        detached.clear()
    }

}
