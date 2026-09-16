package dev.pi.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.pi.gui.model.PiMessage
import dev.pi.gui.ui.ChatPanel
import dev.pi.gui.ui.transcript.ChatSurface
import dev.pi.gui.ui.transcript.WebChatSurface
import dev.pi.gui.web.PiWebView
import dev.pi.gui.web.WebPage
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Phase two: the composer moves into the browser with the conversation.
 *
 * Whether Chromium paints the composer needs a running IDE, and no headless test can stand in for
 * that. What is checkable here is the seam on both sides of the bridge — that every event the
 * plugin posts has a handler in the page, that every message the page sends is dispatched, and
 * that the document really is self-contained. Those are the failures that would otherwise be
 * silent: a renamed field leaves a control simply not working, with nothing in any log.
 */
class WebChatTest : BasePlatformTestCase() {

    /** Stands in for the browser and keeps what was posted. */
    private class RecordingPage : WebPage {
        val posted = mutableListOf<Map<String, Any?>>()
        var focusRequests = 0
        var disposed = false

        override val component: JComponent = JPanel()
        override fun post(event: Map<String, Any?>) { posted.add(event) }
        override fun requestBrowserFocus() { focusRequests++ }
        override fun dispose() { disposed = true }

        fun types(): List<String> = posted.mapNotNull { it["type"] as? String }
        fun last(type: String): Map<String, Any?>? = posted.lastOrNull { it["type"] == type }
    }

    /** The callback the surface hands the page factory — the plugin end of `__piSend`. */
    private var bridge: (JsonObject) -> Unit = {}

    private fun surface(page: RecordingPage): WebChatSurface =
        WebChatSurface(project, onCopy = {}, page = { onMessage ->
            bridge = onMessage
            page
        })

    private fun chatJs(): String =
        PiWebView::class.java.getResourceAsStream("/web/chat.js")!!
            .bufferedReader().use { it.readText() }

    private fun chatCss(): String =
        PiWebView::class.java.getResourceAsStream("/web/app.css")!!
            .bufferedReader().use { it.readText() }

    /** Wide tool windows should use the width the splitter gives them instead of centering 880px. */
    fun testConversationAndComposerUseTheAvailableWidth() {
        val css = chatCss()
        assertFalse("conversation is still capped at 880px", css.contains("max-width: 880px"))
        assertTrue("conversation containers must shrink inside narrow tool windows", css.contains("min-width: 0"))
    }

    /** A live reply is re-rendered frequently, so an entry fade here would restart every flush. */
    fun testStreamingMessageDoesNotReplayTheEntryAnimation() {
        val css = chatCss()
        assertTrue(
            "streaming messages must explicitly disable entry animation",
            css.contains("#streaming .msg { animation: none; }"),
        )
    }

    fun testUserCopyFeedbackKeepsAStableButtonWidth() {
        val css = chatCss()
        assertTrue("user copy button needs reserved width", css.contains(".message-copy") && css.contains("min-width: 76px"))
        assertTrue("copied state should swap to a check icon", css.contains(".message-copy.copied .check-icon"))
        assertTrue("copy action needs visible hover feedback", css.contains(".message-copy:hover"))
    }

    fun testUserCopyUsesTheExactHiddenSourceAndShowsFeedback() {
        val js = chatJs()
        assertTrue("user copy click is not delegated", js.contains("closest('.message-copy')"))
        assertTrue("copy must use the exact source text", js.contains("source.textContent"))
        assertTrue("copy action needs a stable success state", js.contains("classList.add('copied')"))
        assertTrue("copy feedback should reset", js.contains("__copyResetTimer"))
    }

    // ------------------------------------------------------- plugin → the page

    /**
     * Every event the surface can post must be handled by the page.
     *
     * The surface is driven through its whole API rather than matched against a list written out
     * here, so a new method with a misspelled type is caught by the same test that covers the
     * existing ones.
     */
    fun testEveryPostedEventHasAHandlerInThePage() {
        val page = RecordingPage()
        val surface = surface(page)
        try {
            exerciseEverything(surface)
        } finally {
            surface.dispose()
        }

        val handlers = handlerNames(chatJs())
        assertTrue("chat.js declared no handlers — the scrape is wrong", handlers.size > 5)

        val unhandled = page.types().distinct().filterNot { handlers.contains(it) }
        assertEquals("events the page would drop: $unhandled", emptyList<String>(), unhandled)
    }

    /** Names in the `var handlers = { … }` object literal. */
    private fun handlerNames(js: String): Set<String> {
        val body = js.substringAfter("var handlers = {").substringBefore("\n  };")
        return Regex("""^\s{4}(\w+): function""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toSet()
    }

    // ------------------------------------------------------- the page → plugin

    /**
     * Every message the page sends must be dispatched by the surface.
     *
     * A `send({ type: … })` with no arm in the `when` is a control that silently does nothing.
     */
    fun testEveryMessageFromThePageIsDispatched() {
        val sent = Regex("""send\(\{\s*type:\s*'(\w+)'""").findAll(chatJs())
            .map { it.groupValues[1] }.toSet()
        assertTrue("no sends found in chat.js — the scrape is wrong", sent.size > 5)

        sent.forEach { type ->
            val page = RecordingPage()
            val surface = surface(page)
            try {
                var fired = false
                arm(surface, type) { fired = true }
                if (type == "copy" || type == "ready") fired = true // no callback of their own
                deliver(page, type)
                assertTrue("chat.js sends '$type' but the surface ignores it", fired)
            } finally {
                surface.dispose()
            }
        }
    }

    /** Points whichever callback carries [type] at [mark]. `ready` answers on its own. */
    private fun arm(surface: WebChatSurface, type: String, mark: () -> Unit) {
        when (type) {
            "loadEarlier" -> surface.onLoadEarlier = mark
            "copy" -> Unit // handled by the onCopy constructor argument
            "send" -> surface.onSend = { mark() }
            "abort" -> surface.onAbort = mark
            "attach" -> surface.onAttach = mark
            "removeAttachment" -> surface.onRemoveAttachment = { mark() }
            "pasteClipboard" -> surface.onPasteClipboard = mark
            "dropFiles" -> surface.onDropFiles = { mark() }
            "compact" -> surface.onCompact = mark
            "setProvider" -> surface.onSelectProvider = { mark() }
            "setModel" -> surface.onSelectModel = { mark() }
            "setThinking" -> surface.onSelectThinking = { mark() }
            "openDiff" -> surface.onOpenDiff = { mark() }
            "commands" -> surface.onRequestCommands = mark
            "newSession" -> surface.onNewSession = mark
            "refreshSessions" -> surface.onRefreshSessions = mark
            "toggleSidebar" -> surface.onToggleSidebar = mark
            "openSettings" -> surface.onOpenSettings = mark
            "selectSession" -> surface.onSelectSession = { mark() }
            "renameSession" -> surface.onRenameSession = { mark() }
            "deleteSession" -> surface.onDeleteSession = { mark() }
            "ready" -> Unit
            else -> fail("chat.js sends '$type' and this test does not know what it is for")
        }
    }

    /**
     * Feed one message in the way the bridge would.
     *
     * `handle` is private, so the message goes through the factory's callback — the same route a
     * real `__piSend` takes.
     */
    private fun deliver(page: RecordingPage, type: String) {
        val before = page.posted.size
        bridge(
            JsonParser.parseString(
                """{"type":"$type","text":"x","id":"x","path":"x","level":"x","paths":["x"]}"""
            ).asJsonObject
        )
        // `ready` is the page saying it loaded; the answer is a fresh theme and string push.
        if (type == "ready") {
            assertTrue("ready must be answered", page.posted.size > before)
        }
    }

    // ------------------------------------------------------------- the document

    /**
     * Chromium cannot read a plugin's resources out of a jar, so the page must arrive inlined.
     * A renamed resource leaves an unstyled or inert page and says nothing about it.
     */
    fun testTheChatPageIsSelfContained() {
        val doc = PiWebView.document("chat")
        assertFalse("the stylesheet was not inlined", doc.contains("""href="app.css""""))
        assertFalse("the script was not inlined", doc.contains("""src="chat.js""""))
        assertTrue("the stylesheet is missing", doc.contains("--user-bubble-bg"))
        assertTrue("the script is missing", doc.contains("window.__piSend"))
        assertTrue(
            "the policy must still forbid every network origin",
            doc.contains("default-src 'none'"),
        )
    }

    // ---------------------------------------------------------------- composer

    /**
     * The composer is mirrored on send and on set, never per keystroke — a bridge message behind
     * each character is the cost this whole move was meant to remove.
     */
    fun testTheComposerIsMirroredWithoutTrackingKeystrokes() {
        val page = RecordingPage()
        val surface = surface(page)
        try {
            surface.setComposerText("hello", focus = true)
            assertEquals("hello", surface.composerText())
            assertEquals("focus must reach the browser, not just the element", 1, page.focusRequests)

            bridge(JsonParser.parseString("""{"type":"send","text":"typed by hand"}""").asJsonObject)
            assertEquals("typed by hand", surface.composerText())

            surface.setComposerText("")
            assertEquals("", surface.composerText())
        } finally {
            surface.dispose()
        }
    }

    fun testDisposingTheSurfaceDisposesThePage() {
        val page = RecordingPage()
        val surface = surface(page)
        exerciseEverything(surface)
        com.intellij.openapi.util.Disposer.dispose(surface)
        assertTrue("the browser must go with the surface", page.disposed)
    }

    // -------------------------------------------------------------- the bridge

    /**
     * A message from the page must reach the plugin on the EDT.
     *
     * CEF answers on its own handler thread. Every callback ends at Swing — a file chooser, the
     * model combos — so calling straight through threw the platform's EDT assertion, and because
     * the throw landed in the JSON parser's `catch` the only sign was a warning claiming the
     * message was unparseable. Attach and the model list silently did nothing.
     */
    fun testMessagesFromThePageArriveOnTheEdt() {
        if (!PiWebView.isAvailable()) {
            println("SKIPPED: JCEF is not available in this environment")
            return
        }
        val view = try {
            PiWebView("chat") { seenOnEdt.add(ApplicationManager.getApplication().isDispatchThread) }
        } catch (e: Throwable) {
            println("SKIPPED: could not create a JCEF browser here (${e.javaClass.simpleName})")
            return
        }
        try {
            val delivered = CountDownLatch(1)
            // Off the EDT, the way CEF does it.
            ApplicationManager.getApplication().executeOnPooledThread {
                view.deliver(JsonParser.parseString("""{"type":"attach"}""").asJsonObject)
                delivered.countDown()
            }
            assertTrue("the message was never handed over", delivered.await(5, TimeUnit.SECONDS))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals("the message did not arrive", 1, seenOnEdt.size)
            assertTrue("handled off the EDT — every callback below this touches Swing", seenOnEdt[0])
        } finally {
            Disposer.dispose(view)
        }
    }

    private val seenOnEdt = mutableListOf<Boolean>()

    // -------------------------------------------------------------- the panel

    /** In web mode the Swing composer must stay out of the tree, or both would take input. */
    fun testWebModeLeavesTheSwingComposerOutOfTheTree() {
        if (!ChatPanel.useWebTranscript()) {
            println("SKIPPED: JCEF is not available in this environment")
            return
        }
        val panel = ChatPanel(project)
        try {
            assertFalse("the Swing composer is still in the tree", hasTextArea(panel))
        } finally {
            panel.dispose()
        }
    }

    private fun hasTextArea(c: java.awt.Component): Boolean {
        if (c is javax.swing.JTextArea) return true
        if (c is java.awt.Container) return c.components.any { hasTextArea(it) }
        return false
    }

    // ------------------------------------------------------------------ helper

    /** Calls every method that posts, so the scrape above sees the whole surface. */
    private fun exerciseEverything(surface: ChatSurface) {
        surface.showEmptyState("/tmp/project")
        surface.setMessages(listOf(PiMessage.User("one")), hiddenCount = 3)
        surface.appendMessage(PiMessage.User("two"))
        surface.prependMessages(listOf(PiMessage.User("zero")), hiddenCount = 1)
        surface.setStreaming(PiMessage.User("partial"))
        surface.setStreaming(null)
        surface.scrollToBottom()
        surface.applySettings()

        surface.setComposerText("draft", focus = true)
        surface.appendComposerText("@file.kt")
        surface.focusComposer()
        surface.setRunning(true)
        surface.setRunning(false)
        surface.setAttachments(listOf(ChatSurface.Attachment("1", "shot.png")))
        surface.setModels(
            ChatSurface.ModelChoices(
                providers = listOf(ChatSurface.Choice("anthropic", "Anthropic")),
                provider = "anthropic",
                models = listOf(ChatSurface.Choice("claude", "claude")),
                model = "claude",
                thinking = listOf(ChatSurface.Choice("high", "high")),
                thinkingLevel = "high",
            )
        )
        surface.setContext("42%", "42% of the context window")
        surface.setCommands(listOf(ChatSurface.Command("tree", "Show the session tree")))
        surface.setEdits(listOf(ChatSurface.Edit("src/Main.kt", 3, 1)))
        surface.setSendOnEnter(true)

        surface.setSessions(
            listOf(ChatSurface.Session("/tmp/a.jsonl", "a chat", "Sep 15, 19:44")),
            selectedPath = "/tmp/a.jsonl",
        )
        surface.setSidebarVisible(true)
    }
}
