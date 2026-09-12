package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import dev.pi.gui.session.SessionStore
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.ChatPanel
import dev.pi.gui.ui.MessageRenderer
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Guards the two defects found during the full-feature verification pass: clipboard paste being
 * swallowed by the file-drop handler, and unbounded content freezing the UI.
 */
class PerformanceAndInputTest : BasePlatformTestCase() {

    // ------------------------------------------------------------- clipboard

    /**
     * Regression: the attachment drop handler replaced the text area's TransferHandler, and
     * paste/copy/cut all route through it, so pasting text silently did nothing.
     */
    fun testPlainTextPasteStillWorks() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection("pasted text"), null)
            input.text = ""
            input.paste()
            assertEquals("pasted text", input.text)
        } finally {
            panel.dispose()
        }
    }

    fun testCopyFromInputStillWorks() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            input.text = "copy me"
            input.selectAll()
            input.copy()
            val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
            assertEquals("copy me", clip)
        } finally {
            panel.dispose()
        }
    }

    // ----------------------------------------------------------- performance

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it with some content" }

    /** Collapsed sections must not build their body until opened. */
    fun testCollapsedToolResultIsCheapRegardlessOfSize() {
        val huge = lines(10_000)
        val start = System.currentTimeMillis()
        val c = MessageRenderer.render(project, PiMessage.ToolResult("t", "bash", huge, false))
        c.size = Dimension(700, 100_000)
        c.doLayout()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("collapsed 10k-line result took ${elapsed}ms", elapsed < 400)
    }

    fun testHugeCodeBlockIsCapped() {
        val code = "```kotlin\n" + lines(20_000) + "\n```"
        val start = System.currentTimeMillis()
        val c = MessageRenderer.render(project, PiMessage.Assistant(mutableListOf(ContentBlock.Text(code))))
        c.size = Dimension(700, 200_000)
        c.doLayout()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("20k-line code block took ${elapsed}ms", elapsed < 4_000)
    }

    /** Opening a long session must stay responsive; only the tail is materialised. */
    fun testLongTranscriptOpensQuickly() {
        val messages = buildList {
            repeat(400) { i ->
                add(PiMessage.User("question $i"))
                add(PiMessage.Assistant(mutableListOf(ContentBlock.Text("answer $i with `code` and text"))))
            }
        }
        val panel = ChatPanel(project)
        try {
            panel.size = Dimension(700, 900)
            val start = System.currentTimeMillis()
            panel.setTranscriptForPreview(messages)
            panel.doLayout()
            val elapsed = System.currentTimeMillis() - start
            assertTrue("800-message transcript took ${elapsed}ms to open", elapsed < 1_500)
        } finally {
            panel.dispose()
        }
    }

    fun testWindowingRendersOnlyTheTailPlusLoadButton() {
        val messages = (1..300).map { PiMessage.User("message $it") as PiMessage }
        val panel = ChatPanel(project)
        try {
            panel.setTranscriptForPreview(messages)
            val rendered = panel.transcriptChildCountForTest()
            assertTrue(
                "expected a windowed transcript, rendered $rendered of ${messages.size}",
                rendered in 2..80,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testShortTranscriptRendersEverything() {
        val messages = (1..5).map { PiMessage.User("message $it") as PiMessage }
        val panel = ChatPanel(project)
        try {
            panel.setTranscriptForPreview(messages)
            assertEquals(5, panel.transcriptChildCountForTest())
        } finally {
            panel.dispose()
        }
    }

    // --------------------------------------------------- session switching

    /**
     * Re-clicking the session that is already loaded must be a no-op. A reload kills and
     * re-spawns the agent and re-renders everything, which made clicking around the list
     * feel laggy; disk changes are picked up by the refresh button instead.
     */
    fun testReloadingTheLoadedSessionIsASkip() {
        // Point pi at a path that cannot exist: this test must not spawn a real agent, whose
        // startup events append notices to the transcript and whose process outlives the test
        // long enough to lock the framework's temp directory on Windows.
        val settings = PiSettings.getInstance()
        val savedPiPath = settings.piPath
        settings.piPath = "C:/no/such/pi-executable.exe"

        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("pi-session", "")
        val f = File(dir, "s.jsonl")
        f.writeText(
            listOf(
                """{"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
                """{"type":"message","id":"a1","parentId":null,"message":{"role":"user","content":"first"}}""",
                """{"type":"message","id":"a2","parentId":"a1","message":{"role":"assistant","content":[{"type":"text","text":"reply one"}]}}""",
            ).joinToString("\n") + "\n",
        )
        val info = SessionStore.readSessionInfo(f)!!

        val panel = ChatPanel(project)
        try {
            panel.size = Dimension(700, 900)
            panel.loadSession(info)
            assertTrue("first load never landed", pumpUntil { panel.transcriptChildCountForTest() > 0 })
            val rendered = panel.transcriptChildCountForTest()

            // The file grows on disk; a re-click must NOT re-read it.
            f.appendText(
                """{"type":"message","id":"a3","parentId":"a2","message":{"role":"assistant","content":[{"type":"text","text":"reply two"}]}}""" + "\n" +
                    """{"type":"message","id":"a4","parentId":"a3","message":{"role":"assistant","content":[{"type":"text","text":"reply three"}]}}""" + "\n",
            )
            panel.loadSession(info)
            assertEquals("guard must keep pointing at the loaded session", info.filePath, panel.loadedSessionPathForTest())
            pumpUntil(1200) { false } // give a (wrong) reload every chance to land
            assertEquals(
                "re-click must not re-read the session file",
                rendered,
                panel.transcriptChildCountForTest(),
            )
            assertEquals(info.filePath, panel.currentSession()?.filePath)

            // A different session still loads normally.
            val other = File(dir, "other.jsonl")
            other.writeText(
                listOf(
                    """{"type":"session","version":3,"id":"s2","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/tmp/p"}""",
                    """{"type":"message","id":"b1","parentId":null,"message":{"role":"user","content":"other session"}}""",
                ).joinToString("\n") + "\n",
            )
            val otherInfo = SessionStore.readSessionInfo(other)!!
            panel.loadSession(otherInfo)
            assertTrue("switch to another session never landed", pumpUntil {
                panel.currentSession()?.filePath == otherInfo.filePath && panel.transcriptChildCountForTest() > 0
            })

            // /new clears the guard, so the first session can be loaded again.
            panel.startNewSession()
            panel.loadSession(info)
            assertTrue("reload after /new never landed", pumpUntil {
                panel.currentSession()?.filePath == info.filePath && panel.transcriptChildCountForTest() >= rendered
            })
        } finally {
            panel.dispose()
            settings.piPath = savedPiPath
            com.intellij.openapi.util.io.FileUtil.delete(dir)
        }
    }

    /** Processes queued EDT work while waiting for an async condition. */
    private fun pumpUntil(timeoutMs: Int = 5000, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return condition()
    }
}
