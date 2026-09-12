package dev.pi.gui.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.ThemeMode
import java.awt.Color
import java.awt.Font

/**
 * Colors and fonts for the chat surface.
 *
 * The palette mirrors the jetbrains-cc-gui webview theme (VS Code style): a neutral layered
 * surface stack, a single blue accent, and a filled blue bubble for user messages.
 *
 * Colors are resolved against the plugin's own [ThemeMode] rather than `JBColor`, because the user
 * can pin the panel to Light or Dark independently of the IDE. That also means text colors cannot
 * come from `UIUtil` — a forced Light panel inside a Darcula IDE would otherwise render light grey
 * text on white.
 */
object PiTheme {

    /** True when the chat surface should render dark, honoring the user's override. */
    fun isDark(): Boolean = when (themeMode()) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> !JBColor.isBright()
    }

    private fun themeMode(): ThemeMode = try {
        PiSettings.getInstance().themeMode
    } catch (e: Throwable) {
        ThemeMode.SYSTEM
    }

    private fun pick(light: Int, dark: Int): Color = Color(if (isDark()) dark else light)

    // -- surfaces ----------------------------------------------------------

    /** Conversation canvas (`--bg-primary`). */
    val chatBg: Color get() = pick(0xFFFFFF, 0x1E1E1E)

    /** Sidebars, header strips and cards (`--bg-secondary`). */
    val surfaceBg: Color get() = pick(0xF3F3F3, 0x252526)

    /** Composer and form fields (`--bg-tertiary`). */
    val inputBg: Color get() = pick(0xE8E8E8, 0x2B2D31)

    /** Raised chips and popovers (`--bg-elevated`). */
    val elevatedBg: Color get() = pick(0xF5F5F5, 0x2D2D2D)

    /** Resting background of tool blocks and attachment chips. */
    val toolBg: Color get() = pick(0xF5F5F5, 0x2D2D2D)
    val toolBorder: Color get() = pick(0xD0D0D0, 0x3E3E42)

    /** Hairline between transcript messages (`--color-message-divider`). */
    val messageDivider: Color
        get() = if (isDark()) Color(255, 255, 255, 12) else Color(0, 0, 0, 16)

    /** User message bubble: the accent blue with white text (`--color-message-user-bg`). */
    val userBubbleBg: Color get() = pick(0x0078D4, 0x005FB8)
    val userBubbleBorder: Color get() = pick(0x106EBE, 0x1A6FC4)
    val userBubbleFg: Color get() = Color(0xFFFFFF)

    /** Inline-code background inside the filled user bubble (a shade darker than the bubble). */
    val userBubbleCodeBg: Color get() = pick(0x0063B1, 0x004C96)

    // -- accents -----------------------------------------------------------

    val accent: Color get() = pick(0x0078D4, 0x007FD4)
    val accentHover: Color get() = pick(0x106EBE, 0x026EC1)
    val accentPressed: Color get() = pick(0x005A9E, 0x0060A0)
    val onAccent: Color get() = Color(0xFFFFFF)

    /** Secondary buttons: tertiary fill with a secondary border, like the reference header. */
    val buttonBg: Color get() = pick(0xE8E8E8, 0x2B2D31)
    val buttonHover: Color get() = pick(0xE0E0E0, 0x35373C)
    val buttonPressed: Color get() = pick(0xD6D6D6, 0x37373D)
    val buttonBorder: Color get() = pick(0xC0C0C0, 0x3E3E42)

    val danger: Color get() = pick(0xC50F1F, 0xD32F2F)
    val errorBg: Color get() = pick(0xFBE9E9, 0x3B2323)
    val errorFg: Color get() = pick(0xC50F1F, 0xF4876F)

    val success: Color get() = pick(0x107C10, 0x4CAF50)

    val thinkingFg: Color get() = pick(0x666666, 0x999999)
    val noticeFg: Color get() = pick(0x666666, 0x858585)

    // -- text --------------------------------------------------------------

    fun textFg(): Color = pick(0x333333, 0xCCCCCC)

    fun mutedFg(): Color = pick(0x666666, 0x858585)

    fun placeholderFg(): Color = pick(0xA0A0A0, 0x6E6E6E)

    fun linkFg(): Color = pick(0x0066CC, 0x4A90E2)

    fun panelBg(): Color = chatBg

    fun codeBg(): Color = pick(0xF5F5F5, 0x1E1E1E)

    /** Oversized watermark glyph on the empty state. */
    fun logoFg(): Color = pick(0xD8DCE2, 0x35383E)

    // -- fonts -------------------------------------------------------------

    /** Chat font size in points; the setting wins, otherwise the IDE's label font. */
    fun fontSize(): Int {
        val configured = try {
            PiSettings.getInstance().chatFontSize
        } catch (e: Throwable) {
            0
        }
        return if (configured > 0) configured else defaultFontSize()
    }

    fun defaultFontSize(): Int = try {
        UIUtil.getLabelFont().size
    } catch (e: Throwable) {
        13
    }

    fun uiFont(): Font {
        val base = UIUtil.getLabelFont()
        return base.deriveFont(fontSize().toFloat())
    }

    fun monoFont(): Font {
        val family = try {
            EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).family
        } catch (e: Throwable) {
            Font.MONOSPACED
        }
        return Font(family, Font.PLAIN, fontSize())
    }

    fun toHex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}
