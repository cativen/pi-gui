package dev.pi.gui

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.SessionInfo
import dev.pi.gui.rpc.PiRpcClient
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.ui.ChatPanel
import java.io.File

/**
 * Leaving a session must not throw away the turn that is running in it.
 *
 * Starting a new chat, or opening another session, used to stop the agent outright — and with it
 * the reply the user was waiting for. A turn in flight is handed to the background instead: pi
 * finishes it and writes the answer to its own session file.
 */
class BackgroundTurnTest : BasePlatformTestCase() {

    private var savedPiPath: String? = null
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        // A start would otherwise spawn a real agent, which outlives the test JVM.
        savedPiPath = PiSettings.getInstance().piPath
        PiSettings.getInstance().piPath = "C:/no/such/pi-executable.exe"
        dir = FileUtil.createTempDirectory("pi-background", "")
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().piPath = savedPiPath ?: ""
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    /** Never started, so nothing is spawned; the panel only ever moves the reference about. */
    private fun client(): PiRpcClient =
        PiRpcClient(File("/no/such/pi"), dir, emptyMap(), null, emptyList())

    private fun sessionFile(name: String): File =
        File(dir, name).apply { writeText("") }

    fun testNewSessionKeepsARunningTurnAlive() {
        val panel = ChatPanel(project)
        val agent = client()
        try {
            panel.attachAgentForTest(agent, sessionFile = sessionFile("live.jsonl").path)

            panel.startNewSession()

            assertEquals(
                "the running turn must be kept, not killed",
                listOf(agent),
                panel.detachedAgentsForTest(),
            )
            assertNotSame("the new chat must not reuse the old agent", agent, panel.liveAgentForTest())
        } finally {
            panel.dispose()
        }
    }

    /** An idle agent has no reply to lose, and a spare process costs memory. */
    fun testNewSessionStillStopsAnIdleAgent() {
        val panel = ChatPanel(project)
        val agent = client()
        try {
            panel.attachAgentForTest(agent, sessionFile = sessionFile("idle.jsonl").path)
            panel.setRunningForTest(false)

            panel.startNewSession()

            assertEquals(
                "an idle agent should not be left running",
                emptyList<PiRpcClient>(),
                panel.detachedAgentsForTest(),
            )
        } finally {
            panel.dispose()
        }
    }

    fun testSwitchingSessionsKeepsARunningTurnAlive() {
        val panel = ChatPanel(project)
        val agent = client()
        try {
            panel.attachAgentForTest(agent, sessionFile = sessionFile("live.jsonl").path)

            panel.loadSession(info(sessionFile("other.jsonl")))

            assertEquals(listOf(agent), panel.detachedAgentsForTest())
        } finally {
            panel.dispose()
        }
    }

    /**
     * Coming back to the session adopts the agent still finishing its turn there. Two processes
     * writing one session file would interleave their entries.
     */
    fun testReopeningTheSessionAdoptsItsBackgroundAgent() {
        val panel = ChatPanel(project)
        val agent = client()
        val live = sessionFile("live.jsonl")
        try {
            panel.attachAgentForTest(agent, sessionFile = live.path)
            panel.loadSession(info(sessionFile("other.jsonl")))
            assertEquals(listOf(agent), panel.detachedAgentsForTest())

            panel.loadSession(info(live))

            assertSame("the running agent should be picked back up", agent, panel.liveAgentForTest())
            assertEquals(
                "and no longer counted as backgrounded",
                emptyList<PiRpcClient>(),
                panel.detachedAgentsForTest(),
            )
        } finally {
            panel.dispose()
        }
    }

    fun testDisposingThePanelStopsBackgroundAgents() {
        val panel = ChatPanel(project)
        val agent = client()
        panel.attachAgentForTest(agent, sessionFile = sessionFile("live.jsonl").path)
        panel.startNewSession()
        assertEquals(listOf(agent), panel.detachedAgentsForTest())

        panel.dispose()

        assertEquals(
            "nothing is left to read the reply, so it must not outlive the panel",
            emptyList<PiRpcClient>(),
            panel.detachedAgentsForTest(),
        )
    }

    private fun info(file: File) = SessionInfo(
        id = file.nameWithoutExtension,
        filePath = file.path,
        cwd = dir.path,
        name = file.nameWithoutExtension,
        lastModified = file.lastModified(),
        createdAt = file.lastModified(),
        preview = null,
        messageCount = 0,
    )
}
