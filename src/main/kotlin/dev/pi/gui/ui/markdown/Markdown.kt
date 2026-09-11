package dev.pi.gui.ui.markdown

/**
 * A deliberately small Markdown implementation, scoped to what chat transcripts actually contain.
 *
 * Fenced code is split out into [Segment.Code] so it can be rendered by a real IDE editor with
 * syntax highlighting; everything else is converted to the HTML subset Swing's `HTMLEditorKit`
 * understands (headings, lists, tables, blockquotes, inline styling).
 */
object Markdown {

    sealed class Segment {
        data class Prose(val markdown: String) : Segment()
        data class Code(val language: String?, val code: String) : Segment()
    }

    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*)\\s*$")

    /** Split text into prose runs and fenced code blocks, preserving order. */
    fun split(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val lines = text.split("\n")
        val prose = StringBuilder()
        var i = 0

        fun flushProse() {
            val content = prose.toString().trim('\n')
            if (content.isNotBlank()) segments.add(Segment.Prose(content))
            prose.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            val match = FENCE.find(line)
            if (match != null) {
                val marker = match.groupValues[1]
                val language = match.groupValues[2].takeIf { it.isNotBlank() }
                val body = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val candidate = lines[i]
                    // A closing fence uses the same character and is at least as long.
                    val closing = candidate.trimStart()
                    if (closing.startsWith(marker[0].toString().repeat(marker.length)) &&
                        closing.trimEnd().all { it == marker[0] }
                    ) {
                        closed = true
                        i++
                        break
                    }
                    body.append(candidate).append('\n')
                    i++
                }
                flushProse()
                segments.add(Segment.Code(language, body.toString().trimEnd('\n')))
                if (!closed) break
                continue
            }
            prose.append(line).append('\n')
            i++
        }
        flushProse()
        return segments
    }

    /** Convert a prose run to the HTML subset Swing renders. */
    fun proseToHtml(markdown: String): String {
        val lines = markdown.split("\n")
        val out = StringBuilder()
        var i = 0
        var inParagraph = false

        fun closeParagraph() {
            if (inParagraph) { out.append("</p>"); inParagraph = false }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trim()

            // Blank line ends the current paragraph.
            if (trimmed.isEmpty()) { closeParagraph(); i++; continue }

            // Horizontal rule
            if (trimmed.matches(Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$"))) {
                closeParagraph()
                out.append("<hr>")
                i++
                continue
            }

            // ATX heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                closeParagraph()
                val level = heading.groupValues[1].length
                // Swing renders h1/h2 very large; compress the scale for chat.
                val tag = "h" + (level + 2).coerceAtMost(6)
                out.append("<$tag>").append(inline(heading.groupValues[2])).append("</$tag>")
                i++
                continue
            }

            // Table: a header row followed by a delimiter row.
            if (trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$"))
            ) {
                closeParagraph()
                val header = splitRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|') && lines[i].isNotBlank()) {
                    rows.add(splitRow(lines[i].trim()))
                    i++
                }
                out.append(renderTable(header, rows))
                continue
            }

            // Blockquote
            if (trimmed.startsWith(">")) {
                closeParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i].trim().removePrefix(">").trim()).append("\n")
                    i++
                }
                out.append("<blockquote>").append(inline(quote.toString().trim()).replace("\n", "<br>")).append("</blockquote>")
                continue
            }

            // Lists (bullet or ordered), including simple nesting by indent.
            val listItem = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").find(raw)
            if (listItem != null) {
                closeParagraph()
                i = renderList(lines, i, out)
                continue
            }

            // Plain paragraph text
            if (!inParagraph) { out.append("<p>"); inParagraph = true } else out.append("<br>")
            out.append(inline(line))
            i++
        }
        closeParagraph()
        return out.toString()
    }

    /** Render a run of list items starting at [start]; returns the index just past the list. */
    private fun renderList(lines: List<String>, start: Int, out: StringBuilder): Int {
        val itemPattern = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
        val first = itemPattern.find(lines[start]) ?: return start + 1
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2].first().isDigit()

        out.append(if (ordered) "<ol>" else "<ul>")
        var i = start
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) {
                // A blank line only ends the list if the next line is not another item.
                val next = lines.getOrNull(i + 1)
                if (next == null || itemPattern.find(next) == null) break
                i++
                continue
            }
            val m = itemPattern.find(raw)
            if (m == null) {
                // Continuation line of the previous item.
                if (raw.trimStart().isNotEmpty() && raw.takeWhile { it == ' ' }.length > baseIndent) {
                    out.append(' ').append(inline(raw.trim()))
                    i++
                    continue
                }
                break
            }
            val indent = m.groupValues[1].length
            if (indent < baseIndent) break
            if (indent > baseIndent) {
                i = renderList(lines, i, out)
                continue
            }
            out.append("<li>").append(inline(m.groupValues[3])).append("</li>")
            i++
        }
        out.append(if (ordered) "</ol>" else "</ul>")
        return i
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun renderTable(header: List<String>, rows: List<List<String>>): String = buildString {
        append("<table cellspacing='0' cellpadding='4' border='1'>")
        append("<tr>")
        header.forEach { append("<th>").append(inline(it)).append("</th>") }
        append("</tr>")
        rows.forEach { row ->
            append("<tr>")
            for (c in 0 until header.size) {
                append("<td>").append(inline(row.getOrElse(c) { "" })).append("</td>")
            }
            append("</tr>")
        }
        append("</table>")
    }

    /**
     * Inline formatting. Code spans are extracted first and restored last so their contents are
     * never re-interpreted as emphasis or links.
     */
    fun inline(text: String): String {
        val codeSpans = mutableListOf<String>()
        // Placeholder uses control chars that cannot appear in the source text.
        var work = Regex("`([^`]+)`").replace(text) { m ->
            codeSpans.add(m.groupValues[1])
            "\u0000${codeSpans.size - 1}\u0001"
        }

        work = escapeHtml(work)

        // Links before emphasis: link text may contain underscores.
        work = Regex("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)").replace(work) { m ->
            val label = m.groupValues[1]
            val href = m.groupValues[2]
            if (isSafeUrl(href)) "<a href=\"$href\">$label</a>" else label
        }
        // Bare URLs that were not already turned into anchors.
        work = Regex("(?<!\")(?<!href=\")\\b(https?://[^\\s<>\"]+)").replace(work) { m ->
            "<a href=\"${m.groupValues[1]}\">${m.groupValues[1]}</a>"
        }

        work = Regex("\\*\\*\\*(.+?)\\*\\*\\*").replace(work, "<b><i>$1</i></b>")
        work = Regex("\\*\\*(.+?)\\*\\*").replace(work, "<b>$1</b>")
        work = Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])").replace(work, "<i>$1</i>")
        work = Regex("(?<![\\w_])__(.+?)__(?![\\w_])").replace(work, "<b>$1</b>")
        work = Regex("(?<![\\w_])_([^_\\n]+)_(?![\\w_])").replace(work, "<i>$1</i>")
        work = Regex("~~(.+?)~~").replace(work, "<s>$1</s>")

        // Restore code spans, escaping their contents.
        work = Regex("\u0000(\\d+)\u0001").replace(work) { m ->
            val index = m.groupValues[1].toIntOrNull() ?: return@replace ""
            val code = codeSpans.getOrNull(index) ?: return@replace ""
            "<code>${escapeHtml(code)}</code>"
        }
        return work
    }

    private fun isSafeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:") || lower.startsWith("file:") || lower.startsWith("#")
    }

    fun escapeHtml(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
