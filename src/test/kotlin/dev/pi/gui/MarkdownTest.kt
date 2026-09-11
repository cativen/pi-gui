package dev.pi.gui

import dev.pi.gui.ui.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `splits fenced code out of prose`() {
        val input = """
            Here is code:

            ```kotlin
            fun main() {}
            ```

            Done.
        """.trimIndent()

        val segments = Markdown.split(input)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is Markdown.Segment.Prose)
        val code = segments[1] as Markdown.Segment.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {}", code.code)
        assertTrue(segments[2] is Markdown.Segment.Prose)
    }

    @Test
    fun `unterminated fence still yields a code segment`() {
        val segments = Markdown.split("text\n```js\nconst a = 1;")
        assertEquals(2, segments.size)
        assertEquals("const a = 1;", (segments[1] as Markdown.Segment.Code).code)
    }

    @Test
    fun `code fence content is not treated as markdown`() {
        val segments = Markdown.split("```\n**not bold** and `x`\n```")
        val code = segments.single() as Markdown.Segment.Code
        assertEquals("**not bold** and `x`", code.code)
    }

    @Test
    fun `inline code is preserved and escaped`() {
        val html = Markdown.inline("use `a < b` here")
        assertTrue(html, html.contains("<code>a &lt; b</code>"))
    }

    /** Regression: the code-span placeholder must not collide with ordinary text. */
    @Test
    fun `digits after spaces survive inline rendering`() {
        val html = Markdown.inline("upgrade to version 2 and 3 now")
        assertEquals("upgrade to version 2 and 3 now", html)
    }

    @Test
    fun `mixed code spans and digits round-trip`() {
        val html = Markdown.inline("run `npm i` then step 2 and `go build`")
        assertTrue(html, html.contains("<code>npm i</code>"))
        assertTrue(html, html.contains("<code>go build</code>"))
        assertTrue(html, html.contains("then step 2 and"))
    }

    @Test
    fun `bold and italic render`() {
        assertTrue(Markdown.inline("**bold**").contains("<b>bold</b>"))
        assertTrue(Markdown.inline("*it*").contains("<i>it</i>"))
        assertTrue(Markdown.inline("~~gone~~").contains("<s>gone</s>"))
    }

    @Test
    fun `underscores inside identifiers are not italics`() {
        val html = Markdown.inline("call my_var_name here")
        assertEquals("call my_var_name here", html)
    }

    @Test
    fun `html in source is escaped`() {
        val html = Markdown.inline("<script>alert(1)</script>")
        assertTrue(html, html.contains("&lt;script&gt;"))
        assertTrue(html, !html.contains("<script>"))
    }

    @Test
    fun `links become anchors and javascript urls are stripped`() {
        assertTrue(Markdown.inline("[x](https://a.test)").contains("<a href=\"https://a.test\">x</a>"))
        val unsafe = Markdown.inline("[x](javascript:alert(1))")
        assertTrue(unsafe, !unsafe.contains("<a "))
    }

    @Test
    fun `lists render as html lists`() {
        val html = Markdown.proseToHtml("- one\n- two")
        assertTrue(html, html.contains("<ul>"))
        assertEquals(2, Regex("<li>").findAll(html).count())
    }

    @Test
    fun `ordered lists render as ol`() {
        val html = Markdown.proseToHtml("1. one\n2. two")
        assertTrue(html, html.contains("<ol>"))
    }

    @Test
    fun `headings are compressed to smaller tags`() {
        val html = Markdown.proseToHtml("# Title")
        assertTrue(html, html.contains("<h3>Title</h3>"))
    }

    @Test
    fun `tables render`() {
        val html = Markdown.proseToHtml("| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertTrue(html, html.contains("<table"))
        assertTrue(html, html.contains("<th>a</th>"))
        assertTrue(html, html.contains("<td>1</td>"))
    }

    @Test
    fun `plain paragraphs survive`() {
        val html = Markdown.proseToHtml("hello world")
        assertTrue(html, html.contains("hello world"))
    }
}
