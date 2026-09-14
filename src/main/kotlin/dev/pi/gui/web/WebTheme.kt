package dev.pi.gui.web

import dev.pi.gui.ui.PiTheme

/**
 * Publishes [PiTheme] to the page as CSS custom properties.
 *
 * The stylesheet knows nothing about the IDE's theme or the user's font-size setting; it reads
 * variables, and these are the values. That keeps the Light/Dark override — which the plugin
 * resolves itself rather than through `JBColor` — working exactly as it does in Swing.
 */
object WebTheme {

    fun variables(): Map<String, String> {
        val font = PiTheme.uiFont()
        val mono = PiTheme.monoFont()
        return mapOf(
            "chat-bg" to hex(PiTheme.chatBg),
            "surface-bg" to hex(PiTheme.surfaceBg),
            "input-bg" to hex(PiTheme.inputBg),
            "text-fg" to hex(PiTheme.textFg()),
            "muted-fg" to hex(PiTheme.mutedFg()),
            "accent" to hex(PiTheme.accent),
            "on-accent" to hex(PiTheme.onAccent),
            "border" to hex(PiTheme.toolBorder),
            "code-bg" to hex(PiTheme.codeBg()),
            "user-bubble-bg" to hex(PiTheme.userBubbleBg),
            "user-bubble-fg" to hex(PiTheme.userBubbleFg),
            "error-bg" to hex(PiTheme.errorBg),
            "error-fg" to hex(PiTheme.errorFg),
            "thinking-fg" to hex(PiTheme.thinkingFg),
            "divider" to hex(PiTheme.messageDivider),
            // Quoted family names: IDE fonts routinely contain spaces.
            "font" to "${font.size}px '${font.family}', system-ui, sans-serif",
            "mono" to "${mono.size}px '${mono.family}', ui-monospace, monospace",
        )
    }

    private fun hex(color: java.awt.Color): String = PiTheme.toHex(color)
}
