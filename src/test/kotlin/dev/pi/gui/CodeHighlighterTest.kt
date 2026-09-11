package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.ui.markdown.CodeHighlighter

class CodeHighlighterTest : BasePlatformTestCase() {

    private fun colorSpanCount(html: String) = Regex("color:#").findAll(html).count()

    /**
     * Strips markup so assertions can talk about the rendered text. The lexer emits one span per
     * token, so a phrase is never a contiguous substring of the raw HTML.
     */
    private fun renderedText(html: String): String = html
        .replace(Regex("<br>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    /**
     * XML and SQL are always available in the test fixture, so they prove the lexer path really
     * produces per-token colours (other languages depend on which plugins the IDE has loaded).
     */
    fun testTokensAreColouredForAvailableLanguages() {
        val xml = CodeHighlighter.toHtml(project, "xml", """<root attr="v">text</root>""")
        assertTrue("expected several coloured tokens, got: $xml", colorSpanCount(xml) > 2)

        val sql = CodeHighlighter.toHtml(project, "sql", "SELECT * FROM t WHERE id = 1")
        assertTrue("expected several coloured tokens, got: $sql", colorSpanCount(sql) > 2)
    }

    fun testUnknownLanguageStillRendersCode() {
        val html = CodeHighlighter.toHtml(project, "not-a-real-language", "some code")
        assertEquals("some code", renderedText(html))
    }

    fun testNullLanguageIsHandled() {
        val html = CodeHighlighter.toHtml(project, null, "plain output")
        assertEquals("plain output", renderedText(html))
    }

    fun testHtmlInCodeIsEscaped() {
        val html = CodeHighlighter.toHtml(project, null, "<script>alert(1)</script>")
        assertTrue(html, html.contains("&lt;script&gt;"))
        assertFalse(html, html.contains("<script>"))
    }

    fun testNewlinesBecomeLineBreaks() {
        val html = CodeHighlighter.toHtml(project, null, "line1\nline2")
        assertEquals(1, Regex("<br>").findAll(html).count())
    }

    /** Indentation must survive, otherwise every snippet renders flush left. */
    fun testLeadingIndentationIsPreserved() {
        val html = CodeHighlighter.toHtml(project, null, "a\n    indented")
        assertTrue("leading spaces must be non-breaking: $html", html.contains("&nbsp;&nbsp;&nbsp;&nbsp;"))
        assertEquals("a\n    indented", renderedText(html))
    }

    /** Spaces inside a line stay breakable so long lines wrap instead of overflowing. */
    fun testInteriorSpacesRemainBreakable() {
        val html = CodeHighlighter.toHtml(project, null, "one two three")
        assertEquals("one two three", renderedText(html))
        // Only leading indentation may be made non-breaking.
        assertFalse("interior spaces must not be &nbsp;", html.contains("one&nbsp;two"))
    }

    fun testTabsBecomeSpaces() {
        val html = CodeHighlighter.toHtml(project, null, "\tx")
        assertTrue(html, html.contains("&nbsp;"))
    }

    fun testEmptyCodeDoesNotThrow() {
        assertNotNull(CodeHighlighter.toHtml(project, "xml", ""))
    }

    fun testFileTypeMappingResolvesAliases() {
        assertEquals("XML", CodeHighlighter.fileTypeFor("xml").name)
        // Unknown names fall back to plain text rather than throwing.
        assertEquals("PLAIN_TEXT", CodeHighlighter.fileTypeFor("nonsense-lang").name)
        assertEquals("PLAIN_TEXT", CodeHighlighter.fileTypeFor(null).name)
    }
}
