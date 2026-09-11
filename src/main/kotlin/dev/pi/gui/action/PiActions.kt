package dev.pi.gui.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import dev.pi.gui.ui.PiToolWindowFactory

/** Opens the tool window and puts the caret in the composer. */
class OpenPiToolWindowAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(PiToolWindowFactory.TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            PiToolWindowFactory.findPanel(project)?.focusInput()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** Opens the tool window on a brand-new conversation. */
class NewSessionAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(PiToolWindowFactory.TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            PiToolWindowFactory.findPanel(project)?.let {
                it.newSession()
                it.focusInput()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
