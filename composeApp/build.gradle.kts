import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

// Single source of truth for the app version. Driven by `-Papp.version=`
// from the release workflow (extracted from the git tag); falls back to
// the placeholder in `gradle.properties` for local dev. Used both for the
// in-Gradle `project.version` and Compose Desktop's `packageVersion`.
val appVersion: String =
    project
        .findProperty("app.version")
        ?.toString()
        ?.removeSuffix("-SNAPSHOT") ?: "0.0.0"

// Detect once whether we're on a macOS host. macOS-only build steps (the
// .icns generation that calls `iconutil`) are gated on this so Linux and
// Windows runners in the release matrix don't try to invoke a tool that
// doesn't exist there.
val isMacHost: Boolean =
    run {
        val n = System.getProperty("os.name").orEmpty().lowercase()
        n.contains("mac") || n.contains("darwin")
    }

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-Xannotation-default-target=param-property",
        )
    }

    sourceSets {
        val desktopMain by getting

        @Suppress("DEPRECATION")
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.startup.runtime)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }

        @Suppress("DEPRECATION")
        commonMain.dependencies {
            with(compose) {
                implementation(runtime)
                implementation(foundation)
                implementation(material)
                implementation(material3)
                implementation(ui)

                with(components) {
                    implementation(resources)
                    implementation(uiTooling)
                    implementation(uiToolingPreview)
                }
            }

            // `materialIconsExtended` and `materialIconsCore` are both
            // deliberately absent. The app's 12 icons are hand-inlined
            // into `ui/icons/AppIcons.kt` as `ImageVector` constants, so
            // the release DMG ships zero Material-icons metadata (~10 MB
            // saved vs. the Extended artifact). See AppIcons.kt for how
            // to add new icons.
            implementation(libs.androidx.navigation.compose)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.bundles.lifecycle)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)

            implementation(libs.bundles.ktor.client)
            implementation(libs.ksoup)

            implementation(libs.androidx.datastore.core)
            implementation(libs.androidx.datastore.preferences)

            implementation(libs.kermit)
            implementation(kotlin("stdlib-common"))
        }

        desktopMain.dependencies {
            // Pin the macOS Skiko distribution to arm64. `currentOs` would
            // otherwise pull in skiko-awt-runtime-macos-arm64 when building
            // on an Apple Silicon host and macos-x64 on Intel — and some
            // CI setups end up with both architectures in the same bundle.
            // Every Mac sold since ~2020 is Apple Silicon, so hardcoding
            // arm64 saves ~15 MB off the DMG. Intel Mac users would need
            // to flip this back to `currentOs` and rebuild from source.
            // Non-Mac hosts keep `currentOs` so Linux/Windows builds still
            // work unchanged.
            if (isMacHost) {
                // Direct Maven coordinate because the `compose.desktop.macos_arm64`
                // DSL alias is deprecated in compose-plugin 1.11.
                implementation(libs.jetbrains.compose.desktop.macos.arm64)
            } else {
                implementation(compose.desktop.currentOs)
            }
            implementation(kotlin("stdlib"))
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.swing)
            // JNA for calling into the macOS Objective-C runtime (libobjc)
            // to toggle NSApplication's activation policy at runtime — used
            // to hide the Dock icon when the window is hidden to the tray.
            implementation(libs.jna)
        }
    }
}

android {
    namespace = "fr.gunduz.awstoken"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ---------------------------------------------------------------------------
// Generated macOS app icon.
// Renders the same AWS-orange rounded-square "AWS" mark used by the tray at
// every required .iconset size (16 / 32 / 128 / 256 / 512 plus @2x retina
// variants up to 1024) and pipes them through `iconutil -c icns` to produce
// a proper multi-resolution .icns that macOS will show in the Dock, Finder,
// Launchpad, and Cmd+Tab.
// ---------------------------------------------------------------------------
val generatedIconsDir = layout.buildDirectory.dir("generated-icons")
val generatedIcnsFile = generatedIconsDir.map { it.file("aws-token.icns") }

val generateMacIcon by tasks.registering {
    val outDir = generatedIconsDir
    outputs.dir(outDir)
    doLast {
        val iconsetDir = outDir.get().asFile.resolve("aws-token.iconset")
        iconsetDir.deleteRecursively()
        iconsetDir.mkdirs()

        // Apple's required iconset contents: each base size must be present
        // as both a 1x and an @2x (retina) PNG, where the @2x is drawn at
        // double the pixel resolution.
        val sizes = listOf(16, 32, 128, 256, 512)
        for (size in sizes) {
            ImageIO.write(renderAwsIcon(size), "png", iconsetDir.resolve("icon_${size}x$size.png"))
            ImageIO.write(renderAwsIcon(size * 2), "png", iconsetDir.resolve("icon_${size}x$size@2x.png"))
        }

        val icns = outDir.get().asFile.resolve("aws-token.icns")
        // `project.exec { }` has been removed from task actions in current
        // Gradle; use ProcessBuilder directly. `iconutil` is shipped with
        // every macOS install, so we don't need any library.
        val proc =
            ProcessBuilder(
                "iconutil",
                "-c",
                "icns",
                "-o",
                icns.absolutePath,
                iconsetDir.absolutePath,
            ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw GradleException("iconutil failed (exit $exitCode): $output")
        }
    }
}

/**
 * Paint the AWS-orange rounded square + bold "AWS" wordmark into a square
 * `BufferedImage`. Mirrors the tray-icon rendering in `AwsTokenTray.kt` so
 * the Dock icon and menu-bar icon are visually consistent.
 */
fun renderAwsIcon(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

    // Orange rounded-square background. Corner radius ≈ 22% matches
    // macOS's own app icon radius (the "squircle" look).
    g.color = Color(255, 153, 0)
    val radius = (size * 0.22).toInt()
    g.fillRoundRect(0, 0, size, size, radius, radius)

    // Bold white AWS wordmark, centered.
    g.color = Color.WHITE
    g.stroke = BasicStroke((size * 0.03f).coerceAtLeast(1f))
    g.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.42).toInt())
    val label = "AWS"
    val fm = g.fontMetrics
    val tx = (size - fm.stringWidth(label)) / 2
    val ty = (size - fm.height) / 2 + fm.ascent
    g.drawString(label, tx, ty)

    g.dispose()
    return img
}

// Every compose-desktop distribution task needs the .icns ready before it
// starts — wire the dependency explicitly so CI invocations of
// `packageReleaseDmg` / `createDistributable` / etc. regenerate the icon
// on-demand without the user having to remember a bootstrap step.
//
// Gated on `isMacHost` because `generateMacIcon` shells out to `iconutil`,
// which only ships with macOS. On Linux/Windows runners (used by the
// release matrix to produce .deb / .msi) the icon isn't consumed anyway —
// it's only referenced from `nativeDistributions.macOS.iconFile` — so
// skipping the dependsOn keeps those builds from failing on a missing
// `iconutil` binary.
if (isMacHost) {
    tasks
        .matching { task ->
            task.name.startsWith("package") || task.name.contains("Distributable", ignoreCase = true)
        }.configureEach {
            dependsOn(generateMacIcon)
        }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AwsTokenKmp"
            // Driven by `-Papp.version=` (release tag); falls back to the
            // placeholder in gradle.properties for local dev runs.
            packageVersion = appVersion
            modules("jdk.unsupported")
            macOS {
                bundleID = "fr.gunduz.awstoken"
                iconFile.set(generatedIcnsFile)
            }
        }

        // The release DMG path runs through ProGuard. Our ruleset silences
        // warnings from OkHttp's optional SSL providers (BouncyCastle /
        // Conscrypt / OpenJSSE — not shipped) and preserves JNA's
        // reflection-based Library interfaces so `DockIconVisibility` can
        // still load libobjc at runtime.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "fr.gunduz.awstoken.resources"
    generateResClass = always
}

configurations {
    "desktopRuntimeClasspath" {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
    }
}
