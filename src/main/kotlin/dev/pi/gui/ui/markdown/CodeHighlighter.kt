package dev.pi.gui.ui.markdown

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import dev.pi.gui.ui.PiTheme
import java.awt.Color
import java.awt.Font

/**
 * Renders a code snippet as syntax-coloured HTML using the IDE's own lexer and colour scheme.
 *
 * Going through the lexer rather than an `EditorTextField` keeps a long transcript cheap — a
 * conversation can easily contain dozens of snippets, and each editor instance is expensive —
 * and it renders correctly even before the component hierarchy is realized.
 */
object CodeHighlighter {

    fun toHtml(project: Project?, language: String?, code: String): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val defaultFg = scheme.defaultForeground ?: PiTheme.textFg()

        val highlighter = try {
            SyntaxHighlighterFactory.getSyntaxHighlighter(fileTypeFor(language), project, null)
        } catch (e: Throwable) {
            null
        } ?: return plainHtml(code, defaultFg)

        return try {
            val lexer = highlighter.highlightingLexer
            lexer.start(code)
            val sb = StringBuilder()
            // One escaper for the whole snippet: indentation is a property of the line, not of the
            // token, and tokens routinely start mid-line.
            val escaper = CodeEscaper()
            var cursor = 0

            while (lexer.tokenType != null) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                if (start > cursor) sb.append(escaper.escape(code.substring(cursor, start)))
                if (end > start) {
                    val text = code.substring(start, end)
                    val attributes = highlighter.getTokenHighlights(lexer.tokenType!!)
                        .mapNotNull { scheme.getAttributes(it) }
                        .lastOrNull { it.foregroundColor != null }
                    sb.append(
                        span(
                            escaper.escape(text),
                            attributes?.foregroundColor,
                            attributes?.fontType ?: Font.PLAIN,
                        )
                    )
                }
                cursor = end
                lexer.advance()
            }
            if (cursor < code.length) sb.append(escaper.escape(code.substring(cursor)))
            wrap(sb.toString(), defaultFg)
        } catch (e: Throwable) {
            plainHtml(code, defaultFg)
        }
    }

    private fun plainHtml(code: String, fg: Color): String = wrap(CodeEscaper().escape(code), fg)

    private fun wrap(body: String, fg: Color): String {
        val mono = PiTheme.monoFont()
        return "<div style=\"font-family:'${mono.family}'; font-size:${mono.size}pt; " +
            "color:${PiTheme.toHex(fg)};\">$body</div>"
    }

    private fun span(text: String, color: Color?, fontType: Int): String {
        if (text.isEmpty()) return ""
        if (color == null && fontType == Font.PLAIN) return text
        val styles = StringBuilder()
        color?.let { styles.append("color:").append(PiTheme.toHex(it)).append(';') }
        if (fontType and Font.BOLD != 0) styles.append("font-weight:bold;")
        if (fontType and Font.ITALIC != 0) styles.append("font-style:italic;")
        return "<span style=\"$styles\">$text</span>"
    }

    /**
     * Escapes for HTML while preserving code shape.
     *
     * Newlines become `<br>`; spaces in a line's leading indentation become non-breaking so the
     * shape survives, while spaces after the first real character stay ordinary so long lines
     * still wrap instead of overflowing the panel. Line-start state is carried across calls
     * because the caller feeds this one lexer token at a time.
     */
    private class CodeEscaper {
        private var atLineStart = true

        fun escape(text: String): String {
            val sb = StringBuilder(text.length + 16)
            text.forEach { ch ->
                when (ch) {
                    '\n' -> { sb.append("<br>"); atLineStart = true }
                    '\r' -> Unit
                    ' ' -> sb.append(if (atLineStart) "&nbsp;" else " ")
                    '\t' -> sb.append("&nbsp;&nbsp;&nbsp;&nbsp;")
                    '&' -> { sb.append("&amp;"); atLineStart = false }
                    '<' -> { sb.append("&lt;"); atLineStart = false }
                    '>' -> { sb.append("&gt;"); atLineStart = false }
                    '"' -> { sb.append("&quot;"); atLineStart = false }
                    else -> { sb.append(ch); atLineStart = false }
                }
            }
            return sb.toString()
        }
    }

    fun fileTypeFor(language: String?): FileType {
        val key = language?.lowercase()?.trim()
        val ext = EXTENSIONS[key] ?: key
        if (ext.isNullOrBlank()) return PlainTextFileType.INSTANCE
        val type = FileTypeManager.getInstance().getFileTypeByExtension(ext)
        return if (type == UnknownFileType.INSTANCE) PlainTextFileType.INSTANCE else type
    }

    private val EXTENSIONS = mapOf(
        "kotlin" to "kt", "kt" to "kt",
        "java" to "java",
        "python" to "py", "py" to "py",
        "javascript" to "js", "js" to "js", "jsx" to "jsx",
        "typescript" to "ts", "ts" to "ts", "tsx" to "tsx",
        "bash" to "sh", "sh" to "sh", "shell" to "sh", "zsh" to "sh", "console" to "sh",
        "json" to "json",
        "yaml" to "yaml", "yml" to "yaml",
        "xml" to "xml", "html" to "html", "css" to "css", "scss" to "scss",
        "go" to "go", "golang" to "go",
        "rust" to "rs", "rs" to "rs",
        "sql" to "sql",
        "markdown" to "md", "md" to "md",
        "c" to "c", "cpp" to "cpp", "c++" to "cpp", "csharp" to "cs", "cs" to "cs",
        "ruby" to "rb", "rb" to "rb",
        "php" to "php",
        "swift" to "swift",
        "gradle" to "gradle", "groovy" to "groovy",
        "dockerfile" to "dockerfile",
        "diff" to "diff", "patch" to "diff",
        "properties" to "properties",
        "toml" to "toml",
    )
}
