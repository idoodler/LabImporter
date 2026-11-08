package dev.idoodler.labimporter.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Saves every screen in every condition as `<screen>-<condition>.png`, as evidence
 * for a person to look at. Only with `-PqualityScreens=<folder>`, so normal runs stay fast.
 * A capture that is blank or nearly one colour fails the test: it is no evidence.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class ScreenMatrixTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(condition: String, dark: Boolean = false) {
        val folder = System.getProperty("quality.screens")
        assumeTrue("quality.screens isn't set", !folder.isNullOrEmpty())
        val output = File(checkNotNull(folder)).apply { mkdirs() }
        var index by mutableStateOf(0)
        compose.setContent {
            key(index) { LabImporterTheme(darkTheme = dark, dynamicColor = false) { qualityScreens[index].content() } }
        }
        qualityScreens.indices.forEach { position ->
            index = position
            compose.awaitLoaded(qualityScreens[position])
            val name = "${qualityScreens[position].name}-$condition"
            val image = drawnCapture(name)
            File(output, "$name.png").outputStream().use { stream -> image.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        }
    }

    /**
     * The root as drawn. The first screen of a run was once captured before its first frame
     * was drawn — only the host window's background (#FAFAFA) — so a blank capture is taken
     * again a few frames later, and one that stays blank fails.
     */
    private fun drawnCapture(name: String): Bitmap {
        var image = compose.onRoot().captureToImage().asAndroidBitmap()
        repeat(BLANK_RETRIES) {
            if (!isBlank(image)) return image
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            image = compose.onRoot().captureToImage().asAndroidBitmap()
        }
        assertFalse("$name is blank: %.1f%% of it is one colour".format(dominantColourShare(image) * 100), isBlank(image))
        return image
    }

    @Test
    fun aFlatPictureIsBlankAndAScreenIsNot() {
        val flat = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFAFAFA.toInt()) }
        assertTrue(isBlank(flat))
        // A line of text on a plain background is still nearly one colour.
        Canvas(flat).drawRect(10f, 10f, 190f, 20f, Paint().apply { color = Color.BLACK })
        assertTrue(isBlank(flat))
        // A card on the background, as the smallest screens of the matrix draw.
        Canvas(flat).drawRect(10f, 30f, 190f, 120f, Paint().apply { color = Color.LTGRAY })
        assertFalse(isBlank(flat))
        val transparent = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        assertTrue(isBlank(transparent))
    }

    // The window is taller than a phone, so one picture shows what a person scrolls through.
    @Test
    @Config(qualifiers = "w411dp-h1600dp")
    fun light() = capture("light")

    @Test
    @Config(qualifiers = "w411dp-h1600dp-night")
    fun dark() = capture("dark", dark = true)

    @Test
    @Config(qualifiers = "w411dp-h2600dp", fontScale = 2f)
    fun largestText() = capture("largest-text")

    @Test
    @Config(qualifiers = "de-rDE-w411dp-h1600dp")
    fun german() = capture("german")

    private companion object {
        const val BLANK_RETRIES = 10

        /** More than this share in one colour is a blank capture; the plainest screen of the matrix has 85%. */
        const val BLANK_SHARE = 0.95

        fun isBlank(image: Bitmap): Boolean = dominantColourShare(image) > BLANK_SHARE

        /** The share of [image] (every 4th pixel each way) in its most common colour, at 4 bits a channel. */
        fun dominantColourShare(image: Bitmap): Double {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val counts = HashMap<Int, Int>()
            var total = 0
            for (y in 0 until image.height step 4) {
                for (x in 0 until image.width step 4) {
                    val pixel = pixels[y * image.width + x]
                    val colour = (pixel ushr 4) and 0x0F0F0F0F
                    counts[colour] = (counts[colour] ?: 0) + 1
                    total++
                }
            }
            return (counts.values.maxOrNull() ?: 0).toDouble() / total.coerceAtLeast(1)
        }
    }
}
