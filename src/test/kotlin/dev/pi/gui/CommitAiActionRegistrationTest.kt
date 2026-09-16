package dev.pi.gui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CommitAiActionRegistrationTest : BasePlatformTestCase() {
    fun testActionIsRegisteredInCommitMessageToolbar() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction(ACTION_ID)
        assertNotNull("$ACTION_ID is not registered", action)
        val group = manager.getAction("Vcs.MessageActionGroup") as? ActionGroup
        assertNotNull("Vcs.MessageActionGroup is unavailable", group)
        assertTrue(
            "Commit AI action did not reach the commit message toolbar",
            group!!.getChildren(null).any { it === action || manager.getId(it) == ACTION_ID },
        )
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    private companion object {
        const val ACTION_ID = "dev.pi.gui.GenerateCommitMessage"
    }
}
