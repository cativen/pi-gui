package dev.pi.gui.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pi.gui.edits.EditedFile
import dev.pi.gui.edits.EditedFiles
import dev.pi.gui.git.DiffStat
import dev.pi.gui.git.GitDiffService
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.model.PiMessage
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Strip above the composer listing the files this conversation changed.
 *
 * Clicking a file opens the IDE's own diff viewer (HEAD vs working tree) rather than rendering a
 * patch here — the real viewer brings syntax highlighting, navigation and editing for free.
 */
class EditsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val header = JPanel(BorderLayout())
    private val titleLabel = JBLabel()
    private val arrow = JBLabel(AllIcons.General.ArrowRight)
    private val fileList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 18, 6, 10)
        isVisible = false
    }

    private var expanded = false
    private var files: List<EditedFile> = emptyList()
    private val stats = HashMap<String, DiffStat?>()

    init {
        isOpaque = true
        background = PiTheme.surfaceBg
        border = JBUI.Borders.customLineTop(PiTheme.toolBorder)
        isVisible = false

        header.isOpaque = false
        header.border = JBUI.Borders.empty(5, 10)
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
        left.add(arrow)
        titleLabel.icon = AllIcons.Actions.Edit
        titleLabel.foreground = PiTheme.mutedFg()
        left.add(titleLabel)
        header.add(left, BorderLayout.WEST)

        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = setExpanded(!expanded)
        }
        header.addMouseListener(toggle)
        titleLabel.addMouseListener(toggle)
        arrow.addMouseListener(toggle)

        add(header, BorderLayout.NORTH)
        add(fileList, BorderLayout.CENTER)
    }

    /** Recomputes the edited-file list from the transcript. Diff stats load in the background. */
    fun update(messages: List<PiMessage>) {
        val collected = EditedFiles.collect(messages, project.basePath)
        if (collected == files) return
        files = collected

        titleLabel.text = PiBundle.message("edits.title", collected.size)
        isVisible = collected.isNotEmpty()
        if (collected.isEmpty()) {
            setExpanded(false)
        } else {
            rebuildRows()
            loadStats()
        }
        revalidate()
        repaint()
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        arrow.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        fileList.isVisible = value && files.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun rebuildRows() {
        fileList.removeAll()
        files.forEach { fileList.add(rowFor(it)) }
    }

    private fun rowFor(edited: EditedFile): JPanel {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = edited.absolutePath
        }

        val name = JBLabel(edited.displayPath).apply {
            icon = FileTypeManager.getInstance().getFileTypeByFileName(
                File(edited.absolutePath).name
            ).icon
            foreground = PiTheme.textFg()
            font = font.deriveFont(font.size2D - 1f)
        }
        row.add(name, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        val statLabel = JBLabel(stats[edited.absolutePath]?.label().orEmpty()).apply {
            foreground = PiTheme.mutedFg()
            font = font.deriveFont(font.size2D - 1f)
            putClientProperty(STAT_KEY, edited.absolutePath)
        }
        right.add(statLabel)
        if (edited.editCount > 1) {
            right.add(
                JBLabel("×${edited.editCount}").apply {
                    foreground = PiTheme.noticeFg
                    font = font.deriveFont(font.size2D - 2f)
                }
            )
        }
        row.add(right, BorderLayout.EAST)

        val open = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = openDiff(edited)
        }
        row.addMouseListener(open)
        name.addMouseListener(open)
        return row
    }

    /** git can be slow on large repositories, so stats never run on the EDT. */
    private fun loadStats() {
        val snapshot = files
        ApplicationManager.getApplication().executeOnPooledThread {
            val root = GitDiffService.repoRootFor(project.basePath)
            val computed = snapshot.associate { file ->
                file.absolutePath to root?.let { GitDiffService.statFor(file.absolutePath, it) }
            }
            ApplicationManager.getApplication().invokeLater {
                if (files != snapshot) return@invokeLater
                stats.putAll(computed)
                applyStatLabels(fileList)
                repaint()
            }
        }
    }

    private fun applyStatLabels(container: java.awt.Container) {
        container.components.forEach { component ->
            if (component is JBLabel) {
                val path = component.getClientProperty(STAT_KEY) as? String
                if (path != null) component.text = stats[path]?.label().orEmpty()
            }
            if (component is java.awt.Container) applyStatLabels(component)
        }
    }

    /**
     * Shows HEAD vs the working tree. Falls back to just opening the file when the project is not
     * a git work tree, or when the file is new and has no HEAD side worth diffing.
     */
    private fun openDiff(edited: EditedFile) {
        val file = File(edited.absolutePath)
        if (!file.isFile) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val root = GitDiffService.repoRootFor(project.basePath)
            val head = root?.let { GitDiffService.contentAtHead(edited.absolutePath, it) }
            val current = try { file.readText() } catch (e: Exception) { null }

            ApplicationManager.getApplication().invokeLater {
                if (current == null) return@invokeLater
                if (head == null) {
                    // Untracked or no repository: the diff would be meaningless, so just open it.
                    openInEditor(file)
                    return@invokeLater
                }
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
                val factory = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(
                    edited.displayPath,
                    factory.create(project, head, fileType),
                    factory.create(project, current, fileType),
                    PiBundle.message("edits.head"),
                    PiBundle.message("edits.working"),
                )
                DiffManager.getInstance().showDiff(project, request)
            }
        }
    }

    private fun openInEditor(file: File) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }

    private companion object {
        const val STAT_KEY = "pi.edits.statPath"
    }
}
