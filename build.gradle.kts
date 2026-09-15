import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "dev.pi"
version = "1.0.0"

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

            <h4>Requirements</h4>
            <p>The <code>pi</code> CLI must be installed and authenticated on your machine; this
            plugin drives it, it does not bundle or replace it:</p>
            <p><code>npm i -g @earendil-works/pi-coding-agent</code></p>
        """.trimIndent()
        changeNotes = """
            <h4>1.0.0</h4>
            <p>First public release.</p>
            <ul>
              <li>Responsive JCEF chat and settings surfaces hosted inside the IDE.</li>
              <li>Browse, resume and delete the sessions recorded for the current project.</li>
              <li>Streaming responses with collapsible thinking blocks, tool calls and results.</li>
              <li>Markdown rendering with IDE syntax highlighting for fenced code.</li>
              <li><code>/</code> command completion: pi's built-ins, extension commands, prompt
                  templates and skills.</li>
              <li>Edits panel listing the files a conversation changed, opening the IDE diff viewer.</li>
              <li>Attachments by drag, paste or file picker, including inline images.</li>
              <li>Context-usage meter with on-demand compaction.</li>
              <li>Provider, model and thinking-level switching; steering while a run is in flight.</li>
              <li>Skills and packages management from the settings dialog.</li>
              <li>"Send File Path to Pi GUI" from the project view, editor, tabs and navigation bar.</li>
              <li>English, Simplified Chinese and Traditional Chinese.</li>
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
