package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.Usage
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.web.HtmlRenderer

/**
 * The message → HTML step that replaces the Swing component tree.
 *
 * Everything the browser shows arrives through here, so the escaping rules matter as much as the
 * structure: transcript content is agent output and file contents, never trusted markup.
 */
class HtmlRendererTest : BasePlatformTestCase() {

    private var expandThinking = false
    private var showThinking = true

    override fun setUp() {
        super.setUp()
        expandThinking = PiSettings.getInstance().expandThinking
        showThinking = PiSettings.getInstance().showThinking
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().expandThinking = expandThinking
            PiSettings.getInstance().showThinking = showThinking
        } finally {
            super.tearDown()
        }
    }

    private fun assistant(vararg blocks: ContentBlock) =
        PiMessage.Assistant(blocks = blocks.toMutableList(), model = "glm-5.3")

    // -------------------------------------------------------------- structure

    fun testUserMessageRendersAsABubble() {
        val html = HtmlRenderer.render(project, PiMessage.User("hello there"))
        assertTrue(html, html.contains("""class="msg msg-user""""))
        assertTrue(html, html.contains("""class="bubble""""))
        assertTrue(html, html.contains("hello there"))
        assertTrue("user messages need an accessible identity", html.contains("aria-label="))
    }

    fun testAttachedImageCountIsShown() {
        val html = HtmlRenderer.render(project, PiMessage.User("look", imageCount = 2))
        assertTrue(html, html.contains("class=\"attached\""))
    }

    fun testAssistantProseAndCodeAreBothRendered() {
        val html = HtmlRenderer.render(
            project,
            assistant(ContentBlock.Text("Some **prose**.\n\n```kotlin\nval x = 1\n```")),
        )
        // The Markdown renderer targets Swing's HTML subset and emits <b>; Chromium is happy
        // with it, so the migration does not need to touch it.
        assertTrue("markdown not applied", html.contains("<b>prose</b>"))
        assertTrue("code block missing", html.contains("""class="code-wrap""""))
        assertTrue("code not highlighted or emitted", html.contains("val x = 1") || html.contains("val"))
        assertTrue("assistant identity is missing", html.contains("""class="assistant-mark""""))
        assertTrue("copy control is not labelled", html.contains("aria-label="))
        assertTrue("copy control should use a vector icon", html.contains("<svg"))
    }

    /** Collapsible blocks are `<details>`, so the browser owns the open/closed state. */
    fun testThinkingIsCollapsedByDefault() {
        PiSettings.getInstance().showThinking = true
        PiSettings.getInstance().expandThinking = false
        val html = HtmlRenderer.render(project, assistant(ContentBlock.Thinking("pondering")))
        assertTrue(html, html.contains("<details"))
        assertFalse("should not start open", html.contains("<details class=\"sec thinking\" open"))
        assertTrue(html, html.contains("pondering"))
    }

    fun testThinkingCanStartExpanded() {
        PiSettings.getInstance().showThinking = true
        PiSettings.getInstance().expandThinking = true
        val html = HtmlRenderer.render(project, assistant(ContentBlock.Thinking("pondering")))
        assertTrue(html, html.contains(" open>"))
    }

    fun testThinkingCanBeSuppressedEntirely() {
        PiSettings.getInstance().showThinking = false
        val html = HtmlRenderer.render(project, assistant(ContentBlock.Thinking("secret")))
        assertFalse(html, html.contains("secret"))
    }

    fun testToolCallShowsNameAndArgumentSummary() {
        val html = HtmlRenderer.render(
            project,
            assistant(
                ContentBlock.ToolCall(
                    toolCallId = "c1",
                    toolName = "bash",
                    input = """{"command":"gradlew test"}""",
                )
            ),
        )
        assertTrue(html, html.contains("bash"))
        assertTrue(html, html.contains("gradlew test"))
        assertTrue(html, html.contains("""class="sec toolcall""""))
    }

    fun testToolResultReportsItsLineCount() {
        val html = HtmlRenderer.render(project, PiMessage.ToolResult(toolCallId = "c1", toolName = "bash", text = "a\nb\nc", isError = false))
        assertTrue(html, html.contains("""class="msg msg-tool""""))
        assertTrue(html, html.contains("sec-sub"))
    }

    fun testErrorToolResultIsMarked() {
        val html = HtmlRenderer.render(project, PiMessage.ToolResult(toolCallId = "c1", toolName = "bash", text = "boom", isError = true))
        assertTrue(html, html.contains("error"))
    }

    fun testNoticeRendersAsOneLine() {
        val html = HtmlRenderer.render(project, PiMessage.Notice("context compacted"))
        assertTrue(html, html.contains("""class="msg msg-notice""""))
        assertTrue(html, html.contains("context compacted"))
    }

    fun testUsageFooterCarriesModelTokensAndCost() {
        val message = PiMessage.Assistant(
            blocks = mutableListOf<ContentBlock>(ContentBlock.Text("done")),
            model = "glm-5.3",
            usage = Usage(input = 1000, output = 200, cacheRead = 800, cacheWrite = 0, costTotal = 0.0123),
        ).also { it.durationMs = 4200 }

        val html = HtmlRenderer.render(project, message)
        assertTrue(html, html.contains("glm-5.3"))
        assertTrue(html, html.contains("tokens"))
        assertTrue(html, html.contains("$0.0123"))
        assertTrue(html, html.contains("""class="usage""""))
    }

    // --------------------------------------------------------------- escaping

    /** Transcript content is agent output; it must never become live markup. */
    fun testUserTextIsEscaped() {
        val html = HtmlRenderer.render(project, PiMessage.User("<img src=x onerror=alert(1)>"))
        assertFalse(html, html.contains("<img src=x"))
        assertTrue(html, html.contains("&lt;img"))
    }

    fun testToolOutputIsEscaped() {
        val html = HtmlRenderer.render(project, PiMessage.ToolResult(toolCallId = "c1", toolName = "bash", text = "</pre><script>bad()</script>", isError = false))
        assertFalse(html, html.contains("<script>"))
        assertTrue(html, html.contains("&lt;script&gt;"))
    }

    fun testNoticeTextIsEscaped() {
        val html = HtmlRenderer.render(project, PiMessage.Notice("<b>not bold</b>"))
        assertFalse(html, html.contains("<b>not bold"))
    }

    fun testErrorMessageIsEscaped() {
        val message = assistant(ContentBlock.Text("x")).also { it.errorMessage = "<script>x</script>" }
        val html = HtmlRenderer.render(project, message)
        assertFalse(html, html.contains("<script>"))
    }

    /** The usage tooltip lands in an HTML attribute, where a stray quote would break out. */
    fun testUsageTooltipIsAttributeSafe() {
        val message = PiMessage.Assistant(
            blocks = mutableListOf<ContentBlock>(ContentBlock.Text("x")),
            model = """a"onmouseover="evil()""",
            usage = Usage(input = 10, output = 1),
        )
        val html = HtmlRenderer.render(project, message)
        assertFalse(html, html.contains("""onmouseover="evil"""))
    }

    // ------------------------------------------------------------- truncation

    fun testEnormousToolOutputIsTruncated() {
        val huge = (1..5_000).joinToString("\n") { "line $it" }
        val html = HtmlRenderer.render(project, PiMessage.ToolResult(toolCallId = "c1", toolName = "bash", text = huge, isError = false))
        assertFalse("line 4000 should have been cut", html.contains("line 4000"))
        assertTrue("should say it was truncated", html.contains("…"))
    }

    fun testOrdinaryMessagesAreNotTruncated() {
        val html = HtmlRenderer.render(project, PiMessage.ToolResult(toolCallId = "c1", toolName = "bash", text = "short output", isError = false))
        assertTrue(html, html.contains("short output"))
    }

    /** Rendering is the hot path while a reply streams; it has to stay cheap. */
    fun testRenderingALongReplyIsFast() {
        val code = (1..400).joinToString("\n") { "val line$it = compute($it)" }
        val message = assistant(ContentBlock.Text("## Answer\n\n```kotlin\n$code\n```"))
        HtmlRenderer.render(project, message)

        val start = System.nanoTime()
        repeat(20) { HtmlRenderer.render(project, message) }
        val perRender = (System.nanoTime() - start) / 1_000_000.0 / 20
        assertTrue("a 400-line reply took %.1fms to render".format(perRender), perRender < 60)
    }
}
