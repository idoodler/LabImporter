package dev.idoodler.labimporter.ui.design

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A soft wash of category colours behind a screen — the Android counterpart of
 * the iOS `CategoryBackground` / `MorphingCategoryBackground`. Radial glows sit
 * at the corners over the theme surface; with [animated] they slowly drift and
 * cross-fade through the palette (~60 s per cycle), unless the user turned
 * animations off in the system settings.
 */
@Composable
fun CategoryWash(colors: List<Color>, modifier: Modifier = Modifier, animated: Boolean = false, intensity: Float = 1f) {
    val surface = MaterialTheme.colorScheme.surface
    val dark = isDarkScheme
    val alpha = (if (dark) 0.34f else 0.26f) * intensity
    val fallback = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val palette = colors.distinct().ifEmpty { fallback }

    val context = LocalContext.current
    val motionAllowed = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val phase = if (animated && motionAllowed) {
        rememberInfiniteTransition(label = "wash").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        ).value
    } else {
        0f
    }

    Canvas(modifier.fillMaxSize()) {
        drawRect(surface)
        val anchors = listOf(
            Offset(0f, 0f), Offset(size.width, size.height * 0.08f),
            Offset(size.width * 0.1f, size.height * 0.62f), Offset(size.width, size.height),
        )
        val radius = max(size.width, size.height) * 0.62f
        anchors.forEachIndexed { index, anchor ->
            val color = if (animated) cycle(palette, phase * palette.size + index * 0.7f) else palette[index % palette.size]
            val angle = (phase + index * 0.25f) * 2 * PI.toFloat()
            val drift = Offset(cos(angle), sin(angle)) * (size.minDimension * 0.10f)
            val center = anchor + drift
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center, radius),
                radius = radius,
                center = center,
            )
        }
    }
}

private fun cycle(palette: List<Color>, position: Float): Color {
    val count = palette.size
    val wrapped = ((position % count) + count) % count
    val lower = wrapped.toInt() % count
    return lerp(palette[lower], palette[(lower + 1) % count], wrapped - wrapped.toInt())
}

/** The colours of the categories that appear most often in [categories] (shared ranking), strongest first. */
fun dominantColors(categories: List<LabCategory>, limit: Int = 4): List<Color> =
    LabCategory.ranked(categories).filter { it != LabCategory.OTHER }.take(limit).map { it.color }

/** The rainbow used before there's any data (onboarding, empty states). */
val welcomePalette: List<Color> = listOf(
    LabCategory.BLOOD_GAS, LabCategory.ELECTROLYTES, LabCategory.HEPATIC,
    LabCategory.GLYCEMIC, LabCategory.HEMATOLOGY, LabCategory.ENDOCRINE,
).map { it.color }

@Preview(name = "Wash – light", widthDp = 360, heightDp = 640)
@Composable
private fun WashPreview() {
    LabImporterTheme(dynamicColor = false) { CategoryWash(dominantColors(listOf(LabCategory.LIPIDS, LabCategory.RENAL, LabCategory.GLYCEMIC))) }
}

@Preview(name = "Wash – dark", widthDp = 360, heightDp = 640, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WashDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { CategoryWash(welcomePalette, animated = true) }
}
