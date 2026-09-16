package dev.pi.gui.ui.transcript

import com.intellij.openapi.Disposable
import dev.pi.gui.model.PiMessage
import javax.swing.JComponent

/**
 * The scrolling conversation area, behind an interface so the Swing and JCEF implementations are
 * interchangeable.
 *
 * Everything above this line — windowing, the agent lifecycle, commands — stays where it is; only
 * the part that lays out and paints messages changes. That is also the only part that was slow.
 */
interface TranscriptSurface : Disposable {

    val component: JComponent

    /** Clicked the "load earlier messages" row. */
    var onLoadEarlier: (() -> Unit)?

    /** Replace the whole conversation, e.g. after switching sessions. */
    fun setMessages(messages: List<PiMessage>, hiddenCount: Int)

    /** One finished message arriving at the end. */
    fun appendMessage(message: PiMessage)

    /**
     * Older messages inserted above what is already shown, with the number still hidden after
     * them. The reading position must not jump.
     */
    fun prependMessages(messages: List<PiMessage>, hiddenCount: Int)

    /** The in-progress reply, or null once it has finished and been appended. */
    fun setStreaming(message: PiMessage?)

    /** Watermark shown until the conversation has content. */
    fun showEmptyState(projectPath: String?)

    /** Re-read colours, fonts and language after the settings dialog is accepted. */
    fun applySettings()

    fun scrollToBottom()

    /** True when the user is following the newest message rather than reading back. */
    fun isNearBottom(): Boolean

    /** Messages currently materialised — the windowing invariant, in tests. */
    @org.jetbrains.annotations.TestOnly
    fun renderedCount(): Int
}
