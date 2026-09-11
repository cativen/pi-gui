package dev.pi.gui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.CopyOnWriteArrayList

/** Appearance of the chat surface, independent of the IDE's own theme. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** UI language. Stored by tag so the persisted value survives enum reordering. */
enum class UiLanguage(val tag: String) {
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-CN"),
    TRADITIONAL_CHINESE("zh-TW");

    companion object {
        fun fromTag(tag: String?): UiLanguage =
            entries.firstOrNull { it.tag == tag } ?: SIMPLIFIED_CHINESE
    }
}

@State(name = "PiGuiSettings", storages = [Storage("pi-gui.xml")])
class PiSettings : PersistentStateComponent<PiSettings.State> {

    data class State(
        /** Explicit path to the pi executable; empty means auto-detect. */
        var piPath: String = "",
        /** Extra CLI arguments appended to every `pi --mode rpc` launch. */
        var extraArgs: String = "",
        var showThinking: Boolean = true,
        /** Render thinking blocks already expanded instead of collapsed. */
        var expandThinking: Boolean = false,
        var autoExpandToolCalls: Boolean = false,
        var sendOnEnter: Boolean = true,
        /** Absolute chat font size in points; 0 follows the IDE's label font. */
        var chatFontSize: Int = 0,
        var themeMode: String = ThemeMode.SYSTEM.name,
        var language: String = UiLanguage.SIMPLIFIED_CHINESE.tag,
    )

    private var state = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = state
    override fun loadState(newState: State) { state = newState }

    var piPath: String
        get() = state.piPath
        set(value) { state.piPath = value }

    var extraArgs: String
        get() = state.extraArgs
        set(value) { state.extraArgs = value }

    var showThinking: Boolean
        get() = state.showThinking
        set(value) { state.showThinking = value }

    var expandThinking: Boolean
        get() = state.expandThinking
        set(value) { state.expandThinking = value }

    var autoExpandToolCalls: Boolean
        get() = state.autoExpandToolCalls
        set(value) { state.autoExpandToolCalls = value }

    var sendOnEnter: Boolean
        get() = state.sendOnEnter
        set(value) { state.sendOnEnter = value }

    var chatFontSize: Int
        get() = state.chatFontSize
        set(value) { state.chatFontSize = value.coerceIn(0, MAX_FONT_SIZE) }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(state.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) { state.themeMode = value.name }

    var language: UiLanguage
        get() = UiLanguage.fromTag(state.language)
        set(value) { state.language = value.tag }

    /** Notified after the settings dialog applies changes, so open panels can re-render. */
    fun addChangeListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeChangeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun fireChanged() {
        listeners.forEach { runCatching { it() } }
    }

    /** Extra args split on whitespace, honoring simple double quotes. */
    fun parsedExtraArgs(): List<String> {
        val raw = extraArgs.trim()
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        raw.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    companion object {
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 24

        fun getInstance(): PiSettings =
            ApplicationManager.getApplication().getService(PiSettings::class.java)
    }
}
