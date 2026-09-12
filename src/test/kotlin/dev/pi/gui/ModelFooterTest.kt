package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.ModelOption
import dev.pi.gui.rpc.PiRpcClient
import dev.pi.gui.ui.ChatPanel
import java.io.File

/**
 * The chat footer's provider/model combos.
 *
 * Regression guard for the provider-combo refactor: the model combo is disabled by
 * `stopAgent()`/agent exit and must be re-enabled whenever a model list lands, or the footer
 * shows a permanently grayed combo even though the agent is live and switching would work.
 */
class ModelFooterTest : BasePlatformTestCase() {

    private val models = listOf(
        ModelOption("zai", "glm-4.7"),
        ModelOption("zai", "glm-5.2"),
        ModelOption("kimi", "k2"),
    )

    fun testModelListEnablesEveryFooterCombo() {
        val panel = ChatPanel(project)
        // `/quit` stops the (possibly still-booting) agent exactly like a user-initiated stop,
        // which is the state that used to leave the model combo grayed forever.
        panel.runBuiltinForTest("/quit")
        assertFalse(panel.providerComboForTest().isEnabled)
        assertFalse("model combo must start disabled after the agent stops", panel.modelComboForTest().isEnabled)

        panel.applyModelsForTest(models, dummyClient())

        assertTrue("provider combo must enable with the model list", panel.providerComboForTest().isEnabled)
        assertTrue("model combo must re-enable with the model list", panel.modelComboForTest().isEnabled)
    }

    fun testModelComboShowsTheSelectedProvidersSlice() {
        val panel = ChatPanel(project)
        panel.runBuiltinForTest("/quit")
        panel.applyModelsForTest(models, dummyClient())

        // No saved provider: the first one's models are shown, provider list is deduplicated.
        assertEquals(2, panel.providerComboForTest().itemCount)
        assertEquals(2, panel.modelComboForTest().itemCount)
    }

    fun testEmptyModelListLeavesCombosAlone() {
        val panel = ChatPanel(project)
        panel.runBuiltinForTest("/quit")
        panel.applyModelsForTest(emptyList(), dummyClient())
        assertFalse(panel.modelComboForTest().isEnabled)
    }

    /**
     * A client that never started: every send answers synchronously with a synthetic failure,
     * which the apply path already tolerates (it only surfaces the status text).
     */
    private fun dummyClient() = PiRpcClient(
        piExecutable = File("pi"),
        workingDir = File("."),
        environment = emptyMap(),
    )
}
