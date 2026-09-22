import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "dev.pi"
version = "1.0.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Bundled into the plugin: small, and avoids relying on platform internals.
    implementation("com.google.code.gson:gson:2.10.1")
    // Reads cc-switch's SQLite store (~/.cc-switch/cc-switch.db) for provider import.
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.pi.pigui"
        name = "Pi GUI"
        version = project.version.toString()
        description = """
            <p>A JetBrains UI for the <b>pi</b> AI coding agent. Not affiliated with
            JetBrains or with the authors of pi.</p>

            <p>Chat with pi inside the IDE. Conversation and settings surfaces use the IDE's
            bundled JCEF runtime, with no local web server or remote UI origin. Native IDE APIs
            still provide file pickers, diffs, confirmations and project integration.</p>

            <h4>What it does</h4>
            <ul>
              <li><b>Sessions</b> &mdash; every pi session recorded for the current project, read
                  straight from disk, so history opens without starting an agent.</li>
              <li><b>Streaming chat</b> &mdash; assistant text, thinking blocks and tool calls
                  arrive live, each collapsible so the conversation stays readable.</li>
              <li><b>Slash commands</b> &mdash; type <code>/</code> to complete pi's built-ins
                  plus your own extension commands, prompt templates and skills.</li>
              <li><b>Edits</b> &mdash; the files a conversation actually changed, with per-file
                  line counts; click one to open the IDE's own diff viewer.</li>
              <li><b>Attachments</b> &mdash; drag, paste or pick files; images go to the model
                  inline, anything else is referenced by path so it stays out of the context
                  window.</li>
              <li><b>Context meter</b> &mdash; how much of the context window the session
                  occupies, with one-click compaction.</li>
              <li><b>Providers, models and thinking level</b> &mdash; switched from the composer.</li>
              <li><b>Send file path</b> &mdash; right-click any file, folder or selection to drop
                  it into the composer as an <code>@mention</code>.</li>
            </ul>

            <p>Available in English, Simplified Chinese and Traditional Chinese.</p>

            <h4>AI credential access and privacy</h4>
            <p>Pi GUI does not read cc-switch or imported AI credentials until you explicitly
            allow it in a permission dialog. The dialog appears before the first credential
            import and identifies the files, credential names and purpose of the access.</p>
            <ul>
              <li><b>Sources:</b> <code>~/.cc-switch/cc-switch.db</code>,
                  <code>~/.cc-switch/config.json</code>, a <code>cc-switch.db</code> file you
                  select, and the specifically named AI environment variables below.</li>
              <li><b>Values:</b> <code>ANTHROPIC_AUTH_TOKEN</code>,
                  <code>ANTHROPIC_API_KEY</code>, <code>OPENAI_API_KEY</code>, provider base URLs
                  and model names. Pi GUI never reads the complete environment.</li>
              <li><b>Purpose:</b> copy the selected provider configuration to local Pi files
                  (<code>~/.pi/agent/providers.import.json</code> and managed entries in
                  <code>~/.pi/agent/models.json</code>) so the locally installed pi CLI can
                  authenticate with the selected AI provider.</li>
              <li><b>Handling:</b> credentials remain on your computer. Pi GUI does not send them
                  to the plugin developer and does not collect telemetry. When used, the local pi
                  CLI sends a credential only to the AI provider you selected.</li>
            </ul>
            <p>If permission is denied, Pi GUI does not read these credential files or AI
            environment variables, and provider import remains disabled.</p>

            <h4>Requirements</h4>
            <p>The <code>pi</code> CLI must be installed and authenticated on your machine; this
            plugin drives it, it does not bundle or replace it:</p>
            <p><code>npm i -g @earendil-works/pi-coding-agent</code></p>
        """.trimIndent()
        changeNotes = """
            <h4>1.0.3</h4>
            <p>Improves file and folder references in the chat composer.</p>
            <ul>
              <li>Show files, folders, and selected line ranges as compact attachment chips.</li>
              <li>Keep raw file paths out of the visible composer.</li>
              <li>Preserve the user's caret and selection when sending a path to Pi GUI.</li>
            </ul>
            <h4>1.0.2</h4>
            <p>Adds explicit, fail-closed permission handling for local AI credentials.</p>
            <ul>
              <li>Ask for permission before reading cc-switch credential files.</li>
              <li>Explain exactly which files and credential fields are accessed and why.</li>
              <li>Keep stored API keys out of the JCEF page and preserve them on blank edits.</li>
              <li>Document credential access and privacy in the Marketplace description.</li>
              <li>Fix skills.sh global/project installation and smooth search-result rendering.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
        vendor {
            // TODO before publishing: Marketplace requires a real contact email.
            name = providers.gradleProperty("pluginVendorName").getOrElse("pi-gui")
            email = providers.gradleProperty("pluginVendorEmail").getOrElse("")
            url = providers.gradleProperty("pluginVendorUrl").getOrElse("")
        }
    }

    signing {
        // Supplied via environment variables in CI; absent locally, which is fine until you sign.
        certificateChainFile.fileProvider(
            providers.environmentVariable("PI_CERTIFICATE_CHAIN").map { File(it) }
        )
        privateKeyFile.fileProvider(
            providers.environmentVariable("PI_PRIVATE_KEY").map { File(it) }
        )
        password = providers.environmentVariable("PI_PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PI_PUBLISH_TOKEN")
        // "default" is the public stable channel; use e.g. "eap" for a pre-release channel.
        channels = providers.gradleProperty("pluginChannel").map { listOf(it) }.orElse(listOf("default"))
    }
    buildSearchableOptions = false

    pluginVerification {
        ides {
            // Machine-independent verification, so it runs identically for anyone on any OS
            // (the old setup probed a locally installed IDE path that only existed on one
            // Mac and silently verified nothing elsewhere):
            //   - the floor of the supported range (sinceBuild = 243), which is also the
            //     build target and already in the Gradle cache, and
            //   - recommended(), the newest stable release the verifier suggests for the
            //     range — catches regressions on IDEs newer than the build target.
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.1")
            recommended()
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

/**
 * Fail fast rather than having Marketplace review bounce the submission days later.
 * A missing vendor email is the single most common rejection reason for a first upload.
 */
tasks.named("publishPlugin") {
    doFirst {
        val email = providers.gradleProperty("pluginVendorEmail").getOrElse("")
        check(email.isNotBlank()) {
            "pluginVendorEmail is empty in gradle.properties — JetBrains Marketplace requires a " +
                "public contact email for the vendor."
        }
        check(!providers.environmentVariable("PI_PUBLISH_TOKEN").orNull.isNullOrBlank()) {
            "PI_PUBLISH_TOKEN is not set. Create a token at " +
                "https://plugins.jetbrains.com/author/me/tokens and export it before publishing."
        }
    }
}
