package dev.pi.gui.ui

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
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
import dev.pi.gui.model.ModelOption
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.SessionInfo
import dev.pi.gui.rpc.PiJson
import dev.pi.gui.rpc.PiRpcClient
import dev.pi.gui.rpc.StreamingAssistant
import dev.pi.gui.rpc.asStringOrNull
import dev.pi.gui.rpc.getAsJsonObjectOrNull
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.components.PiButton
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
import java.awt.RenderingHints
import java.awt.event.ActionEvent
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

    private val transcript = TranscriptPanel()
    private val scrollPane = JBScrollPane(
        transcript,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    )
    private val input = JBTextArea()
    private val sendButton = PiButton(
        PiBundle.message("chat.send"), AllIcons.Actions.Execute, PiButton.Style.PRIMARY,
    )
    private val stopButton = PiButton(
        PiBundle.message("chat.stop"), AllIcons.Actions.Suspend, PiButton.Style.SECONDARY,
    )
    private val statusLabel = JBLabel(" ")
    private val branchLabel = JBLabel("")

    private val cards = CardLayout()
    private val center = JPanel(cards)
    private lateinit var emptyState: JComponent

    /** Start of the in-flight assistant step, used for the live timer and the message footer. */
    private var stepStartedAt: Long? = null
    private var lastStepDurationMs: Long? = null

    /** How many trailing messages are currently materialised. */
    private var visibleLimit = VISIBLE_PAGE_SIZE

    private val attachments = mutableListOf<Attachment>()
    private val attachmentStrip = AttachmentStrip { removeAttachment(it) }

    private val commandRegistry = CommandRegistry(project.basePath?.let(::File))
    private val commandPopup = CommandPopup { insertCommand(it) }
    /** The composer shell the popup hangs above; set once the composer is built. */
    private var commandAnchor: JComponent? = null

    private val editsPanel = EditsPanel(project)
    private val modelCombo = JComboBox<ModelOption>()
    private val thinkingCombo = JComboBox<String>()

    private val messages = mutableListOf<PiMessage>()
    private val streaming = StreamingAssistant()
    private var streamingComponent: JComponent? = null

    private var rpc: PiRpcClient? = null
    private var session: SessionInfo? = null
    private var isRunning = false
    private var phase: AgentPhase = AgentPhase.Idle
    private var pendingUserText: String? = null
    private var suppressModelEvents = false
    private var disposed = false

    /** Coalesces streaming deltas so a fast model does not force a repaint per token. */
    private val repaintTimer = Timer(80) { flushStreaming() }.apply { isRepeats = false }

    /** Drives the live elapsed counter and picks up branch switches while the panel is open. */
    private val tickTimer = Timer(500) {
        if (isRunning) setStatus(statusSummary())
        refreshBranch()
    }.apply { isRepeats = true }

    var onSessionChanged: (() -> Unit)? = null

    /** Set by the tool window so `/new` and `/resume` can drive the sidebar too. */
    var onNewSessionRequested: (() -> Unit)? = null
    var onShowSessionsRequested: (() -> Unit)? = null

    init {
        buildUi()
        showEmptyState()
        tickTimer.start()
    }

    // ---------------------------------------------------------------- UI setup

    private fun buildUi() {
        background = PiTheme.chatBg
        isOpaque = true

        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.background = PiTheme.chatBg
        scrollPane.background = PiTheme.chatBg
        transcript.background = PiTheme.chatBg
        transcript.isOpaque = true

        center.isOpaque = true
        center.background = PiTheme.chatBg
        emptyState = buildEmptyState()
        center.add(emptyState, CARD_EMPTY)
        center.add(scrollPane, CARD_CHAT)

        add(buildStatusStrip(), BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(editsPanel, BorderLayout.NORTH)
                add(buildComposer(), BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
    }

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
            background = PiTheme.surfaceBg
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(PiTheme.toolBorder),
                JBUI.Borders.empty(8, 10, 8, 10),
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

        val inputScroll = JBScrollPane(
            input,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        // Rounded shell around the text area; the outline brightens while the field has focus.
        val shell = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = PiTheme.inputBg
                    val a = JBUI.scale(10)
                    g2.fillRoundRect(0, 0, width, height, a, a)
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = false
            border = RoundedBorder(
                colorProvider = { if (input.hasFocus()) PiTheme.accent else PiTheme.buttonBorder },
                arc = 10,
                padding = JBUI.insets(6, 8),
            )
            preferredSize = Dimension(100, JBUI.scale(78))
            add(inputScroll, BorderLayout.CENTER)
        }
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

        composer.add(attachmentStrip, BorderLayout.NORTH)
        composer.add(shell, BorderLayout.CENTER)
        composer.add(buildControlRow(), BorderLayout.SOUTH)
        return composer
    }

    // ------------------------------------------------------------ attachments

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
     * Intercepts files and images, forwarding everything else — including all clipboard traffic —
     * to the component's original handler.
     *
     * Attachment handling lives here rather than on a Cmd+V key binding because the IDE's own
     * `$Paste` action can consume the keystroke before any Swing `InputMap` sees it. Every route
     * (IDE action, Swing paste, drag-and-drop) ends up calling `importData`, so this catches them
     * all.
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
        attachmentStrip.setAttachments(attachments)
        revalidate()
        repaint()
    }

    private fun removeAttachment(attachment: Attachment) {
        attachments.remove(attachment)
        attachmentStrip.setAttachments(attachments)
        revalidate()
        repaint()
    }

    private fun clearAttachments() {
        attachments.clear()
        attachmentStrip.setAttachments(attachments)
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
            rpc?.setModel(option.provider, option.id) { response ->
                if (response.get("success")?.asBoolean != true) {
                    onEdt { setStatus("Could not switch model: ${response.get("error")?.asStringOrNull() ?: "unknown error"}") }
                }
            }
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
        row.add(modelCombo)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(thinkingCombo)
        row.add(Box.createHorizontalGlue())
        row.add(stopButton)
        row.add(Box.createHorizontalStrut(JBUI.scale(6)))
        row.add(sendButton)
        return row
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
        val refresh = { SwingUtilities.invokeLater { updateCommandPopup() } }
        input.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { refresh() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { refresh() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })
        input.addCaretListener { refresh() }
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
        val query = PiCommand.queryAt(input.text, input.caretPosition)
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
            input.text = ""
            return true
        }

        // Everything below either needs no agent or brings one up on demand.
        when (name) {
            "new" -> onNewSessionRequested?.invoke() ?: startNewSession()
            "settings" -> PiSettingsDialog(project).show()
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
        input.text = ""
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
        stopAgent()
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

    /** Show an existing session's transcript, read from disk. The agent is not started yet. */
    fun loadSession(info: SessionInfo) {
        stopAgent()
        session = info
        messages.clear()
        visibleLimit = VISIBLE_PAGE_SIZE
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
    }

    /** Discard the current conversation and begin a fresh one in this project. */
    fun startNewSession() {
        stopAgent()
        commandPopup.hide()
        // Skills and extensions may have been added since; re-ask rather than serve a stale list.
        commandRegistry.invalidate()
        session = null
        messages.clear()
        visibleLimit = VISIBLE_PAGE_SIZE
        rebuildTranscript()
        showEmptyState()
        setStatus(PiBundle.message("status.newSession"))
        input.requestFocusInWindow()
    }

    private fun showEmptyState() {
        transcript.removeAll()
        streamingComponent = null
        cards.show(center, CARD_EMPTY)
    }

    /** Swap the watermark out for the transcript the moment there is anything to show. */
    private fun showTranscript() {
        cards.show(center, CARD_CHAT)
    }

    // ------------------------------------------------------------- transcript

    /**
     * Renders only the tail of the conversation.
     *
     * Every message costs an HTML layout pass, so materialising a few hundred of them at once
     * froze the UI for seconds when opening a long session. Older messages are one click away.
     */
    private fun rebuildTranscript() {
        transcript.removeAll()
        streamingComponent = null

        val hidden = (messages.size - visibleLimit).coerceAtLeast(0)
        if (hidden > 0) transcript.add(buildLoadEarlierRow(hidden))
        messages.drop(hidden).forEach { transcript.add(MessageRenderer.render(project, it)) }

        if (messages.isEmpty()) showEmptyState() else showTranscript()
        editsPanel.update(messages)
        transcript.revalidate()
        transcript.repaint()
    }

    private fun buildLoadEarlierRow(hidden: Int): JComponent {
        val row = StackPanel(0).apply { border = JBUI.Borders.empty(4, 0, 10, 0) }
        val button = PiButton(
            PiBundle.message("chat.loadEarlier", hidden), null, PiButton.Style.SECONDARY,
        )
        button.addActionListener {
            visibleLimit += VISIBLE_PAGE_SIZE
            rebuildTranscript()
        }
        row.add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(button)
        })
        return row
    }

    private fun appendMessage(message: PiMessage) {
        messages.add(message)
        visibleLimit++
        editsPanel.update(messages)
        showTranscript()
        // Insert before the live streaming bubble so ordering stays chronological.
        val streamingIdx = streamingComponent?.let { comp ->
            transcript.components.indexOfFirst { it === comp }
        } ?: -1
        val component = MessageRenderer.render(project, message)
        if (streamingIdx >= 0) transcript.add(component, streamingIdx) else transcript.add(component)
        transcript.revalidate()
        transcript.repaint()
        maybeScrollToBottom()
    }

    private fun flushStreaming() {
        val current = streaming.current
        if (current == null) {
            streamingComponent?.let { transcript.remove(it) }
            streamingComponent = null
            transcript.revalidate()
            transcript.repaint()
            return
        }
        val rendered = MessageRenderer.render(project, current)
        streamingComponent?.let { transcript.remove(it) }
        showTranscript()
        transcript.add(rendered)
        streamingComponent = rendered
        transcript.revalidate()
        transcript.repaint()
        maybeScrollToBottom()
    }

    private fun scheduleStreamingRepaint() {
        if (!repaintTimer.isRunning) repaintTimer.restart()
    }

    private fun isNearBottom(): Boolean {
        val bar = scrollPane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(120)
    }

    private fun maybeScrollToBottom() {
        if (isNearBottom()) scrollToBottom()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    // ----------------------------------------------------------------- sending

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty() && attachments.isEmpty()) return

        commandPopup.hide()

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

        input.text = ""
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

    /** Start `pi --mode rpc`, resuming the loaded session when there is one. */
    private fun startAgent(onReady: (PiRpcClient?) -> Unit) {
        val executable = PiLocator.findPi()
        if (executable == null) {
            Messages.showErrorDialog(
                project,
                "The pi CLI was not found.\n\nInstall it with:\n    npm i -g @earendil-works/pi-coding-agent\n\n" +
                    "If it is installed somewhere unusual, set the path in Settings → Tools → Pi GUI.",
                "Pi Not Found",
            )
            onReady(null)
            return
        }

        val workDir = project.basePath?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))

        setStatus(PiBundle.message("status.startingPi"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = PiRpcClient(
                piExecutable = executable,
                workingDir = workDir,
                environment = PiLocator.shellEnvironment(),
                sessionFile = session?.filePath,
                extraArgs = PiSettings.getInstance().parsedExtraArgs(),
            )
            client.addListener(AgentListener())
            try {
                client.start()
            } catch (e: Exception) {
                log.warn("Failed to start pi", e)
                onEdt {
                    setStatus("Failed to start pi")
                    Messages.showErrorDialog(project, "Could not start pi:\n${e.message}", "Pi GUI")
                    onReady(null)
                }
                return@executeOnPooledThread
            }
            onEdt {
                if (disposed) { client.stop(); return@onEdt }
                rpc = client
                onReady(client)
                refreshModels(client)
                refreshState(client)
                // A live agent is the authoritative source, and costs nothing extra to ask.
                commandRegistry.refreshFrom(client) { if (!disposed) updateCommandPopup() }
            }
        }
    }

    private fun stopAgent() {
        repaintTimer.stop()
        rpc?.stop()
        rpc = null
        streaming.end()
        streamingComponent = null
        setRunning(false)
        phase = AgentPhase.Idle
        modelCombo.isEnabled = false
        thinkingCombo.isEnabled = false
    }

    private fun refreshModels(client: PiRpcClient) {
        client.getAvailableModels { response ->
            val models = response.getAsJsonObjectOrNull("data")
                ?.let { PiJson.asArray(it.get("models")) }
                ?.mapNotNull { element ->
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val provider = obj.get("provider")?.asStringOrNull() ?: return@mapNotNull null
                    val id = obj.get("id")?.asStringOrNull() ?: return@mapNotNull null
                    ModelOption(provider, id, obj.get("reasoning")?.asBoolean ?: false)
                } ?: emptyList()
            onEdt {
                if (models.isEmpty()) return@onEdt
                suppressModelEvents = true
                modelCombo.model = DefaultComboBoxModel(models.toTypedArray())
                modelCombo.isEnabled = true
                suppressModelEvents = false
            }
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
                suppressModelEvents = false
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

                // A brand-new session gets its file only once pi creates it.
                if (session == null && sessionFile != null) {
                    onSessionChanged?.invoke()
                }
                setStatus(statusSummary())
            }
        }
    }

    // --------------------------------------------------------------- events

    private inner class AgentListener : PiRpcClient.Listener {
        override fun onEvent(event: JsonObject) {
            val type = event.get("type")?.asStringOrNull() ?: return
            onEdt { handleEvent(type, event) }
        }

        override fun onExit(exitCode: Int, stderr: String) {
            onEdt {
                if (disposed) return@onEdt
                setRunning(false)
                streaming.end()
                flushStreaming()
                rpc = null
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
        sendButton.isEnabled = true
        sendButton.text = if (running) PiBundle.message("chat.queue") else PiBundle.message("chat.send")
        stopButton.isVisible = running
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

    private fun onEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block)
    }

    fun focusInput() {
        input.requestFocusInWindow()
    }

    /**
     * Re-applies theme, font and language after the settings dialog is accepted.
     * The transcript is re-rendered because message components bake in colors and fonts.
     */
    fun applySettings() {
        background = PiTheme.chatBg
        scrollPane.viewport.background = PiTheme.chatBg
        scrollPane.background = PiTheme.chatBg
        transcript.background = PiTheme.chatBg
        center.background = PiTheme.chatBg

        statusLabel.foreground = PiTheme.mutedFg()
        branchLabel.foreground = PiTheme.mutedFg()
        branchLabel.toolTipText = PiBundle.message("chat.branch.tooltip")

        input.font = PiTheme.uiFont()
        input.background = PiTheme.inputBg
        input.caretColor = PiTheme.textFg()
        input.emptyText.text = PiBundle.message("chat.input.placeholder")

        sendButton.text = if (isRunning) PiBundle.message("chat.queue") else PiBundle.message("chat.send")
        stopButton.text = PiBundle.message("chat.stop")
        modelCombo.toolTipText = PiBundle.message("chat.model")
        thinkingCombo.toolTipText = PiBundle.message("chat.thinking")
        modelCombo.renderer = placeholderRenderer(PiBundle.message("chat.model.none"))
        thinkingCombo.renderer = placeholderRenderer(PiBundle.message("chat.thinking"))

        rebuildEmptyState()
        if (messages.isEmpty()) showEmptyState() else rebuildTranscript()
        setStatus(statusSummary())
        revalidate()
        repaint()
    }

    private fun rebuildEmptyState() {
        center.remove(emptyState)
        emptyState = buildEmptyState()
        center.add(emptyState, CARD_EMPTY)
    }

    /**
     * Append text to the composer without disturbing what the user already typed.
     * Used by the "Send … to Pi GUI" actions to drop in `@path` mentions.
     */
    fun appendToInput(text: String) {
        if (text.isBlank()) return
        val existing = input.text
        val needsSpace = existing.isNotEmpty() &&
            !existing.last().isWhitespace()
        input.text = buildString {
            append(existing)
            if (needsSpace) append(' ')
            append(text)
            append(' ')
        }
        input.caretPosition = input.text.length
    }

    fun currentSession(): SessionInfo? = session

    @org.jetbrains.annotations.TestOnly
    fun composerText(): String = input.text

    @org.jetbrains.annotations.TestOnly
    fun transcriptChildCountForTest(): Int = transcript.componentCount

    @org.jetbrains.annotations.TestOnly
    fun inputForTest(): JBTextArea = input

    @org.jetbrains.annotations.TestOnly
    fun attachmentsForTest(): List<Attachment> = attachments.toList()

    @org.jetbrains.annotations.TestOnly
    fun addAttachmentForTest(attachment: Attachment) = addAttachment(attachment)

    @org.jetbrains.annotations.TestOnly
    fun clearAttachmentsForTest() = clearAttachments()

    @org.jetbrains.annotations.TestOnly
    fun commandPopupForTest(): CommandPopup = commandPopup

    @org.jetbrains.annotations.TestOnly
    fun commandCompletionForTest(): CommandCompletion = lastCompletion

    @org.jetbrains.annotations.TestOnly
    fun commandRegistryForTest(): CommandRegistry = commandRegistry

    @org.jetbrains.annotations.TestOnly
    fun insertCommandForTest(command: PiCommand) = insertCommand(command)

    @org.jetbrains.annotations.TestOnly
    fun runBuiltinForTest(text: String): Boolean = runBuiltinCommand(text)

    @org.jetbrains.annotations.TestOnly
    fun messagesForTest(): List<PiMessage> = messages.toList()

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
         * Join the typed text with its `@path` mentions.
         *
         * pi only recognises a command when the message *starts* with `/`, so a command has to
         * stay in front and the mentions become its arguments; anywhere else the leading `@path`
         * would demote the command to ordinary prose.
         */
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
        rpc?.stop()
        rpc = null
    }
}
