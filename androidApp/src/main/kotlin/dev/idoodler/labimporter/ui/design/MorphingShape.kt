package dev.idoodler.labimporter.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * A slowly rotating blob that morphs between Material 3 Expressive shapes,
 * filled with a sweep of [colors] — the hero art for onboarding and empty
 * states. Content (e.g. the app icon) is centred on top.
 */
@Composable
fun MorphingShape(
    colors: List<Color>,
    size: Dp,
    modifier: Modifier = Modifier,
    shapes: List<RoundedPolygon> = listOf(
        MaterialShapes.Cookie9Sided, MaterialShapes.Clover8Leaf, MaterialShapes.SoftBurst, MaterialShapes.Sunny,
    ),
    content: @Composable () -> Unit = {},
) {
    val morphs = remember(shapes) { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val transition = rememberInfiniteTransition(label = "morph")
    val step = transition.animateFloat(
        0f, shapes.size.toFloat(),
        infiniteRepeatable(tween(shapes.size * 4_000, easing = LinearEasing), RepeatMode.Restart), label = "step",
    ).value
    val rotation = transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "rotation",
    ).value
    val index = step.toInt() % shapes.size
    // Hold each shape for most of its slot, then morph quickly into the next.
    val local = step - step.toInt()
    val progress = FastOutSlowInEasing.transform(((local - 0.65f) / 0.35f).coerceIn(0f, 1f))
    val path = remember { Path() }
    val brush = remember(colors) { Brush.sweepGradient(colors + colors.first(), center = Offset(0.5f, 0.5f)) }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            path.rewind()
            morphs[index].toPath(progress, path)
            scale(this.size.width, this.size.height, pivot = Offset.Zero) {
                rotate(rotation, pivot = Offset(0.5f, 0.5f)) { drawPath(path, brush) }
            }
        }
        content()
    }
}
