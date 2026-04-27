package fr.gunduz.awstoken.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-inlined Material Design icons. This object exists so the app does
 * NOT depend on `compose.materialIconsExtended` (~10 MB of vector metadata
 * that resists ProGuard shrinking) or `compose.materialIconsCore` (the
 * JetBrains multiplatform build is stuck at 1.7.3 and doesn't match the
 * 1.11 Compose Multiplatform line this project runs on).
 *
 * Every icon the app uses is inlined here, with path data copied verbatim
 * from the corresponding androidx Material icons source file (Apache-2.0,
 * same licence as the rest of Compose — no new attribution requirements).
 * The helpers [appMaterialIcon] and [appMaterialPath] below are trivial
 * re-implementations of the private `materialIcon`/`materialPath` DSL
 * used inside androidx's own icon files, so each path block below reads
 * identically to its upstream form and is easy to diff against the source.
 *
 * To add a new icon: pull the `materialIcon(name = "…") { materialPath { … } }`
 * block from the `material-icons-extended-android-<version>-sources.jar`
 * in the Gradle cache, copy it into a new `val` on this object, and done.
 * The `ImageVector` primitive is in `compose.ui` so no extra dependency
 * is needed.
 */
object AppIcons {
    val Add: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Add") {
            appMaterialPath {
                moveTo(19.0f, 13.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val ArrowBack: ImageVector by lazy {
        appMaterialIcon(name = "AutoMirrored.Outlined.ArrowBack", autoMirror = true) {
            appMaterialPath {
                moveTo(20.0f, 11.0f)
                horizontalLineTo(7.83f)
                lineToRelative(5.59f, -5.59f)
                lineTo(12.0f, 4.0f)
                lineToRelative(-8.0f, 8.0f)
                lineToRelative(8.0f, 8.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(7.83f, 13.0f)
                horizontalLineTo(20.0f)
                verticalLineToRelative(-2.0f)
                close()
            }
        }
    }

    val Check: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Check") {
            appMaterialPath {
                moveTo(9.0f, 16.17f)
                lineTo(4.83f, 12.0f)
                lineToRelative(-1.42f, 1.41f)
                lineTo(9.0f, 19.0f)
                lineTo(21.0f, 7.0f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(9.0f, 16.17f)
                close()
            }
        }
    }

    val Close: ImageVector by lazy {
        appMaterialIcon(name = "Filled.Close") {
            appMaterialPath {
                moveTo(19.0f, 6.41f)
                lineTo(17.59f, 5.0f)
                lineTo(12.0f, 10.59f)
                lineTo(6.41f, 5.0f)
                lineTo(5.0f, 6.41f)
                lineTo(10.59f, 12.0f)
                lineTo(5.0f, 17.59f)
                lineTo(6.41f, 19.0f)
                lineTo(12.0f, 13.41f)
                lineTo(17.59f, 19.0f)
                lineTo(19.0f, 17.59f)
                lineTo(13.41f, 12.0f)
                close()
            }
        }
    }

    val Delete: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Delete") {
            appMaterialPath {
                moveTo(16.0f, 9.0f)
                verticalLineToRelative(10.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(8.0f)
                moveToRelative(-1.5f, -6.0f)
                horizontalLineToRelative(-5.0f)
                lineToRelative(-1.0f, 1.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-3.5f)
                lineToRelative(-1.0f, -1.0f)
                close()
                moveTo(18.0f, 7.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(7.0f)
                close()
            }
        }
    }

    val Download: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Download") {
            appMaterialPath {
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(15.0f, 3.0f)
                lineTo(9.0f, 3.0f)
                verticalLineToRelative(6.0f)
                lineTo(5.0f, 9.0f)
                lineToRelative(7.0f, 7.0f)
                lineToRelative(7.0f, -7.0f)
                close()
                moveTo(11.0f, 11.0f)
                lineTo(11.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(1.17f)
                lineTo(12.0f, 13.17f)
                lineTo(9.83f, 11.0f)
                lineTo(11.0f, 11.0f)
                close()
                moveTo(5.0f, 18.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                lineTo(5.0f, 20.0f)
                close()
            }
        }
    }

    val Edit: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Edit") {
            appMaterialPath {
                moveTo(14.06f, 9.02f)
                lineToRelative(0.92f, 0.92f)
                lineTo(5.92f, 19.0f)
                lineTo(5.0f, 19.0f)
                verticalLineToRelative(-0.92f)
                lineToRelative(9.06f, -9.06f)
                moveTo(17.66f, 3.0f)
                curveToRelative(-0.25f, 0.0f, -0.51f, 0.1f, -0.7f, 0.29f)
                lineToRelative(-1.83f, 1.83f)
                lineToRelative(3.75f, 3.75f)
                lineToRelative(1.83f, -1.83f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineToRelative(-2.34f, -2.34f)
                curveToRelative(-0.2f, -0.2f, -0.45f, -0.29f, -0.71f, -0.29f)
                close()
                moveTo(14.06f, 6.19f)
                lineTo(3.0f, 17.25f)
                lineTo(3.0f, 21.0f)
                horizontalLineToRelative(3.75f)
                lineTo(17.81f, 9.94f)
                lineToRelative(-3.75f, -3.75f)
                close()
            }
        }
    }

    val Minimize: ImageVector by lazy {
        appMaterialIcon(name = "Filled.Minimize") {
            appMaterialPath {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(6.0f)
                close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Refresh") {
            appMaterialPath {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
                reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
                curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
                horizontalLineToRelative(-2.08f)
                curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
                lineTo(13.0f, 11.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(4.0f)
                lineToRelative(-2.35f, 2.35f)
                close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.Settings") {
            appMaterialPath {
                moveTo(19.43f, 12.98f)
                curveToRelative(0.04f, -0.32f, 0.07f, -0.64f, 0.07f, -0.98f)
                curveToRelative(0.0f, -0.34f, -0.03f, -0.66f, -0.07f, -0.98f)
                lineToRelative(2.11f, -1.65f)
                curveToRelative(0.19f, -0.15f, 0.24f, -0.42f, 0.12f, -0.64f)
                lineToRelative(-2.0f, -3.46f)
                curveToRelative(-0.09f, -0.16f, -0.26f, -0.25f, -0.44f, -0.25f)
                curveToRelative(-0.06f, 0.0f, -0.12f, 0.01f, -0.17f, 0.03f)
                lineToRelative(-2.49f, 1.0f)
                curveToRelative(-0.52f, -0.4f, -1.08f, -0.73f, -1.69f, -0.98f)
                lineToRelative(-0.38f, -2.65f)
                curveTo(14.46f, 2.18f, 14.25f, 2.0f, 14.0f, 2.0f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(-0.25f, 0.0f, -0.46f, 0.18f, -0.49f, 0.42f)
                lineToRelative(-0.38f, 2.65f)
                curveToRelative(-0.61f, 0.25f, -1.17f, 0.59f, -1.69f, 0.98f)
                lineToRelative(-2.49f, -1.0f)
                curveToRelative(-0.06f, -0.02f, -0.12f, -0.03f, -0.18f, -0.03f)
                curveToRelative(-0.17f, 0.0f, -0.34f, 0.09f, -0.43f, 0.25f)
                lineToRelative(-2.0f, 3.46f)
                curveToRelative(-0.13f, 0.22f, -0.07f, 0.49f, 0.12f, 0.64f)
                lineToRelative(2.11f, 1.65f)
                curveToRelative(-0.04f, 0.32f, -0.07f, 0.65f, -0.07f, 0.98f)
                curveToRelative(0.0f, 0.33f, 0.03f, 0.66f, 0.07f, 0.98f)
                lineToRelative(-2.11f, 1.65f)
                curveToRelative(-0.19f, 0.15f, -0.24f, 0.42f, -0.12f, 0.64f)
                lineToRelative(2.0f, 3.46f)
                curveToRelative(0.09f, 0.16f, 0.26f, 0.25f, 0.44f, 0.25f)
                curveToRelative(0.06f, 0.0f, 0.12f, -0.01f, 0.17f, -0.03f)
                lineToRelative(2.49f, -1.0f)
                curveToRelative(0.52f, 0.4f, 1.08f, 0.73f, 1.69f, 0.98f)
                lineToRelative(0.38f, 2.65f)
                curveToRelative(0.03f, 0.24f, 0.24f, 0.42f, 0.49f, 0.42f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.25f, 0.0f, 0.46f, -0.18f, 0.49f, -0.42f)
                lineToRelative(0.38f, -2.65f)
                curveToRelative(0.61f, -0.25f, 1.17f, -0.59f, 1.69f, -0.98f)
                lineToRelative(2.49f, 1.0f)
                curveToRelative(0.06f, 0.02f, 0.12f, 0.03f, 0.18f, 0.03f)
                curveToRelative(0.17f, 0.0f, 0.34f, -0.09f, 0.43f, -0.25f)
                lineToRelative(2.0f, -3.46f)
                curveToRelative(0.12f, -0.22f, 0.07f, -0.49f, -0.12f, -0.64f)
                lineToRelative(-2.11f, -1.65f)
                close()
                moveTo(17.45f, 11.27f)
                curveToRelative(0.04f, 0.31f, 0.05f, 0.52f, 0.05f, 0.73f)
                curveToRelative(0.0f, 0.21f, -0.02f, 0.43f, -0.05f, 0.73f)
                lineToRelative(-0.14f, 1.13f)
                lineToRelative(0.89f, 0.7f)
                lineToRelative(1.08f, 0.84f)
                lineToRelative(-0.7f, 1.21f)
                lineToRelative(-1.27f, -0.51f)
                lineToRelative(-1.04f, -0.42f)
                lineToRelative(-0.9f, 0.68f)
                curveToRelative(-0.43f, 0.32f, -0.84f, 0.56f, -1.25f, 0.73f)
                lineToRelative(-1.06f, 0.43f)
                lineToRelative(-0.16f, 1.13f)
                lineToRelative(-0.2f, 1.35f)
                horizontalLineToRelative(-1.4f)
                lineToRelative(-0.19f, -1.35f)
                lineToRelative(-0.16f, -1.13f)
                lineToRelative(-1.06f, -0.43f)
                curveToRelative(-0.43f, -0.18f, -0.83f, -0.41f, -1.23f, -0.71f)
                lineToRelative(-0.91f, -0.7f)
                lineToRelative(-1.06f, 0.43f)
                lineToRelative(-1.27f, 0.51f)
                lineToRelative(-0.7f, -1.21f)
                lineToRelative(1.08f, -0.84f)
                lineToRelative(0.89f, -0.7f)
                lineToRelative(-0.14f, -1.13f)
                curveToRelative(-0.03f, -0.31f, -0.05f, -0.54f, -0.05f, -0.74f)
                reflectiveCurveToRelative(0.02f, -0.43f, 0.05f, -0.73f)
                lineToRelative(0.14f, -1.13f)
                lineToRelative(-0.89f, -0.7f)
                lineToRelative(-1.08f, -0.84f)
                lineToRelative(0.7f, -1.21f)
                lineToRelative(1.27f, 0.51f)
                lineToRelative(1.04f, 0.42f)
                lineToRelative(0.9f, -0.68f)
                curveToRelative(0.43f, -0.32f, 0.84f, -0.56f, 1.25f, -0.73f)
                lineToRelative(1.06f, -0.43f)
                lineToRelative(0.16f, -1.13f)
                lineToRelative(0.2f, -1.35f)
                horizontalLineToRelative(1.39f)
                lineToRelative(0.19f, 1.35f)
                lineToRelative(0.16f, 1.13f)
                lineToRelative(1.06f, 0.43f)
                curveToRelative(0.43f, 0.18f, 0.83f, 0.41f, 1.23f, 0.71f)
                lineToRelative(0.91f, 0.7f)
                lineToRelative(1.06f, -0.43f)
                lineToRelative(1.27f, -0.51f)
                lineToRelative(0.7f, 1.21f)
                lineToRelative(-1.07f, 0.85f)
                lineToRelative(-0.89f, 0.7f)
                lineToRelative(0.14f, 1.13f)
                close()
                moveTo(12.0f, 8.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
                reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                close()
                moveTo(12.0f, 14.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
            }
        }
    }

    val Star: ImageVector by lazy {
        appMaterialIcon(name = "Filled.Star") {
            appMaterialPath {
                moveTo(12.0f, 17.27f)
                lineTo(18.18f, 21.0f)
                lineToRelative(-1.64f, -7.03f)
                lineTo(22.0f, 9.24f)
                lineToRelative(-7.19f, -0.61f)
                lineTo(12.0f, 2.0f)
                lineTo(9.19f, 8.63f)
                lineTo(2.0f, 9.24f)
                lineToRelative(5.46f, 4.73f)
                lineTo(5.82f, 21.0f)
                close()
            }
        }
    }

    val StarBorder: ImageVector by lazy {
        appMaterialIcon(name = "Outlined.StarBorder") {
            appMaterialPath {
                moveTo(22.0f, 9.24f)
                lineToRelative(-7.19f, -0.62f)
                lineTo(12.0f, 2.0f)
                lineTo(9.19f, 8.63f)
                lineTo(2.0f, 9.24f)
                lineToRelative(5.46f, 4.73f)
                lineTo(5.82f, 21.0f)
                lineTo(12.0f, 17.27f)
                lineTo(18.18f, 21.0f)
                lineToRelative(-1.63f, -7.03f)
                lineTo(22.0f, 9.24f)
                close()
                moveTo(12.0f, 15.4f)
                lineToRelative(-3.76f, 2.27f)
                lineToRelative(1.0f, -4.28f)
                lineToRelative(-3.32f, -2.88f)
                lineToRelative(4.38f, -0.38f)
                lineTo(12.0f, 6.1f)
                lineToRelative(1.71f, 4.04f)
                lineToRelative(4.38f, 0.38f)
                lineToRelative(-3.32f, 2.88f)
                lineToRelative(1.0f, 4.28f)
                lineTo(12.0f, 15.4f)
                close()
            }
        }
    }
}

// --- helpers -------------------------------------------------------------

/**
 * Standalone re-implementation of androidx's private `materialIcon` DSL,
 * so each `val` above can read identically to the upstream source file
 * (makes diffing against the canonical path data trivial). Builds a 24×24
 * `ImageVector` in the same viewport Google uses for every Material icon.
 *
 * `autoMirror = true` on [ArrowBack] tells Compose to horizontally flip
 * the drawing in RTL layouts — same behavior as
 * `Icons.AutoMirrored.Outlined.ArrowBack`. All other icons leave it off
 * so they render identically in LTR and RTL.
 */
private fun appMaterialIcon(
    name: String,
    autoMirror: Boolean = false,
    block: ImageVector.Builder.() -> ImageVector.Builder,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).block().build()

/**
 * Mirrors androidx's private `materialPath`: a single solid-black filled
 * path with no stroke. The `Color.Black` fill is a convention — the
 * compose `Icon` composable tints the vector at draw time using
 * `LocalContentColor`, so the literal color here is never seen.
 */
private fun ImageVector.Builder.appMaterialPath(
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector.Builder = path(
    fill = SolidColor(Color.Black),
    pathBuilder = pathBuilder,
)
