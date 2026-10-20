package dev.idoodler.labimporter.ui.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Coronavirus
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.RoundedPolygon
import dev.idoodler.labimporter.core.model.LabCategory

/*
 * The visual identity of each clinical category: the shared colour (identical
 * on iOS), a Material Symbol, and a Material 3 Expressive shape. Every place a
 * metric appears — badges, charts, washes, chips — draws from here, so a
 * category reads the same everywhere.
 */

val LabCategory.color: Color get() = Color(argb)

val LabCategory.icon: ImageVector
    get() = when (this) {
        LabCategory.GLYCEMIC -> Icons.Rounded.Cookie
        LabCategory.LIPIDS -> Icons.Rounded.Opacity
        LabCategory.CARDIAC -> Icons.Rounded.Favorite
        LabCategory.RENAL -> Icons.Rounded.WaterDrop
        LabCategory.HEPATIC -> Icons.Rounded.Eco
        LabCategory.ENDOCRINE -> Icons.Rounded.Hub
        LabCategory.ELECTROLYTES -> Icons.Rounded.Bolt
        LabCategory.BLOOD_GAS -> Icons.Rounded.Air
        LabCategory.HEMATOLOGY -> Icons.Rounded.Bloodtype
        LabCategory.COAGULATION -> Icons.Rounded.Healing
        LabCategory.NUTRITION -> Icons.Rounded.WbSunny
        LabCategory.MICROBIOLOGY -> Icons.Rounded.Coronavirus
        LabCategory.URINALYSIS -> Icons.Rounded.FilterVintage
        LabCategory.DRUG -> Icons.Rounded.Medication
        LabCategory.OTHER -> Icons.Rounded.Biotech
    }

/** A distinct expressive shape per category, so badges are recognisable even without colour. */
val LabCategory.polygon: RoundedPolygon
    get() = when (this) {
        LabCategory.GLYCEMIC -> MaterialShapes.Cookie9Sided
        LabCategory.LIPIDS -> MaterialShapes.Clover4Leaf
        LabCategory.CARDIAC -> MaterialShapes.Heart
        LabCategory.RENAL -> MaterialShapes.Bun
        LabCategory.HEPATIC -> MaterialShapes.Flower
        LabCategory.ENDOCRINE -> MaterialShapes.Sunny
        LabCategory.ELECTROLYTES -> MaterialShapes.SoftBurst
        LabCategory.BLOOD_GAS -> MaterialShapes.Puffy
        LabCategory.HEMATOLOGY -> MaterialShapes.Cookie4Sided
        LabCategory.COAGULATION -> MaterialShapes.Gem
        LabCategory.NUTRITION -> MaterialShapes.VerySunny
        LabCategory.MICROBIOLOGY -> MaterialShapes.Clover8Leaf
        LabCategory.URINALYSIS -> MaterialShapes.PuffyDiamond
        LabCategory.DRUG -> MaterialShapes.Pentagon
        LabCategory.OTHER -> MaterialShapes.Cookie6Sided
    }

/** True when the current scheme is dark (works inside previews that force a theme). */
val isDarkScheme: Boolean
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Text/icon colour that stays legible on top of [color]. */
fun contentColorOn(color: Color): Color = if (color.luminance() > 0.45f) Color(0xFF2B2100) else Color.White

/** A category colour tuned for lines and accents on the current surface. */
@Composable
@ReadOnlyComposable
fun accentFor(color: Color): Color = if (isDarkScheme) lerp(color, Color.White, 0.12f) else lerp(color, Color.Black, 0.06f)

/** A surface tinted toward [color] — the base of category cards. */
@Composable
@ReadOnlyComposable
fun tintedSurface(color: Color, strength: Float = 0.10f): Color {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    return lerp(base, color, if (isDarkScheme) strength * 1.5f else strength)
}
