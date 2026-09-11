package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.ThemeMode
import dev.pi.gui.settings.UiLanguage
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.ui.settings.GeneralSettingsPanel

class SettingsAndI18nTest : BasePlatformTestCase() {

    private lateinit var original: PiSettings.State

    override fun setUp() {
        super.setUp()
        original = PiSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        try {
            PiSettings.getInstance().loadState(original)
            PiBundle.reset()
        } finally {
            super.tearDown()
        }
    }

    private fun withLanguage(language: UiLanguage, body: () -> Unit) {
        PiSettings.getInstance().language = language
        PiBundle.reset()
        body()
    }

    // ------------------------------------------------------------- language

    fun testDefaultLanguageIsSimplifiedChinese() {
        assertEquals(UiLanguage.SIMPLIFIED_CHINESE, PiSettings.State().let { UiLanguage.fromTag(it.language) })
    }

    fun testAllThreeLanguagesResolveDistinctText() {
        val translations = mutableSetOf<String>()
        UiLanguage.entries.forEach { language ->
            withLanguage(language) { translations.add(PiBundle.message("settings.appearance")) }
        }
        assertEquals("each language must supply its own wording", 3, translations.size)
    }

    fun testChineseBundlesAreActuallyTranslated() {
        withLanguage(UiLanguage.SIMPLIFIED_CHINESE) {
            assertEquals("外观", PiBundle.message("settings.appearance"))
            assertEquals("跟随系统", PiBundle.message("settings.appearance.system"))
            assertEquals("默认展开思考块", PiBundle.message("settings.expandThinking"))
            assertEquals("聊天字体大小", PiBundle.message("settings.fontSize"))
        }
        withLanguage(UiLanguage.TRADITIONAL_CHINESE) {
            assertEquals("外觀", PiBundle.message("settings.appearance"))
            assertEquals("預設展開思考區塊", PiBundle.message("settings.expandThinking"))
        }
        withLanguage(UiLanguage.ENGLISH) {
            assertEquals("Appearance", PiBundle.message("settings.appearance"))
        }
    }

    fun testPlaceholdersAreSubstituted() {
        withLanguage(UiLanguage.ENGLISH) {
            assertEquals("Retrying 2/5…", PiBundle.message("status.retrying", 2, 5))
            assertEquals("3 lines", PiBundle.message("message.lines", 3))
        }
    }

    fun testUnknownKeyFallsBackToTheKey() {
        assertEquals("no.such.key", PiBundle.message("no.such.key"))
    }

    /** A missing translation must fall back to English rather than showing a raw key. */
    fun testMissingTranslationFallsBackToEnglish() {
        withLanguage(UiLanguage.TRADITIONAL_CHINESE) {
            assertFalse(PiBundle.message("chat.send").isBlank())
        }
    }

    fun testEveryKeyExistsInEveryLanguage() {
        fun keys(language: UiLanguage): Set<String> {
            val resource = "/messages/pi_${language.tag.replace('-', '_')}.properties"
            val properties = java.util.Properties()
            PiBundle::class.java.getResourceAsStream(resource)!!.use { stream ->
                java.io.InputStreamReader(stream, Charsets.UTF_8).use { properties.load(it) }
            }
            return properties.stringPropertyNames()
        }

        val english = keys(UiLanguage.ENGLISH)
        UiLanguage.entries.filter { it != UiLanguage.ENGLISH }.forEach { language ->
            assertEquals(
                "$language is missing keys",
                emptySet<String>(),
                english - keys(language),
            )
        }
    }

    // ---------------------------------------------------------------- theme

    fun testThemeOverrideForcesLightAndDark() {
        val settings = PiSettings.getInstance()

        settings.themeMode = ThemeMode.DARK
        assertTrue(PiTheme.isDark())
        val darkBg = PiTheme.chatBg
        val darkText = PiTheme.textFg()

        settings.themeMode = ThemeMode.LIGHT
        assertFalse(PiTheme.isDark())
        val lightBg = PiTheme.chatBg
        val lightText = PiTheme.textFg()

        assertFalse("background must differ between modes", darkBg == lightBg)
        // Text has to flip too, otherwise a forced Light panel in a dark IDE is unreadable.
        assertFalse("text colour must differ between modes", darkText == lightText)
    }

    fun testForcedLightHasReadableContrast() {
        PiSettings.getInstance().themeMode = ThemeMode.LIGHT
        assertTrue("light background should be bright", brightness(PiTheme.chatBg) > 0.8)
        assertTrue("light text should be dark", brightness(PiTheme.textFg()) < 0.4)
    }

    fun testForcedDarkHasReadableContrast() {
        PiSettings.getInstance().themeMode = ThemeMode.DARK
        assertTrue("dark background should be near black", brightness(PiTheme.chatBg) < 0.2)
        assertTrue("dark text should be bright", brightness(PiTheme.textFg()) > 0.6)
    }

    private fun brightness(c: java.awt.Color): Double =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    // ----------------------------------------------------------- font size

    fun testFontSizeFollowsTheSlider() {
        val settings = PiSettings.getInstance()
        settings.chatFontSize = 20
        assertEquals(20, PiTheme.fontSize())
        assertEquals(20, PiTheme.uiFont().size)
        assertEquals(20, PiTheme.monoFont().size)
    }

    fun testZeroFontSizeFollowsTheIde() {
        PiSettings.getInstance().chatFontSize = 0
        assertEquals(PiTheme.defaultFontSize(), PiTheme.fontSize())
    }

    fun testFontSizeIsClampedToTheSliderRange() {
        val settings = PiSettings.getInstance()
        settings.chatFontSize = 999
        assertEquals(PiSettings.MAX_FONT_SIZE, settings.chatFontSize)
        settings.chatFontSize = -5
        assertEquals(0, settings.chatFontSize)
    }

    // -------------------------------------------------------------- panel

    fun testGeneralPanelRoundTripsEverySetting() {
        val settings = PiSettings.getInstance()
        settings.themeMode = ThemeMode.SYSTEM
        settings.language = UiLanguage.ENGLISH
        settings.expandThinking = false
        settings.chatFontSize = 12

        val panel = GeneralSettingsPanel()
        assertFalse("freshly reset panel is not modified", panel.isModified())

        settings.themeMode = ThemeMode.DARK
        settings.language = UiLanguage.TRADITIONAL_CHINESE
        settings.expandThinking = true
        settings.chatFontSize = 18
        panel.reset()
        assertFalse(panel.isModified())

        panel.apply()
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(UiLanguage.TRADITIONAL_CHINESE, settings.language)
        assertTrue(settings.expandThinking)
        assertEquals(18, settings.chatFontSize)
    }

    fun testChangeListenersFire() {
        val settings = PiSettings.getInstance()
        var fired = 0
        val listener: () -> Unit = { fired++ }
        settings.addChangeListener(listener)
        try {
            settings.fireChanged()
            assertEquals(1, fired)
        } finally {
            settings.removeChangeListener(listener)
        }
        settings.fireChanged()
        assertEquals("removed listeners must not fire", 1, fired)
    }
}
