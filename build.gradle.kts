import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "de.aarondietz"
version = "0.0.19"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2026.1") // Check at https://www.jetbrains.com/idea/download/other/ what is the newest version
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }

        changeNotes = """
            - Improved language qualifier handling
            - Added possibility to overwrite language qualifier. Helpful for languages with multiple alphabets. Can overwrite to fixed alphabet.
            - Added possibility to provide custom instructions per language.
            - Added possibility to disable translation for language.
            - Improved progress display during translations
            - Resets uncomplete translations with new start of translations (previous failures do not block future translations).
            - Removes duplicate localizePipe tab at the top.
        """.trimIndent().markdownToHtml()
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
            create(IntelliJPlatformType.AndroidStudio, "2026.1.3.6")
            recommended()
        }
    }

    signing {
        providers.environmentVariable("CERTIFICATE_CHAIN_FILE").orNull?.let {
            certificateChainFile = file(it)
        }
        providers.environmentVariable("PRIVATE_KEY_FILE").orNull?.let {
            privateKeyFile = file(it)
        }
        providers.environmentVariable("PRIVATE_KEY_PASSWORD").orNull?.let {
            password = it
        }
    }

    publishing {
        providers.environmentVariable("PUBLISH_TOKEN").orNull?.let {
            token = it
        }
        channels = providers.gradleProperty("pluginChannel").orNull?.let { listOf(it) } ?: listOf("default")
    }
}

val runIdeCustom by intellijPlatformTesting.runIde.registering {
    version = "2026.2"

    task {
        description = "Runs IntelliJ IDEA 2026.1 with the plugin installed."
    }
}

val runAndroidStudio by intellijPlatformTesting.runIde.registering {
    type = IntelliJPlatformType.AndroidStudio
    version = "2026.1.3.6"

    task {
        description = "Runs Android Studio 2026.1 with the plugin installed."
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    named("verifyPluginSignature") {
        dependsOn("signPlugin")
        notCompatibleWithConfigurationCache("Marketplace ZIP signing tasks are not reliably configuration-cache compatible.")
    }

    named("signPlugin") {
        notCompatibleWithConfigurationCache("Marketplace ZIP signing tasks are not reliably configuration-cache compatible.")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

private fun String.markdownToHtml(): String {
    val lines = this.trimIndent().lines()
    val htmlLines = mutableListOf<String>()
    var inList = false

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            if (!inList) {
                htmlLines.add("<ul>")
                inList = true
            }
            val content = trimmed.substring(2).trim()
            htmlLines.add("  <li>$content</li>")
        } else {
            if (inList) {
                htmlLines.add("</ul>")
                inList = false
            }
            if (trimmed.isNotEmpty()) {
                htmlLines.add("<p>$trimmed</p>")
            }
        }
    }
    if (inList) {
        htmlLines.add("</ul>")
    }
    return htmlLines.joinToString("\n")
}
