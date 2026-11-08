package dev.idoodler.labimporter.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Saves every screen in every condition as `<screen>-<condition>.png`, as evidence
 * for a person to look at. Only with `-PqualityScreens=<folder>`, so normal runs stay fast.
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
            val file = File(output, "${qualityScreens[position].name}-$condition.png")
            file.outputStream().use { stream ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
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
}
