package dev.idoodler.labimporter.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.StandInTexts
import dev.idoodler.labimporter.ai.UnsupportedReason
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.design.welcomePalette
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** The warning orange of the iOS screen. */
private val WarningOrange = Color(0xFFFF9500)

/**
 * Shown instead of the app when Gemini Nano can't run on this device — the
 * Android counterpart of iOS's `UnsupportedDeviceView`. LabImporter reads
 * reports only with its on-device AI and never guesses values instead.
 *
 * @param checking a "Check Again" is in flight.
 * @param standIn debug builds only: the development stand-in offer.
 */
@Composable
fun UnsupportedDeviceScreen(
    reason: UnsupportedReason,
    checking: Boolean,
    onCheckAgain: () -> Unit,
    standIn: StandInTexts? = null,
    onUseStandIn: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        CategoryWash(welcomePalette, animated = true, intensity = 1.25f)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            OnboardingPage(
                content = {
                    ShapeBadge(MaterialShapes.Cookie7Sided, WarningOrange, Icons.Rounded.WarningAmber, size = 128.dp)
                    Text(
                        stringResource(R.string.unsupported_title), style = MaterialTheme.typography.headlineLargeEmphasized,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(
                            if (reason == UnsupportedReason.UPDATE_REQUIRED) R.string.unsupported_update_message else R.string.unsupported_message,
                        ),
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = groupContainerColor())) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Requirement(
                                MaterialShapes.Cookie9Sided, LabCategory.BLOOD_GAS.color, Icons.Rounded.Memory,
                                R.string.requirement_device_title, R.string.requirement_device_detail,
                            )
                            Requirement(
                                MaterialShapes.Clover4Leaf, LabCategory.HEPATIC.color, Icons.Rounded.SystemUpdate,
                                R.string.requirement_update_title, R.string.requirement_update_detail,
                            )
                        }
                    }
                },
                actions = {
                    Button(onClick = onCheckAgain, enabled = !checking, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        if (checking) {
                            LoadingIndicator(Modifier.size(32.dp))
                        } else {
                            Text(stringResource(R.string.check_again), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (standIn != null) {
                        OutlinedButton(onClick = onUseStandIn, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(standIn.use) }
                        Text(
                            standIn.explanation, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun Requirement(polygon: RoundedPolygon, color: Color, icon: ImageVector, title: Int, detail: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ShapeBadge(polygon, color, icon, size = 52.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val previewStandIn = StandInTexts("Use Development Stand-in", "Debug build only. Imports return sample values.", "", "", "", "")

@Preview(name = "Unsupported", widthDp = 400, heightDp = 860)
@Composable
private fun UnsupportedPreview() {
    LabImporterTheme(dynamicColor = false) { UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}) }
}

@Preview(name = "Unsupported – dark", widthDp = 400, heightDp = 860, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnsupportedDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {})
    }
}

@Preview(name = "Update required · checking", widthDp = 400, heightDp = 860)
@Composable
private fun UpdateRequiredPreview() {
    LabImporterTheme(dynamicColor = false) { UnsupportedDeviceScreen(UnsupportedReason.UPDATE_REQUIRED, checking = true, onCheckAgain = {}) }
}

@Preview(name = "Debug build · stand-in offer", widthDp = 400, heightDp = 860)
@Composable
private fun StandInPreview() {
    LabImporterTheme(dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}, standIn = previewStandIn)
    }
}

@Preview(name = "Landscape", widthDp = 860, heightDp = 400)
@Composable
private fun UnsupportedLandscapePreview() {
    LabImporterTheme(dynamicColor = false) { UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}) }
}
