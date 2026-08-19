import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")

        // Deliberately no PythonCore/JavaScript bundled plugin dependency,
        // same reasoning already documented in env-var-missing-companion's
        // build.gradle.kts: Python PSI needs the separate PyCharm/Python
        // plugin (not guaranteed in IntelliJ IDEA Community), and Kotlin's
        // own K1/K2 PSI is bundled but Java's PSI is what's guaranteed
        // across every edition this catalog targets. This plugin's whole
        // detection surface (string concatenation/interpolation building a
        // SQL-shaped string, then passed to a method that looks like it
        // executes SQL) is done as plain-text/regex analysis over the open
        // editor's Document -- same "hand-rolled over plain text" principle
        // already proven by env-var-missing-companion's
        // EnvVarReferenceScanner and dockerfile-layer-size-companion's
        // Dockerfile parser. This also means the inspection runs on ANY
        // file type (.java/.kt/.py) without needing a per-language PSI
        // grammar dependency -- see LocalInspectionTool registered without
        // a `language` filter in plugin.xml.
        bundledPlugin("com.intellij.modules.platform")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 243 = 2024.3, so as not to exclude the real installed base.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    // Same tooling bug as every other Gap Hunter Labs plugin (Gradle 9.5 +
    // IntelliJ Platform Gradle Plugin 2.16 + IDE 2025.2.6.2): the
    // bytecode instrumenter fails with "instrumentIdeaExtensions
    // doesn't support the nested element". Not required for
    // build/test/verifyPlugin.
    instrumentCode = false

    // Catch experimental/internal API usage locally, before Marketplace's
    // own verifier flags it post-upload. Never relax this list without a
    // documented exception (see AUTOMATION_PLAYBOOK.md SS1.5).
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
    }

    // publishPlugin credentials -- token/cert/key read from
    // ~/.gradle/gradle.properties (self-signed cert generated once for
    // the whole catalog, 10-year validity) -- never in this file.
    publishing {
        token.set(providers.gradleProperty("gapHunterLabs.marketplace.token"))
    }

    signing {
        certificateChain.set(providers.gradleProperty("gapHunterLabs.marketplace.certificateChain"))
        privateKey.set(providers.gradleProperty("gapHunterLabs.marketplace.privateKey"))
        password.set(providers.gradleProperty("gapHunterLabs.marketplace.privateKeyPassword"))
    }
}
