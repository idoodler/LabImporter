package dev.idoodler.labimporter.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.design.MAX_WASH_INTENSITY
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.contentColorOn
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.design.washAlpha
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The text and background pairs of the design tokens, computed (WCAG 2). Category
 * colours are fixed across both platforms, but the surfaces they tint and the text on
 * them follow the wallpaper on Android 12+, where dynamic colour is on by default.
 * Checked in light and dark against the fallback palette and against the system's
 * dynamic palette turned to every hue: a wallpaper changes a role's hue, not its tone.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h914dp")
class DesignContrastTest {
    @get:Rule
    val compose = createComposeRule()

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private class Scheme(val name: String, val scheme: ColorScheme, val tints: Map<Pair<LabCategory, Float>, Color>, val dark: Boolean)

    /** Every strength a card, chip, pill or top bar tints with. */
    private val strengths = listOf(0.10f, 0.12f, 0.14f, 0.16f, 0.18f, 0.22f, 0.35f)

    /** Wallpapers of every hue: the system's palette turned this far round the colour wheel. */
    private val wallpaperHues = listOf(0f, 60f, 120f, 180f, 240f, 300f)

    private fun schemes(): List<Scheme> {
        val found = mutableListOf<Scheme>()
        compose.setContent {
            listOf(false, true).forEach { dynamic ->
                listOf(false, true).forEach { dark ->
                    LabImporterTheme(darkTheme = dark, dynamicColor = dynamic) {
                        val system = MaterialTheme.colorScheme
                        val variants = if (dynamic) {
                            wallpaperHues.map { "dynamic ${if (dark) "dark" else "light"} +${it.toInt()}°" to system.withHueTurned(it) }
                        } else {
                            listOf((if (dark) "dark" else "light") to system)
                        }
                        variants.forEach { (name, scheme) ->
                            MaterialTheme(colorScheme = scheme) {
                                val tints = buildMap {
                                    LabCategory.entries.forEach { category ->
                                        strengths.forEach { put(category to it, tintedSurface(category.color, it)) }
                                    }
                                }
                                found += Scheme(name, scheme, tints, dark)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        check(found.any { it.name.startsWith("dynamic") }) { "No dynamic palette: Robolectric runs below Android 12" }
        return found
    }

    /** The roles text and tints use, at another hue: dynamic colour keeps each role's tone, so its luminance stays. */
    private fun ColorScheme.withHueTurned(degrees: Float) = copy(
        primary = primary.withHueTurned(degrees),
        onSurface = onSurface.withHueTurned(degrees),
        onSurfaceVariant = onSurfaceVariant.withHueTurned(degrees),
        surface = surface.withHueTurned(degrees),
        surfaceContainerLow = surfaceContainerLow.withHueTurned(degrees),
    )

    private fun Color.withHueTurned(degrees: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(toArgb(), hsl)
        hsl[0] = (hsl[0] + degrees) % 360f
        val target = luminance()
        var low = 0f
        var high = 1f
        repeat(24) {
            hsl[2] = (low + high) / 2
            if (Color(ColorUtils.HSLToColor(hsl)).luminance() < target) low = hsl[2] else high = hsl[2]
        }
        return Color(ColorUtils.HSLToColor(hsl))
    }

    private fun failures(pairs: Sequence<Triple<String, Color, Color>>, minimum: Float) =
        pairs.map { (name, a, b) -> Triple(name, contrast(a, b), minimum) }
            .filter { it.second < it.third }
            .map { (name, ratio, _) -> "$name: ${"%.2f".format(ratio)}:1" }
            .toList()

    @Test
    fun textOnACategoryColourReachesAA() {
        val pairs = LabCategory.entries.asSequence().map { Triple(it.name, contentColorOn(it.color), it.color) }
        assertEquals(emptyList<String>(), failures(pairs, 4.5f))
    }

    @Test
    fun theGlyphOnABadgesDarkerEndStaysVisible() {
        // Badges fade 18 % toward black. Icons need 3:1 (WCAG 1.4.11), and one colour serves the whole gradient.
        val pairs = LabCategory.entries.asSequence().map {
            Triple(it.name, contentColorOn(it.color), androidx.compose.ui.graphics.lerp(it.color, Color.Black, 0.18f))
        }
        assertEquals(emptyList<String>(), failures(pairs, 3f))
    }

    @Test
    fun textOnATintedSurfaceReachesAA() {
        val pairs = schemes().asSequence().flatMap { scheme ->
            scheme.tints.asSequence().flatMap { (key, tint) ->
                val (category, strength) = key
                sequenceOf(
                    Triple("${scheme.name} onSurface on ${category.name} @$strength", scheme.scheme.onSurface, tint),
                    Triple("${scheme.name} onSurfaceVariant on ${category.name} @$strength", scheme.scheme.onSurfaceVariant, tint),
                )
            }
        }
        assertEquals(emptyList<String>(), failures(pairs, 4.5f))
    }

    @Test
    fun textOnAWashReachesAA() {
        val pairs = schemes().asSequence().flatMap { scheme ->
            LabCategory.entries.asSequence().flatMap { category ->
                // The brightest spot: the wash at its strongest, under the centre of one glow.
                val spot = category.color.copy(alpha = washAlpha(scheme.dark) * MAX_WASH_INTENSITY).compositeOver(scheme.scheme.surface)
                sequenceOf(
                    Triple("${scheme.name} onSurface on ${category.name}", scheme.scheme.onSurface, spot),
                    Triple("${scheme.name} onSurfaceVariant on ${category.name}", scheme.scheme.onSurfaceVariant, spot),
                    Triple("${scheme.name} primary on ${category.name}", scheme.scheme.primary, spot),
                )
            }
        }
        assertEquals(emptyList<String>(), failures(pairs, 4.5f))
    }
}
