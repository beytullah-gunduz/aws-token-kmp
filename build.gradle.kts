plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    // Group + version are picked up by `project.version` everywhere. The
    // version is sourced from the `-Papp.version=` flag passed by the
    // release workflow (which extracts it from the git tag). For local
    // dev runs the placeholder `0.0.0` from gradle.properties is used.
    group = "fr.gunduz.awstoken"
    version =
        findProperty("app.version")
            ?.toString()
            ?.removeSuffix("-SNAPSHOT") ?: "0.0.0"

    apply(
        plugin =
            rootProject.libs.plugins.spotless
                .get()
                .pluginId,
    )

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**", "**/generated/**")
            ktlint(
                rootProject.libs.versions.ktlint
                    .get(),
            ).editorConfigOverride(
                mapOf(
                    "ktlint_standard_no-wildcard-imports" to "enabled",
                    "ktlint_standard_function-naming" to "disabled",
                    "ktlint_standard_filename" to "disabled",
                    "ktlint_standard_property-naming" to "disabled",
                    "ktlint_standard_backing-property-naming" to "disabled",
                ),
            )
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(
                rootProject.libs.versions.ktlint
                    .get(),
            )
        }
    }
}
