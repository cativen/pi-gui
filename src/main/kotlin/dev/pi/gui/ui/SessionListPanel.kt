package dev.pi.gui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.SessionInfo
import dev.pi.gui.session.SessionStore
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/** Sidebar listing the pi sessions recorded for this project, newest first. */
class SessionListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<SessionInfo>()
    private val list = JBList(model)

    var onSessionSelected: ((SessionInfo) -> Unit)? = null

    /** Fires after the user renamed a session, so a loaded chat can update its title. */
    var onSessionRenamed: ((SessionInfo) -> Unit)? = null

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SessionCellRenderer()
        list.emptyText.text = PiBundle.message("sessions.empty")

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1) {
                    selectedSession()?.let { onSessionSelected?.invoke(it) }
                }
            }

            // Right-click should act on the row under the cursor, not the old selection.
            override fun mousePressed(e: MouseEvent) = selectForPopup(e)
            override fun mouseReleased(e: MouseEvent) = selectForPopup(e)
        })

        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "pi.deleteSession")
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "pi.deleteSession")
        list.actionMap.put("pi.deleteSession", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = deleteSelected()
        })

        // F2 renames, as it does everywhere else in the IDE.
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "pi.renameSession")
        list.actionMap.put("pi.renameSession", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = renameSelected()
        })

        installPopupMenu()

        list.background = PiTheme.surfaceBg
        list.border = JBUI.Borders.empty(4, 0)

        val scroll = JBScrollPane(list).apply {
            border = JBUI.Borders.customLineRight(PiTheme.toolBorder)
            viewport.background = PiTheme.surfaceBg
            background = PiTheme.surfaceBg
        }
        isOpaque = true
        background = PiTheme.surfaceBg
        add(scroll, BorderLayout.CENTER)
    }

    fun selectedSession(): SessionInfo? = list.selectedValue

    /** Put the keyboard on the session list, so `/resume` lands where the user can pick one. */
    fun focusList() {
        if (list.model.size > 0 && list.selectedIndex < 0) list.selectedIndex = 0
        list.requestFocusInWindow()
    }

    /** Re-applies theme and language after the settings dialog is accepted. */
    fun applySettings() {
        background = PiTheme.surfaceBg
        list.background = PiTheme.surfaceBg
        list.emptyText.text = PiBundle.message("sessions.empty")
        (list.parent as? javax.swing.JViewport)?.background = PiTheme.surfaceBg
        list.font = PiTheme.uiFont()
        revalidate()
        repaint()
    }

    fun refresh(selectPath: String? = null) {
        val basePath = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = SessionStore.listSessionsForProject(basePath)
            ApplicationManager.getApplication().invokeLater {
                val previous = selectPath ?: list.selectedValue?.filePath
                model.clear()
                sessions.forEach { model.addElement(it) }
                previous?.let { path ->
                    for (i in 0 until model.size()) {
                        if (model.getElementAt(i).filePath == path) {
                            list.selectedIndex = i
                            break
                        }
                    }
                }
            }
        }
    }

    private fun deleteSelected() {
        val session = selectedSession() ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            PiBundle.message("sessions.delete.confirm", session.displayTitle()),
            PiBundle.message("sessions.delete.title"),
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return
        if (SessionStore.deleteSession(File(session.filePath))) refresh()
        else Messages.showErrorDialog(
            project,
            PiBundle.message("sessions.delete.failed"),
            PiBundle.message("sessions.delete.title"),
        )
    }

    /** Right-click menu: rename and delete, the two things one wants on a session row. */
    private fun installPopupMenu() {
        list.componentPopupMenu = JPopupMenu().apply {
            add(menuItem(PiBundle.message("sessions.rename"), AllIcons.Actions.Edit) { renameSelected() })
            add(menuItem(PiBundle.message("sessions.delete"), AllIcons.Actions.GC) { deleteSelected() })
        }
    }

    private fun menuItem(text: String, icon: javax.swing.Icon, action: () -> Unit): JMenuItem =
        JMenuItem(text, icon).apply { addActionListener { action() } }

    private fun selectForPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = list.locationToIndex(e.point)
        if (index >= 0) list.selectedIndex = index
    }

    /**
     * Rename the selected session. The name is appended to the session file as a
     * `session_info` entry — the same way pi persists names — so it survives restarts and
     * shows up in pi's own TUI too. Works without a running agent, even for old sessions.
     */
    private fun renameSelected() {
        val session = selectedSession() ?: return
        val validator = object : InputValidator {
            override fun checkInput(inputString: String): Boolean = inputString.isNotBlank()
            override fun canClose(inputString: String): Boolean = inputString.isNotBlank()
        }
        val chosen = Messages.showInputDialog(
            project,
            PiBundle.message("sessions.rename.prompt"),
            PiBundle.message("sessions.rename.title"),
            Messages.getQuestionIcon(),
            session.displayTitle(),
            validator,
        )?.trim() ?: return // null = cancelled
        if (chosen.isBlank() || chosen == session.name) return

        if (!SessionStore.renameSession(File(session.filePath), chosen)) {
            Messages.showErrorDialog(
                project,
                PiBundle.message("sessions.rename.failed"),
                PiBundle.message("sessions.rename.title"),
            )
            return
        }

        // Update the row in place so the selection and scroll position survive.
        val updated = session.copy(name = chosen)
        for (i in 0 until model.size()) {
            if (model.getElementAt(i).filePath == updated.filePath) {
                model.setElementAt(updated, i)
                break
            }
        }
        onSessionRenamed?.invoke(updated)
    }

    private class SessionCellRenderer : ColoredListCellRenderer<SessionInfo>() {
        private val format = SimpleDateFormat("MMM d, HH:mm")

        override fun customizeCellRenderer(
            list: JList<out SessionInfo>,
            value: SessionInfo?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            border = JBUI.Borders.empty(6, 10)
            append(value.displayTitle(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(
                "  ${format.format(Date(value.lastModified))}",
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
        }
    }
}
