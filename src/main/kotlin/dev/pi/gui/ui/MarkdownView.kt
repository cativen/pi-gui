package dev.pi.gui.ui

import com.intellij.openapi.project.Project
import dev.pi.gui.ui.components.CodeBlock
import dev.pi.gui.ui.components.HtmlBlock
import dev.pi.gui.ui.components.StackPanel
import dev.pi.gui.ui.markdown.Markdown

/**
 * Renders Markdown as a stack of native components: prose through [HtmlBlock], fenced code
 * through [CodeBlock] so it picks up the IDE's syntax highlighting.
 */
class MarkdownView(
    private val project: Project?,
    markdown: String = "",
) : StackPanel(2) {

    private var currentMarkdown: String? = null

    init {
        setMarkdown(markdown)
    }

    fun setMarkdown(markdown: String) {
        if (markdown == currentMarkdown) return
        currentMarkdown = markdown
        removeAll()
        Markdown.split(markdown).forEach { segment ->
            when (segment) {
                is Markdown.Segment.Prose ->
                    add(HtmlBlock(Markdown.proseToHtml(segment.markdown)))
                is Markdown.Segment.Code ->
                    add(CodeBlock(project, segment.language, segment.code))
            }
        }
        revalidate()
        repaint()
    }
}
