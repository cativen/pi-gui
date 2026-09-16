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
            "elevated-bg" to hex(PiTheme.elevatedBg),
            "input-bg" to hex(PiTheme.inputBg),
            "text-fg" to hex(PiTheme.textFg()),
            "muted-fg" to hex(PiTheme.mutedFg()),
            "accent" to hex(PiTheme.accent),
            "accent-hover" to hex(PiTheme.accentHover),
            // The second stop of the empty-state mark. Derived rather than picked so it stays in
            // step with whatever accent the theme resolves to.
            "accent-soft" to hex(shift(PiTheme.accent, red = 60, blue = 40)),
            // A pill's hover outline: the accent is too loud at full strength on every control.
            "accent-border" to hex(blend(PiTheme.accent, PiTheme.inputBg, 0.55)),
            "accent-tint" to hex(blend(PiTheme.accent, PiTheme.chatBg, if (PiTheme.isDark()) 0.16 else 0.10)),
            "on-accent" to hex(PiTheme.onAccent),
            "border" to hex(PiTheme.toolBorder),
            "pill-bg" to hex(blend(PiTheme.surfaceBg, PiTheme.inputBg, 0.5)),
            "pill-hover-bg" to hex(blend(PiTheme.textFg(), PiTheme.inputBg, 0.08)),
            "code-bg" to hex(PiTheme.codeBg()),
            "user-bubble-bg" to hex(PiTheme.userBubbleBg),
            "user-bubble-fg" to hex(PiTheme.userBubbleFg),
            "error-bg" to hex(PiTheme.errorBg),
            "error-fg" to hex(PiTheme.errorFg),
            "thinking-fg" to hex(PiTheme.thinkingFg),
            "success-fg" to hex(PiTheme.success),
            "success-bg" to hex(blend(PiTheme.success, PiTheme.chatBg, if (PiTheme.isDark()) 0.14 else 0.08)),
            "assistant-bg" to hex(blend(PiTheme.surfaceBg, PiTheme.chatBg, if (PiTheme.isDark()) 0.72 else 0.90)),
            "assistant-border" to hex(blend(PiTheme.textFg(), PiTheme.chatBg, if (PiTheme.isDark()) 0.14 else 0.10)),
            "divider" to hex(PiTheme.messageDivider),
            // Quoted family names: IDE fonts routinely contain spaces.
            "font" to "${font.size}px '${font.family}', system-ui, sans-serif",
            "mono" to "${mono.size}px '${mono.family}', ui-monospace, monospace",
        )
    }

    private fun hex(color: java.awt.Color): String = PiTheme.toHex(color)

    /** [weight] of [front] over [back]; both opaque, so no alpha reaches the page. */
    private fun blend(front: java.awt.Color, back: java.awt.Color, weight: Double): java.awt.Color {
        val w = weight.coerceIn(0.0, 1.0)
        fun mix(f: Int, b: Int) = (f * w + b * (1 - w)).toInt().coerceIn(0, 255)
        return java.awt.Color(
            mix(front.red, back.red),
            mix(front.green, back.green),
            mix(front.blue, back.blue),
        )
    }

    private fun shift(color: java.awt.Color, red: Int = 0, green: Int = 0, blue: Int = 0) =
        java.awt.Color(
            (color.red + red).coerceIn(0, 255),
            (color.green + green).coerceIn(0, 255),
            (color.blue + blue).coerceIn(0, 255),
        )
}
