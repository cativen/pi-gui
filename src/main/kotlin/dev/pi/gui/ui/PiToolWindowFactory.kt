package dev.pi.gui.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class PiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PiMainPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    companion object {
        const val TOOL_WINDOW_ID = "Pi GUI"

        /** The panel backing the tool window, when it has been created. */
        fun findPanel(project: Project): PiMainPanel? {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID) ?: return null
            return toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? PiMainPanel }
        }
    }
}
