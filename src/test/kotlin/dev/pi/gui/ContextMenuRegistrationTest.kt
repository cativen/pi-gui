package dev.pi.gui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * "Send File Path to Pi GUI" must be in the menus it claims to be in.
 *
 * It went missing from the project view's right-click menu on a user's machine while the
 * descriptor said it was registered, so both halves are checked here: that the action reaches the
 * group, and that `update` actually shows it for a file and for a folder.
 */
class ContextMenuRegistrationTest : BasePlatformTestCase() {

    private val actionId = "dev.pi.gui.SendPathToPi"

    private val groups = listOf(
        "ProjectViewPopupMenu",
        "EditorPopupMenu",
        "ConsoleView.PopupMenu",
        "EditorTabPopupMenu",
        "NavbarPopupMenu",
        "ScopeViewPopupMenu",
    )

    fun testTheActionIsRegistered() {
        assertNotNull(
            "$actionId is not registered at all",
            ActionManager.getInstance().getAction(actionId),
        )
    }

    fun testItIsAChildOfEveryMenuItClaims() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction(actionId)
        val missing = groups.filter { id ->
            val group = manager.getAction(id) as? ActionGroup
                ?: fail("no such group: $id").let { return@filter true }
            group.getChildren(null).none { it === action || manager.getId(it) == actionId }
        }
        assertEquals("the action never reached these menus: $missing", emptyList<String>(), missing)
    }

    /**
     * An EDT-updating action is dropped from the project view menu on 2026.1 without a word in any
     * log. Every action this plugin puts in a menu updates on a background thread.
     */
    fun testEveryMenuActionUpdatesInTheBackground() {
        val manager = ActionManager.getInstance()
        val edt = listOf(actionId, "dev.pi.gui.OpenToolWindow", "dev.pi.gui.NewSession")
            .filter { manager.getAction(it)?.actionUpdateThread != ActionUpdateThread.BGT }
        assertEquals("these would vanish from their menus on 2026.1: $edt", emptyList<String>(), edt)
    }

    fun testItIsVisibleForAFile() {
        val file = myFixture.addFileToProject("src/Main.kt", "fun main() {}").virtualFile
        assertVisible(file)
    }

    fun testItIsVisibleForAFolder() {
        myFixture.addFileToProject("src/pkg/Main.kt", "fun main() {}")
        val folder = myFixture.findFileInTempDir("src/pkg")
        assertVisible(folder)
    }

    private fun assertVisible(file: VirtualFile) {
        val action = ActionManager.getInstance().getAction(actionId)
        val presentation = Presentation()
        val context = DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                CommonDataKeys.VIRTUAL_FILE_ARRAY.name -> arrayOf(file)
                else -> null
            }
        }
        val event = AnActionEvent.createEvent(
            action, context, presentation, ActionPlaces.PROJECT_VIEW_POPUP,
            com.intellij.openapi.actionSystem.ActionUiKind.POPUP, null,
        )
        action.update(event)
        assertTrue(
            "hidden for ${file.path} (isVisible=${presentation.isVisible})",
            presentation.isVisible,
        )
        assertTrue("disabled for ${file.path}", presentation.isEnabled)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    @Suppress("unused")
    private fun describe(a: AnAction) = "${a.javaClass.name}"
}
