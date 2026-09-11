package dev.pi.gui.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
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
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/** Sidebar listing the pi sessions recorded for this project, newest first. */
class SessionListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<SessionInfo>()
    private val list = JBList(model)

    var onSessionSelected: ((SessionInfo) -> Unit)? = null

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
        })

        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "pi.deleteSession")
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "pi.deleteSession")
        list.actionMap.put("pi.deleteSession", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = deleteSelected()
        })

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
        else Messages.showErrorDialog(project, "Could not delete the session file.", "Pi GUI")
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
            border = JBUI.Borders.empty(4, 6)
            append(value.displayTitle(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(
                "  ${format.format(Date(value.lastModified))}",
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
        }
    }
}
