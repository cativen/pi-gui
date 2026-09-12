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
            <p>Native JetBrains UI for the <b>pi</b> AI coding agent.</p>
            <p>Chat with pi directly inside the IDE: browse and resume sessions, watch streaming
            responses, inspect tool calls, and switch models &mdash; all rendered with native
            IntelliJ components (no embedded browser).</p>
            <p>Requires the <code>pi</code> CLI on your machine:
            <code>npm i -g @earendil-works/pi-coding-agent</code></p>
        """.trimIndent()
        changeNotes = """
            <h4>1.0.0</h4>
            <ul>
              <li>Native chat tool window for the pi coding agent &mdash; no embedded browser.</li>
              <li>Browse and resume sessions recorded for the current project.</li>
              <li>Streaming responses with collapsible thinking blocks, tool calls and results.</li>
              <li>Markdown rendering with IDE syntax highlighting for fenced code.</li>
              <li>Model and thinking-level switching; steering while a run is in flight.</li>
              <li>"Send File Path to Pi GUI" from the project view, editor and editor tabs.</li>
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
            // Verify against the locally installed IDE, which is newer than the build target.
            val localIde = file("/Applications/IntelliJ IDEA.app")
            if (localIde.exists()) local(localIde.absolutePath)
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
