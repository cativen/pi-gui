package dev.pi.gui.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.pi.gui.ui.PiToolWindowFactory

/**
 * Sends the path of whatever the user acted on into the Pi GUI composer as an `@mention`.
 *
 * Handles the four cases the context menus can produce: a selection inside the editor (path plus
 * line range), a single file, a directory, and a multi-selection in the project view.
 */
class SendPathToPiAction : AnAction(), DumbAware {

    private data class Target(val label: String, val mentions: String)

    /**
     * BGT, and it has to be.
     *
     * On IntelliJ 2026.1 this entry simply never appeared in the project view's right-click menu —
     * no error, no log line, just absent — while the identical registration worked on 2024.3. It
     * was the only third-party action in that menu still updating on the EDT. Nothing here needs
     * the EDT: [update] reads the data context and nothing else, and the tool window is touched
     * from [actionPerformed], which the platform always runs on the EDT.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = e.project?.let { resolveTarget(e, it) }
        e.presentation.isEnabledAndVisible = target != null
        target?.let { e.presentation.text = it.label }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = resolveTarget(e, project) ?: return

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(PiToolWindowFactory.TOOL_WINDOW_ID) ?: return

        // activate() runs the callback after the content has been created, so this also works
        // the very first time the tool window is opened.
        toolWindow.activate {
            PiToolWindowFactory.findPanel(project)?.let { panel ->
                panel.appendToInput(target.mentions)
                panel.focusInput()
            }
        }
    }

    private fun resolveTarget(e: AnActionEvent, project: Project): Target? {
        val base = project.basePath
        val editor = e.getData(CommonDataKeys.EDITOR)
        val contextFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editorFile = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }

        // Only treat a selection as the target when the menu was opened on that same editor —
        // the tab popup also supplies an EDITOR, but for a possibly different file.
        if (editor != null && editorFile != null &&
            editor.selectionModel.hasSelection() &&
            (contextFile == null || contextFile == editorFile)
        ) {
            val document = editor.document
            val startLine = document.getLineNumber(editor.selectionModel.selectionStart) + 1
            val endLine = document.getLineNumber(editor.selectionModel.selectionEnd) + 1
            val range = if (startLine == endLine) ":$startLine" else ":$startLine-$endLine"
            return Target(
                "Send Selection Path to Pi GUI",
                "@${mentionPath(editorFile, base)}$range",
            )
        }

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(contextFile ?: editorFile)
        if (files.isEmpty()) return null

        val label = when {
            files.size > 1 -> "Send ${files.size} Paths to Pi GUI"
            files.first().isDirectory -> "Send Folder Path to Pi GUI"
            else -> "Send File Path to Pi GUI"
        }
        return Target(label, files.joinToString(" ") { "@" + mentionPath(it, base) })
    }

    /**
     * Paths are made relative to the project root when possible: the agent runs with the project
     * as its working directory, so relative mentions are shorter and travel better.
     */
    private fun mentionPath(file: VirtualFile, basePath: String?): String {
        val path = file.path
        val relative =
            if (basePath != null && path.startsWith("$basePath/")) path.removePrefix("$basePath/")
            else path
        return if (file.isDirectory && !relative.endsWith("/")) "$relative/" else relative
    }
}
