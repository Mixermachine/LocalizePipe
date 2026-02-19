plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

group = "de.aarondietz"
version = "0.0.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.3.2") // Check at https://www.jetbrains.com/idea/download/other/ was is the newest version
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        @Suppress("UnstableApiUsage") // Yes composeUI might change. We accept this
        composeUI()
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }

    pluginVerification {
        ides {
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
