package dev.pi.gui.i18n

import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.UiLanguage
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Message lookup driven by the plugin's own language setting.
 *
 * Deliberately not `ResourceBundle`/`DynamicBundle`: those resolve against the IDE or JVM locale,
 * and the user picks the chat language independently of both.
 */
object PiBundle {

    private val cache = ConcurrentHashMap<UiLanguage, Properties>()

    /** Looks up [key], falling back to English and then to the key itself. */
    fun message(key: String, vararg args: Any?): String {
        val language = currentLanguage()
        val template = bundleFor(language).getProperty(key)
            ?: bundleFor(UiLanguage.ENGLISH).getProperty(key)
            ?: return key
        return if (args.isEmpty()) template else format(template, args)
    }

    private fun format(template: String, args: Array<out Any?>): String = try {
        java.text.MessageFormat.format(template, *args)
    } catch (e: Exception) {
        template
    }

    private fun currentLanguage(): UiLanguage = try {
        PiSettings.getInstance().language
    } catch (e: Throwable) {
        // Service unavailable (e.g. very early startup or a bare unit test).
        UiLanguage.SIMPLIFIED_CHINESE
    }

    private fun bundleFor(language: UiLanguage): Properties = cache.getOrPut(language) {
        val properties = Properties()
        val resource = "/messages/pi_${language.tag.replace('-', '_')}.properties"
        try {
            PiBundle::class.java.getResourceAsStream(resource)?.use { stream ->
                InputStreamReader(stream, StandardCharsets.UTF_8).use { properties.load(it) }
            }
        } catch (e: Exception) {
            // Leave empty; callers fall back to English and then the raw key.
        }
        properties
    }

    /** Drops cached bundles; used by tests that switch language repeatedly. */
    fun reset() = cache.clear()
}
