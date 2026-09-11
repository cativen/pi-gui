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

    /** Conversation canvas: near-black when dark so the chat reads as its own surface. */
    val chatBg: Color get() = pick(0xFFFFFF, 0x141517)
    val surfaceBg: Color get() = pick(0xF7F8FA, 0x1B1C1F)
    val inputBg: Color get() = pick(0xFFFFFF, 0x222327)

    val toolBg: Color get() = pick(0xF5F6F8, 0x1E2023)
    val toolBorder: Color get() = pick(0xE0E2E6, 0x2C2E33)

    val userBubbleBg: Color get() = pick(0xEEF2FB, 0x242830)
    val userBubbleBorder: Color get() = pick(0xD3DDF0, 0x313640)

    // -- accents -----------------------------------------------------------

    val accent: Color get() = pick(0x2F7AF0, 0x4D8EFF)
    val accentHover: Color get() = pick(0x1F6AE0, 0x669EFF)
    val accentPressed: Color get() = pick(0x185CC8, 0x3D7EEF)
    val onAccent: Color get() = Color(0xFFFFFF)

    val buttonBg: Color get() = pick(0xFFFFFF, 0x2A2C31)
    val buttonHover: Color get() = pick(0xEFF1F4, 0x34363C)
    val buttonPressed: Color get() = pick(0xE3E6EA, 0x3D3F46)
    val buttonBorder: Color get() = pick(0xD5D9DF, 0x3A3C42)

    val danger: Color get() = pick(0xD1453B, 0xE06C60)
    val errorBg: Color get() = pick(0xFDF0F0, 0x3B2B2B)
    val errorFg: Color get() = pick(0xC03939, 0xE07A7A)

    val thinkingFg: Color get() = pick(0x7A7E85, 0x868A91)
    val noticeFg: Color get() = pick(0x8A8E95, 0x7E8289)

    // -- text --------------------------------------------------------------

    fun textFg(): Color = pick(0x1F2328, 0xDFE1E5)

    fun mutedFg(): Color = pick(0x6E7781, 0x8B9098)

    fun linkFg(): Color = accent

    fun panelBg(): Color = chatBg

    fun codeBg(): Color = pick(0xF2F3F5, 0x1E2024)

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
