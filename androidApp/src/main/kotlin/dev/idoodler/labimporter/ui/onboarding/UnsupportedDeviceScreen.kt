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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.foundation.text.selection.SelectionContainer
import android.content.Intent
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
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.ui.components.fileSize
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
 * reports only with its on-device AI and never guesses values instead. Where
 * the phone can run the Gemma model instead ([modelDownload]), the screen
 * offers that download — with its size, what is sent and the license — rather
 * than ending here.
 *
 * @param detail the system's own answer (status or error code), shown for reporting.
 * @param checking a "Check Again" is in flight.
 * @param onDownloadModel the user's go-ahead; true allows mobile data.
 * @param standIn debug builds only: the development stand-in offer.
 */
@Composable
fun UnsupportedDeviceScreen(
    reason: UnsupportedReason,
    checking: Boolean,
    onCheckAgain: () -> Unit,
    detail: String? = null,
    modelDownload: Boolean = false,
    onDownloadModel: (allowMobileData: Boolean) -> Unit = {},
    standIn: StandInTexts? = null,
    onUseStandIn: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        CategoryWash(welcomePalette, animated = true, intensity = 1.25f)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (modelDownload) {
                ModelOffer(checking, onCheckAgain, onDownloadModel, detail, standIn, onUseStandIn)
            } else {
                NoModel(reason, checking, onCheckAgain, detail, standIn, onUseStandIn)
            }
        }
    }
}

@Composable
private fun NoModel(
    reason: UnsupportedReason,
    checking: Boolean,
    onCheckAgain: () -> Unit,
    detail: String?,
    standIn: StandInTexts?,
    onUseStandIn: () -> Unit,
) {
    val context = LocalContext.current
    OnboardingPage(
        content = {
            ShapeBadge(MaterialShapes.Cookie7Sided, WarningOrange, Icons.Rounded.WarningAmber, size = 128.dp)
            Text(
                stringResource(if (reason == UnsupportedReason.NOT_DETERMINED) R.string.unsupported_unknown_title else R.string.unsupported_title),
                style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(
                    when (reason) {
                        UnsupportedReason.DEVICE -> R.string.unsupported_message
                        UnsupportedReason.UPDATE_REQUIRED -> R.string.unsupported_update_message
                        UnsupportedReason.NOT_DETERMINED -> R.string.unsupported_unknown_message
                    },
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
            // Google's current list of phones that offer Gemini Nano to apps.
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, SUPPORTED_PHONES_URL.toUri())) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.supported_phones))
            }
            // The system's own answer, so the reason can be reported and checked.
            detail?.let { DetailLine(it) }
        },
        actions = {
            Button(onClick = onCheckAgain, enabled = !checking, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                if (checking) {
                    LoadingIndicator(Modifier.size(32.dp))
                } else {
                    Text(stringResource(R.string.check_again), style = MaterialTheme.typography.titleMedium)
                }
            }
            StandInOffer(standIn, onUseStandIn)
        },
    )
}

@Composable
private fun ModelOffer(
    checking: Boolean,
    onCheckAgain: () -> Unit,
    onDownloadModel: (allowMobileData: Boolean) -> Unit,
    detail: String?,
    standIn: StandInTexts?,
    onUseStandIn: () -> Unit,
) {
    val context = LocalContext.current
    var allowMobileData by rememberSaveable { mutableStateOf(false) }
    OnboardingPage(
        content = {
            ShapeBadge(MaterialShapes.Cookie7Sided, LabCategory.HEMATOLOGY.color, Icons.Rounded.Download, size = 128.dp)
            Text(
                stringResource(R.string.model_offer_title), style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.model_offer_message), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = groupContainerColor())) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Fact(
                        MaterialShapes.Cookie9Sided, LabCategory.BLOOD_GAS.color, Icons.Rounded.Download,
                        stringResource(R.string.model_fact_size_title, fileSize(GemmaModel.SIZE_BYTES)),
                        stringResource(R.string.model_fact_size_detail, fileSize(GemmaModel.SIZE_BYTES + GemmaModel.SPACE_MARGIN_BYTES)),
                    )
                    Fact(
                        MaterialShapes.Clover4Leaf, LabCategory.HEPATIC.color, Icons.Rounded.Lock,
                        stringResource(R.string.model_fact_privacy_title),
                        stringResource(R.string.model_fact_privacy_detail, GemmaModel.HOST),
                    )
                    Fact(
                        MaterialShapes.Sunny, LabCategory.ENDOCRINE.color, Icons.Rounded.AutoAwesome,
                        stringResource(R.string.model_fact_license_title, GemmaModel.NAME),
                        stringResource(R.string.model_fact_license_detail, GemmaModel.LICENSE),
                    )
                    Row(
                        Modifier.fillMaxWidth().toggleable(allowMobileData, role = Role.Switch) { allowMobileData = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.model_allow_mobile_data), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.model_allow_mobile_data_detail), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = allowMobileData, onCheckedChange = null)
                    }
                }
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, GemmaModel.PAGE_URL.toUri())) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.model_details))
            }
            // Why Gemini Nano isn't used: the system's own answer.
            detail?.let { DetailLine(it) }
        },
        actions = {
            Button(onClick = { onDownloadModel(allowMobileData) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.model_download, fileSize(GemmaModel.SIZE_BYTES)), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onCheckAgain, enabled = !checking, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (checking) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.check_again))
            }
            StandInOffer(standIn, onUseStandIn)
        },
    )
}

@Composable
private fun StandInOffer(standIn: StandInTexts?, onUseStandIn: () -> Unit) {
    if (standIn == null) return
    OutlinedButton(onClick = onUseStandIn, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(standIn.use) }
    Text(
        standIn.explanation, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun DetailLine(detail: String) {
    SelectionContainer {
        Text(
            "${stringResource(R.string.details)}: $detail", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Requirement(polygon: RoundedPolygon, color: Color, icon: ImageVector, title: Int, detail: Int) =
    Fact(polygon, color, icon, stringResource(title), stringResource(detail))

@Composable
private fun Fact(polygon: RoundedPolygon, color: Color, icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ShapeBadge(polygon, color, icon, size = 52.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ML Kit's list of phones per GenAI API (the Prompt API's is the one that counts). */
private const val SUPPORTED_PHONES_URL = "https://developers.google.com/ml-kit/genai"

private val previewStandIn = StandInTexts("Use Development Stand-in", "Debug build only. Imports return sample values.", "", "", "", "")

@Preview(name = "Unsupported", widthDp = 400, heightDp = 860)
@Composable
private fun UnsupportedPreview() {
    LabImporterTheme(dynamicColor = false) { UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}, detail = "Prompt API: UNAVAILABLE") }
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

@Preview(name = "Gemma download offer", widthDp = 400, heightDp = 1200)
@Composable
private fun ModelOfferPreview() {
    LabImporterTheme(dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}, detail = "Prompt API: UNAVAILABLE", modelDownload = true)
    }
}

@Preview(name = "Gemma download offer – dark, checking", widthDp = 400, heightDp = 1200, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ModelOfferDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = true, onCheckAgain = {}, modelDownload = true, standIn = previewStandIn)
    }
}

@Preview(name = "Gemma download offer – landscape", widthDp = 860, heightDp = 400)
@Composable
private fun ModelOfferLandscapePreview() {
    LabImporterTheme(dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.DEVICE, checking = false, onCheckAgain = {}, modelDownload = true)
    }
}

@Preview(name = "Gemma download offer – RTL", widthDp = 400, heightDp = 1200, locale = "ar")
@Composable
private fun ModelOfferRtlPreview() {
    LabImporterTheme(dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.NOT_DETERMINED, checking = false, onCheckAgain = {}, modelDownload = true)
    }
}

@Preview(name = "Couldn't check", widthDp = 400, heightDp = 1000)
@Composable
private fun UnsupportedNotDeterminedPreview() {
    LabImporterTheme(dynamicColor = false) {
        UnsupportedDeviceScreen(UnsupportedReason.NOT_DETERMINED, checking = false, onCheckAgain = {}, detail = "AICore: no status within 30 s")
    }
}
