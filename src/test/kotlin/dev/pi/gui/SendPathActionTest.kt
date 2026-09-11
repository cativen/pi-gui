package dev.pi.gui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.action.SendPathToPiAction

/**
 * Drives the action through the real action system so the data-context resolution — which is
 * where the file / folder / selection cases diverge — is actually exercised.
 */
class SendPathActionTest : BasePlatformTestCase() {

    private fun presentationFor(context: DataContext): com.intellij.openapi.actionSystem.Presentation {
        val action = SendPathToPiAction()
        val event = AnActionEvent.createFromDataContext(ActionPlaces.PROJECT_VIEW_POPUP, null, context)
        action.update(event)
        return event.presentation
    }

    fun testActionIsRegistered() {
        assertNotNull(
            "action must be registered in plugin.xml",
            ActionManager.getInstance().getAction("dev.pi.gui.SendPathToPi"),
        )
    }

    /**
     * Every menu a file or folder can be right-clicked from must offer the action, and it has to
     * be near the top: these menus are long enough to run off the bottom of the screen, and with
     * `anchor="last"` the item was registered but unreachable.
     */
    fun testActionSitsNearTheTopOfEveryFileMenu() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("dev.pi.gui.SendPathToPi")

        listOf(
            "ProjectViewPopupMenu",
            "EditorPopupMenu",
            "EditorTabPopupMenu",
            "NavbarPopupMenu",
            "ScopeViewPopupMenu",
        ).forEach { groupId ->
            val group = manager.getAction(groupId) as? com.intellij.openapi.actionSystem.DefaultActionGroup
            assertNotNull("$groupId should exist", group)
            val children = group!!.getChildren(manager).toList()
            val index = children.indexOf(action)
            assertTrue("$groupId is missing the action", index >= 0)
            assertTrue(
                "$groupId places the action at $index — too far down to be reachable",
                index <= 2,
            )
        }
    }

    fun testSingleFileLabel() {
        val file = myFixture.addFileToProject("src/Sample.kt", "val a = 1").virtualFile
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()

        val presentation = presentationFor(context)
        assertTrue(presentation.isEnabledAndVisible)
        assertEquals("Send File Path to Pi GUI", presentation.text)
    }

    fun testFolderLabel() {
        myFixture.addFileToProject("tools/thing.txt", "x")
        val dir = myFixture.findFileInTempDir("tools")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, dir)
            .build()

        val presentation = presentationFor(context)
        assertTrue(presentation.isEnabledAndVisible)
        assertEquals("Send Folder Path to Pi GUI", presentation.text)
    }

    fun testMultiSelectionLabel() {
        val a = myFixture.addFileToProject("a.txt", "a").virtualFile
        val b = myFixture.addFileToProject("b.txt", "b").virtualFile
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(a, b))
            .build()

        val presentation = presentationFor(context)
        assertTrue(presentation.isEnabledAndVisible)
        assertEquals("Send 2 Paths to Pi GUI", presentation.text)
    }

    fun testSelectionInEditorLabel() {
        myFixture.configureByText("Selected.kt", "line1\nline2\nline3\n")
        myFixture.editor.selectionModel.setSelection(0, myFixture.editor.document.getLineEndOffset(1))

        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
            .build()

        val presentation = presentationFor(context)
        assertTrue(presentation.isEnabledAndVisible)
        assertEquals("Send Selection Path to Pi GUI", presentation.text)
    }

    fun testHiddenWithoutAnyTarget() {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()
        assertFalse(presentationFor(context).isEnabledAndVisible)
    }

    fun testHiddenWithoutProject() {
        assertFalse(presentationFor(SimpleDataContext.builder().build()).isEnabledAndVisible)
    }
}
