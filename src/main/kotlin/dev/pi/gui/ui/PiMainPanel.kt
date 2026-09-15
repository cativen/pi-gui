package dev.pi.gui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.OnePixelSplitter
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.settings.PiSettingsDialog
import dev.pi.gui.ui.settings.WebSettingsSurface
import dev.pi.gui.web.PiWebView
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

/** Root content of the Pi GUI tool window: session sidebar plus the conversation view. */
class PiMainPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val chat = ChatPanel(project)
    private val sessions = SessionListPanel(project)
    private val splitter = OnePixelSplitter(false, 0.3f)
    private val contentCards = CardLayout()
    private val content = JPanel(contentCards)
    private var sidebarVisible = true

    /**
     * True when the conversation page draws the toolbar and the session list itself.
     *
     * A Swing list beside a Chromium transcript never matched, whichever theme was picked, so in
     * web mode the chrome moves into the same document and this panel keeps only the plumbing:
     * [sessions] still reads the files and raises the IDE's rename and delete dialogs, off-screen.
     */
    private val shellInPage = chat.hostsShell()
    private var toolbarComponent: JPanel? = null
    private var settingsSurface: WebSettingsSurface? = null
    private var showingSettings = false

    /** Rebuilds the chrome after a settings change — the toolbar text is language-dependent. */
    private val settingsListener: () -> Unit = {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!shellInPage) {
                rebuildToolbar()
                toolbarComponent?.isVisible = !showingSettings
            }
            sessions.applySettings()
            chat.applySettings()
            revalidate()
            repaint()
        }
    }

    init {
        Disposer.register(this, chat)

        sessions.onSessionSelected = { info -> chat.loadSession(info) }
        sessions.onSessionRenamed = { info -> chat.applySessionRename(info) }
        chat.onSessionChanged = { sessions.refresh() }
        // `/new` and `/resume` drive the same chrome the toolbar buttons do.
        chat.onNewSessionRequested = { newSession() }
        chat.onShowSessionsRequested = {
            showChat()
            if (!sidebarVisible) toggleSidebar()
            sessions.refresh()
            sessions.focusList()
        }
        chat.onShowSettingsRequested = { showSettings() }
        chat.onRefreshSessionsRequested = { sessions.refresh() }
        chat.onToggleSidebarRequested = { toggleSidebar() }
        chat.onSelectSessionRequested = { path ->
            sessions.selectByPath(path)?.let { chat.loadSession(it) }
        }
        chat.onRenameSessionRequested = { path -> sessions.renameByPath(path) }
        chat.onDeleteSessionRequested = { path -> sessions.deleteByPath(path) }
        sessions.onSessionsChanged = { items, selected ->
            chat.setSessions(
                items.map {
                    dev.pi.gui.ui.transcript.ChatSurface.Session(
                        path = it.filePath,
                        title = it.displayTitle(),
                        at = sessionDateFormat.format(java.util.Date(it.lastModified)),
                    )
                },
                selected,
            )
        }

        splitter.firstComponent = if (shellInPage) null else sessions
        splitter.secondComponent = chat
        if (shellInPage) splitter.proportion = 0f

        if (!shellInPage) rebuildToolbar()
        chat.setSidebarVisible(sidebarVisible)
        content.add(splitter, CHAT_CARD)
        add(content, BorderLayout.CENTER)

        PiSettings.getInstance().addChangeListener(settingsListener)
        sessions.refresh()

        // Every time the tool window is opened, the chat footer should already show the last
        // provider and its models: revive a dead agent on show, which repopulates the combos
        // and restores the remembered selection.
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                // The (String, ToolWindow) overload is scheduled for removal; with an open-ended
                // until-build this plugin would break on the IDE that drops it.
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == PiToolWindowFactory.TOOL_WINDOW_ID) chat.ensureAgentRunning()
                }
            },
        )
    }

    private fun rebuildToolbar() {
        toolbarComponent?.let { remove(it) }
        val toolbar = buildToolbar()
        toolbarComponent = toolbar
        add(toolbar, BorderLayout.NORTH)
    }

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup()

        group.add(object : AnAction(
            PiBundle.message("toolbar.newSession"),
            PiBundle.message("toolbar.newSession.desc"),
            AllIcons.General.Add,
        ) {
            override fun actionPerformed(e: AnActionEvent) = newSession()
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })

        group.add(object : AnAction(
            PiBundle.message("toolbar.refresh"),
            PiBundle.message("toolbar.refresh.desc"),
            AllIcons.Actions.Refresh,
        ) {
            override fun actionPerformed(e: AnActionEvent) = sessions.refresh()
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })

        group.addSeparator()

        group.add(object : AnAction(
            PiBundle.message("toolbar.toggleSessions"),
            PiBundle.message("toolbar.toggleSessions.desc"),
            AllIcons.Actions.ListFiles,
        ), Toggleable {
            override fun actionPerformed(e: AnActionEvent) = toggleSidebar()
            override fun update(e: AnActionEvent) {
                Toggleable.setSelected(e.presentation, sidebarVisible)
            }
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })

        group.add(object : AnAction(
            PiBundle.message("toolbar.settings"),
            PiBundle.message("toolbar.settings.desc"),
            AllIcons.General.GearPlain,
        ) {
            override fun actionPerformed(e: AnActionEvent) = showSettings()
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PiTheme.surfaceBg
            border = com.intellij.util.ui.JBUI.Borders.compound(
                com.intellij.util.ui.JBUI.Borders.customLineBottom(PiTheme.toolBorder),
                com.intellij.util.ui.JBUI.Borders.empty(5, 8, 5, 8),
            )
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    fun newSession() {
        showChat()
        chat.startNewSession()
        sessions.refresh()
    }

    private fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
        if (shellInPage) {
            chat.setSidebarVisible(sidebarVisible)
            return
        }
        splitter.firstComponent = if (sidebarVisible) sessions else null
        splitter.proportion = if (sidebarVisible) 0.3f else 0f
        revalidate()
        repaint()
    }

    fun focusInput() = chat.focusInput()

    /** Insert text (typically `@path` mentions) into the composer. */
    fun appendToInput(text: String) {
        showChat()
        chat.appendToInput(text)
    }

    private fun showSettings() {
        if (!PiWebView.isAvailable()) {
            PiSettingsDialog(project).show()
            return
        }
        val surface = settingsSurface ?: WebSettingsSurface(
            project = project,
            embedded = true,
            onClose = ::showChat,
        ).also {
            settingsSurface = it
            Disposer.register(this, it)
            content.add(it.component, SETTINGS_CARD)
        }
        surface.activate()
        showingSettings = true
        toolbarComponent?.isVisible = false
        contentCards.show(content, SETTINGS_CARD)
        revalidate()
        repaint()
    }

    private fun showChat() {
        if (!showingSettings) return
        showingSettings = false
        toolbarComponent?.isVisible = true
        contentCards.show(content, CHAT_CARD)
        chat.focusInput()
        revalidate()
        repaint()
    }

    override fun dispose() {
        PiSettings.getInstance().removeChangeListener(settingsListener)
    }

    private companion object {
        /** Matches the format the Swing session rows have always used. */
        val sessionDateFormat = java.text.SimpleDateFormat("MMM d, HH:mm")

        const val CHAT_CARD = "chat"
        const val SETTINGS_CARD = "settings"
    }
}
