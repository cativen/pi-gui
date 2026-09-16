package dev.pi.gui.ui.settings

import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import dev.pi.gui.PiLocator
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.packages.PackageScope
import dev.pi.gui.packages.PackagesRegistry
import dev.pi.gui.packages.PackagesService
import dev.pi.gui.providers.CcSwitchImporter
import dev.pi.gui.providers.ProvidersRegistry
import dev.pi.gui.rpc.PiJson
import dev.pi.gui.rpc.asStringOrNull
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.CommitLanguage
import dev.pi.gui.settings.ThemeMode
import dev.pi.gui.settings.UiLanguage
import dev.pi.gui.skills.SearchTimeoutException
import dev.pi.gui.skills.SkillScope
import dev.pi.gui.skills.SkillsRegistry
import dev.pi.gui.skills.SkillsService
import dev.pi.gui.ui.PiTheme
import dev.pi.gui.web.PiWebView
import dev.pi.gui.web.WebPage
import dev.pi.gui.web.WebTheme
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * JCEF settings application shared by the toolbar dialog and IDE Settings configurable.
 *
 * Chromium owns layout and interaction; this controller owns all privileged work: settings,
 * filesystem access, command execution, browser opening and native confirmation/file dialogs.
 * No local server or network origin is exposed to the page.
 */
class WebSettingsSurface(
    private val project: Project?,
    private val registry: ProvidersRegistry = ProvidersRegistry.default(),
    private val embedded: Boolean = false,
    private val onClose: () -> Unit = {},
    page: ((JsonObject) -> Unit) -> WebPage = { PiWebView("settings", it) },
) : Disposable {

    private val view = page(::handle)
    private var disposed = false
    private var draft = Draft.read()
    @Volatile private var detectedPi: String? = null
    @Volatile private var detectingPi = true

    val component get() = view.component

    init {
        pushTheme()
        pushStrings()
        pushState()
        detectPi()
        reloadProviders()
        reloadSkills()
        reloadPackages()
    }

    fun isModified(): Boolean = draft != Draft.read()

    fun reset() {
        draft = Draft.read()
        pushState()
        pushTheme()
    }

    /** Refresh data every time the in-tool-window page is entered. */
    fun activate() {
        draft = Draft.read()
        pushTheme()
        pushStrings()
        pushState()
        reloadProviders()
        reloadSkills()
        reloadPackages()
    }

    fun apply() {
        val settings = PiSettings.getInstance()
        settings.themeMode = draft.theme
        settings.language = draft.language
        settings.showThinking = draft.showThinking
        settings.expandThinking = draft.expandThinking
        settings.autoExpandToolCalls = draft.expandToolCalls
        settings.sendOnEnter = draft.sendOnEnter
        settings.chatFontSize = draft.fontSize
        settings.piPath = draft.piPath.trim()
        settings.extraArgs = draft.extraArgs.trim()
        settings.commitLanguage = draft.commitLanguage
        settings.commitPrompt = draft.commitPrompt.trim()
        settings.fireChanged()
    }

    private fun handle(message: JsonObject) {
        val type = message["type"]?.asStringOrNull() ?: return
        val text = { key: String -> message[key]?.asStringOrNull().orEmpty() }
        when (type) {
            "ready" -> {
                pushTheme(); pushStrings(); pushState(); reloadProviders(); reloadSkills(); reloadPackages()
            }
            "closeSettings" -> onClose()
            "updateDraft" -> {
                val field = text("field")
                updateDraft(field, message["value"])
                if (embedded) applyEmbeddedChange(field)
            }
            "resetDraft" -> {
                draft = Draft.defaults()
                pushState()
                if (embedded) applyEmbeddedChange("all")
            }
            "resetCommitPrompt" -> {
                draft = draft.copy(commitPrompt = PiSettings.DEFAULT_COMMIT_PROMPT)
                pushState()
                if (embedded) applyEmbeddedChange("commitPrompt")
            }
            "choosePi" -> choosePiExecutable()
            "importProvidersAuto" -> importProviders { CcSwitchImporter.importAuto() }
            "importProvidersDb" -> chooseAndImportProviderDb()
            "enableProvider" -> enableProvider(text("id"))
            "saveProvider" -> saveProvider(message)
            "deleteProvider" -> deleteProvider(text("id"))
            "toggleSkill" -> toggleSkill(text("path"), message["enabled"]?.asBoolean == true)
            "searchSkills" -> searchSkills(text("query"))
            "installSkill" -> installSkill(text("id"), text("scope"))
            "searchPackages" -> searchPackages(text("query"))
            "installPackage" -> installPackage(text("source"), text("scope"))
            "removePackage" -> removePackage(text("source"), text("scope"))
            "refreshPackages" -> reloadPackages()
            "openPackages" -> BrowserUtil.browse(PackagesRegistry.PACKAGES_URL)
        }
    }

    private fun updateDraft(field: String, value: com.google.gson.JsonElement?) {
        draft = when (field) {
            "theme" -> draft.copy(theme = runCatching { ThemeMode.valueOf(value?.asString.orEmpty()) }.getOrDefault(draft.theme))
            "language" -> draft.copy(language = UiLanguage.fromTag(value?.asString))
            "showThinking" -> draft.copy(showThinking = value?.asBoolean ?: draft.showThinking)
            "expandThinking" -> draft.copy(expandThinking = value?.asBoolean ?: draft.expandThinking)
            "expandToolCalls" -> draft.copy(expandToolCalls = value?.asBoolean ?: draft.expandToolCalls)
            "sendOnEnter" -> draft.copy(sendOnEnter = value?.asBoolean ?: draft.sendOnEnter)
            "fontSize" -> draft.copy(fontSize = (value?.asInt ?: draft.fontSize).coerceIn(PiSettings.MIN_FONT_SIZE, PiSettings.MAX_FONT_SIZE))
            "piPath" -> draft.copy(piPath = value?.asString.orEmpty())
            "extraArgs" -> draft.copy(extraArgs = value?.asString.orEmpty())
            "commitLanguage" -> draft.copy(commitLanguage = CommitLanguage.from(value?.asString))
            "commitPrompt" -> draft.copy(
                commitPrompt = value?.asString.orEmpty().take(PiSettings.MAX_COMMIT_PROMPT_LENGTH),
            )
            else -> draft
        }
    }

    private fun choosePiExecutable() {
        val chooser = JFileChooser().apply {
            dialogTitle = PiBundle.message("settings.cli.path")
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
        }
        if (chooser.showOpenDialog(component) == JFileChooser.APPROVE_OPTION) {
            draft = draft.copy(piPath = chooser.selectedFile?.absolutePath.orEmpty())
            pushState()
            if (embedded) applyEmbeddedChange("piPath")
        }
    }

    private fun applyEmbeddedChange(field: String) {
        apply()
        if (field == "theme" || field == "fontSize" || field == "all") pushTheme()
        if (field == "language" || field == "all") pushStrings()
        pushState()
        post("saved", "message" to PiBundle.message("settings.autoSaved"))
    }

    private fun detectPi() = pooled {
        detectedPi = PiLocator.discover()?.absolutePath
        detectingPi = false
        pushState()
    }

    // --------------------------------------------------------------- providers

    private fun reloadProviders() {
        post(
            "providers",
            "items" to registry.list().map { provider ->
                mapOf(
                    "id" to provider.id,
                    "name" to provider.name,
                    "kind" to provider.kind.name,
                    "baseUrl" to provider.baseUrl,
                    "apiKey" to provider.apiKey,
                    "models" to provider.models,
                    "current" to (PiSettings.getInstance().activeProvider == provider.id),
                )
            },
        )
    }

    private fun importProviders(block: () -> CcSwitchImporter.Outcome) {
        status("providers", PiBundle.message("providers.importing"), busy = true)
        pooled {
            val outcome = runCatching(block).getOrElse { CcSwitchImporter.Outcome(emptyList(), emptyList()) }
            if (outcome.imported.isEmpty() && outcome.skipped.isEmpty()) {
                PiBundle.message("providers.import.empty")
                    .let { status("providers", it) }
                return@pooled
            }
            val summary = registry.import(outcome)
            val message = PiBundle.message("providers.import.done", summary.added, summary.updated, summary.skipped.size) +
                (outcome.source?.let { PiBundle.message("providers.import.source", it) } ?: "")
            status("providers", message)
            reloadProviders()
        }
    }

    private fun chooseAndImportProviderDb() {
        val chooser = JFileChooser().apply {
            dialogTitle = PiBundle.message("providers.import.db.title")
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter(PiBundle.message("providers.import.db.desc"), "db")
        }
        if (chooser.showOpenDialog(component) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.let { file -> importProviders { CcSwitchImporter.importFromDb(file) } }
        }
    }

    private fun enableProvider(id: String) {
        val provider = registry.find(id) ?: return
        PiSettings.getInstance().apply {
            activeProvider = provider.id
            activeModel = provider.defaultModel ?: provider.models.firstOrNull().orEmpty()
            fireChanged()
        }
        reloadProviders()
    }

    private fun saveProvider(message: JsonObject) {
        val original = registry.find(message["id"]?.asStringOrNull().orEmpty()) ?: return
        val models = PiJson.asArray(message["models"])?.mapNotNull { it.asStringOrNull()?.trim() }
            ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        val name = message["name"]?.asStringOrNull()?.trim().orEmpty()
        val baseUrl = message["baseUrl"]?.asStringOrNull()?.trim().orEmpty()
        val apiKey = message["apiKey"]?.asStringOrNull()?.trim().orEmpty()
        if (name.isEmpty() || baseUrl.isEmpty() || apiKey.isEmpty() || models.isEmpty()) {
            status("providers", PiBundle.message("providers.invalid"), error = true)
            return
        }
        val edited = original.copy(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = models,
            defaultModel = original.defaultModel?.takeIf { it in models } ?: models.first(),
        )
        registry.update(edited)
        PiSettings.getInstance().apply {
            if (activeProvider == edited.id) {
                activeModel = models.firstOrNull { it == activeModel } ?: edited.defaultModel ?: models.first()
                fireChanged()
            }
        }
        status("providers", PiBundle.message("settings.saved"))
        reloadProviders()
    }

    private fun deleteProvider(id: String) {
        val provider = registry.find(id) ?: return
        if (!confirm(PiBundle.message("providers.delete.confirm", provider.name), PiBundle.message("settings.tab.providers"))) return
        registry.delete(id)
        PiSettings.getInstance().apply {
            if (activeProvider == id) {
                activeProvider = ""; activeModel = ""; fireChanged()
            }
        }
        reloadProviders()
    }

    // ------------------------------------------------------------------- skills

    private fun reloadSkills() = pooled {
        val items = runCatching { SkillsService.listSkills(project?.basePath) }.getOrDefault(emptyList())
        post(
            "skills",
            "items" to items.map { skill ->
                mapOf(
                    "name" to skill.name,
                    "description" to skill.description,
                    "path" to skill.filePath,
                    "displayPath" to skill.displayPath(),
                    "scope" to skill.scope.name,
                    "enabled" to skill.enabled,
                )
            },
        )
    }

    private fun toggleSkill(path: String, enabled: Boolean) {
        val skill = SkillsService.listSkills(project?.basePath).firstOrNull { it.filePath == path } ?: return
        if (!SkillsService.setEnabled(skill, enabled)) {
            status("skills", PiBundle.message("skills.toggleFailed", skill.displayPath()), error = true)
        }
        reloadSkills()
    }

    private fun searchSkills(query: String) {
        if (query.isBlank()) return
        status("skills", PiBundle.message("skills.searching"), busy = true)
        pooled {
            runCatching { SkillsRegistry.search(query) }
                .onSuccess { found ->
                    post(
                        "skillResults",
                        "items" to found.map { mapOf("id" to it.id, "name" to it.name, "source" to it.source, "installs" to it.installsLabel()) },
                    )
                    status("skills", if (found.isEmpty()) PiBundle.message("skills.search.none") else PiBundle.message("skills.search.count", found.size))
                }
                .onFailure { error ->
                    status(
                        "skills",
                        if (error is SearchTimeoutException) PiBundle.message("skills.search.timeout") else PiBundle.message("skills.search.failed", error.message ?: ""),
                        error = true,
                    )
                }
        }
    }

    private fun installSkill(id: String, scopeName: String) {
        val scope = if (scopeName == SkillScope.PROJECT.name) SkillScope.PROJECT else SkillScope.GLOBAL
        val target = if (scope == SkillScope.PROJECT) project?.basePath ?: return else PiBundle.message("skills.scope.global")
        if (!confirm(PiBundle.message("skills.install.confirm", id, target), PiBundle.message("skills.add"))) return
        status("skills", PiBundle.message("skills.installing", id), busy = true)
        pooled {
            val result = SkillsRegistry.install(id, scope, project?.basePath)
            if (result.success) {
                status("skills", PiBundle.message("skills.install.done", id))
                reloadSkills()
            } else {
                status("skills", PiBundle.message("skills.install.failedShort"), error = true)
                showError(PiBundle.message("skills.install.failed", result.output.takeLast(600)), PiBundle.message("skills.add"))
            }
        }
    }

    // ----------------------------------------------------------------- packages

    private fun reloadPackages() = pooled {
        val installed = PackagesService.listInstalled(project?.basePath)
        val counts = PackagesService.summarize(installed)
        post(
            "packages",
            "items" to installed.map {
                mapOf("source" to it.source, "name" to it.displayName, "scope" to it.scope.name)
            },
            "summary" to PiBundle.message(
                "plugins.summary", counts.packages, counts.extensions, counts.skills, counts.prompts, counts.themes,
            ),
        )
    }

    private fun searchPackages(query: String) {
        status("plugins", PiBundle.message("plugins.searching"), busy = true)
        pooled {
            runCatching { PackagesRegistry.search(query) }
                .onSuccess { found ->
                    post(
                        "packageResults",
                        "items" to found.map {
                            mapOf(
                                "name" to it.name,
                                "description" to it.description,
                                "types" to it.types,
                                "downloads" to it.downloadsLabel(),
                                "source" to it.installSource,
                            )
                        },
                    )
                    status("plugins", if (found.isEmpty()) PiBundle.message("plugins.search.none") else PiBundle.message("plugins.search.count", found.size))
                }
                .onFailure { status("plugins", PiBundle.message("plugins.search.failed", it.message ?: ""), error = true) }
        }
    }

    private fun installPackage(source: String, scopeName: String) {
        if (source.isBlank()) {
            status("plugins", PiBundle.message("plugins.source.required"), error = true)
            return
        }
        val scope = if (scopeName == PackageScope.PROJECT.name) PackageScope.PROJECT else PackageScope.GLOBAL
        val target = if (scope == PackageScope.PROJECT) project?.basePath ?: return else PiBundle.message("plugins.scope.global")
        if (!confirm(PiBundle.message("plugins.install.confirm", source, target), PiBundle.message("plugins.add"))) return
        status("plugins", PiBundle.message("plugins.installing", source), busy = true)
        pooled {
            val result = PackagesService.install(source, scope, project?.basePath)
            if (result.success) {
                status("plugins", PiBundle.message("plugins.install.done", source))
                reloadPackages()
            } else {
                status("plugins", PiBundle.message("plugins.install.failedShort"), error = true)
                showError(PiBundle.message("plugins.install.failed", result.output.takeLast(600)), PiBundle.message("plugins.add"))
            }
        }
    }

    private fun removePackage(source: String, scopeName: String) {
        val scope = runCatching { PackageScope.valueOf(scopeName) }.getOrDefault(PackageScope.GLOBAL)
        if (!confirm(PiBundle.message("plugins.remove.confirm", source), PiBundle.message("plugins.remove"), warning = true)) return
        status("plugins", PiBundle.message("plugins.removing", source), busy = true)
        pooled {
            val result = PackagesService.remove(source, scope, project?.basePath)
            if (result.success) {
                status("plugins", "")
                reloadPackages()
            }
            else {
                status("plugins", PiBundle.message("plugins.remove.failed", result.output.takeLast(600)), error = true)
                showError(PiBundle.message("plugins.remove.failed", result.output.takeLast(600)), PiBundle.message("plugins.remove"))
            }
        }
    }

    // ------------------------------------------------------------------ events

    private fun pushTheme() = post("theme", "vars" to WebTheme.variables())

    private fun pushStrings() = post(
        "i18n",
        "strings" to SETTINGS_KEYS.associateWith { PiBundle.message(it) } + mapOf(
            "projectAvailable" to (project?.basePath != null).toString(),
        ),
    )

    private fun pushState() {
        post(
            "settings",
            "value" to mapOf(
                "theme" to draft.theme.name,
                "language" to draft.language.tag,
                "showThinking" to draft.showThinking,
                "expandThinking" to draft.expandThinking,
                "expandToolCalls" to draft.expandToolCalls,
                "sendOnEnter" to draft.sendOnEnter,
                "fontSize" to draft.fontSize,
                "piPath" to draft.piPath,
                "extraArgs" to draft.extraArgs,
                "commitLanguage" to draft.commitLanguage.name,
                "commitPrompt" to draft.commitPrompt,
                "commitPromptMax" to PiSettings.MAX_COMMIT_PROMPT_LENGTH,
                "fontMin" to PiSettings.MIN_FONT_SIZE,
                "fontMax" to PiSettings.MAX_FONT_SIZE,
                "detected" to detectedPi,
                "detecting" to detectingPi,
                "projectAvailable" to (project?.basePath != null),
                "embedded" to embedded,
            ),
        )
    }

    private fun status(area: String, message: String, busy: Boolean = false, error: Boolean = false) =
        post("status", "area" to area, "message" to message, "busy" to busy, "error" to error)

    private fun post(type: String, vararg values: Pair<String, Any?>) {
        if (disposed) return
        val event = mapOf("type" to type, *values)
        if (ApplicationManager.getApplication().isDispatchThread) {
            view.post(event)
        } else {
            ApplicationManager.getApplication().invokeLater({
                if (!disposed) view.post(event)
            }, ModalityState.any())
        }
    }

    private fun pooled(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!disposed) block()
        }
    }

    private fun confirm(message: String, title: String, warning: Boolean = false): Boolean =
        Messages.showYesNoDialog(component, message, title, if (warning) Messages.getWarningIcon() else Messages.getQuestionIcon()) == Messages.YES

    private fun showError(message: String, title: String) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed) Messages.showErrorDialog(component, message, title)
        }, ModalityState.any())
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Disposer.dispose(view)
    }

    private data class Draft(
        val theme: ThemeMode,
        val language: UiLanguage,
        val showThinking: Boolean,
        val expandThinking: Boolean,
        val expandToolCalls: Boolean,
        val sendOnEnter: Boolean,
        val fontSize: Int,
        val piPath: String,
        val extraArgs: String,
        val commitLanguage: CommitLanguage,
        val commitPrompt: String,
    ) {
        companion object {
            fun read(): Draft {
                val s = PiSettings.getInstance()
                return Draft(
                    s.themeMode, s.language, s.showThinking, s.expandThinking,
                    s.autoExpandToolCalls, s.sendOnEnter,
                    if (s.chatFontSize > 0) s.chatFontSize else PiTheme.defaultFontSize(),
                    s.piPath, s.extraArgs, s.commitLanguage, s.commitPrompt,
                )
            }

            fun defaults() = Draft(
                ThemeMode.SYSTEM, UiLanguage.SIMPLIFIED_CHINESE,
                showThinking = true, expandThinking = false, expandToolCalls = false,
                sendOnEnter = true, fontSize = PiTheme.defaultFontSize(), piPath = "", extraArgs = "",
                commitLanguage = CommitLanguage.CHINESE,
                commitPrompt = PiSettings.DEFAULT_COMMIT_PROMPT,
            )
        }
    }

    companion object {
        internal val HANDLED_MESSAGES = setOf(
            "ready", "closeSettings", "updateDraft", "resetDraft", "resetCommitPrompt", "choosePi", "importProvidersAuto",
            "importProvidersDb", "enableProvider", "saveProvider", "deleteProvider", "toggleSkill",
            "searchSkills", "installSkill", "searchPackages", "installPackage", "removePackage",
            "refreshPackages", "openPackages",
        )

        private val SETTINGS_KEYS = listOf(
            "settings.title", "settings.tab.general", "settings.tab.providers", "settings.tab.skills",
            "settings.tab.plugins", "settings.tab.commitAi", "settings.tab.cli", "settings.appearance", "settings.appearance.system",
            "settings.general.description", "settings.appearance.description", "settings.appearance.light",
            "settings.appearance.dark", "settings.conversation", "settings.conversation.description",
            "settings.showThinking", "settings.expandThinking", "settings.expandToolCalls",
            "settings.sendOnEnter", "settings.fontSize", "settings.fontSize.description", "settings.reset", "settings.language",
            "settings.language.description",
            "settings.language.en", "settings.language.zhCN", "settings.language.zhTW",
            "settings.cli.description", "settings.cli.path", "settings.cli.extraArgs", "settings.cli.extraArgs.hint",
            "settings.cli.notFound", "settings.detected", "settings.detecting", "settings.choose", "settings.save", "settings.cancel",
            "settings.saved", "settings.autoSaved", "settings.backToChat", "settings.installed",
            "settings.providers.description", "settings.skills.description",
            "settings.plugins.description", "providers.claudeSection", "providers.codexSection",
            "settings.commitAi.description", "settings.commitAi.generation", "settings.commitAi.model",
            "settings.commitAi.followChatModel", "settings.commitAi.language", "settings.commitAi.language.zh",
            "settings.commitAi.language.en", "settings.commitAi.prompt", "settings.commitAi.promptHint",
            "settings.commitAi.restoreDefault", "settings.commitAi.privacy",
            "providers.import.auto", "providers.import.db", "providers.empty.hint", "providers.enable",
            "providers.edit", "providers.delete", "providers.current", "providers.dialog.title",
            "providers.name", "providers.baseUrl", "providers.apiKey", "providers.models",
            "skills.empty", "skills.add", "skills.selectHint", "skills.name", "skills.description",
            "skills.scope.global", "skills.scope.project", "skills.search", "skills.search.placeholder",
            "skills.search.hint", "skills.install.global", "skills.install.project", "skills.install.noProject",
            "plugins.empty", "plugins.add", "plugins.location", "plugins.source", "plugins.examples",
            "plugins.scope.global", "plugins.scope.project", "plugins.noProject", "plugins.install",
            "plugins.remove", "plugins.search", "plugins.search.action", "plugins.search.placeholder",
            "plugins.search.hint", "plugins.refresh",
        )
    }
}
