package dev.idoodler.labimporter.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.PreparingStage
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.ai.messageRes
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** What a model download is doing, in one line — the processing dialog and Settings say it alike. */
@Composable
fun preparingTitle(preparing: AiAvailability.Preparing, engine: AiEngine): String = when (preparing.stage) {
    PreparingStage.DOWNLOADING ->
        if (engine == AiEngine.GEMMA) stringResource(R.string.processing_downloading, GemmaModel.NAME) else stringResource(R.string.processing_preparing)
    PreparingStage.WAITING_FOR_WIFI -> stringResource(R.string.model_waiting_wifi)
    PreparingStage.WAITING_FOR_NETWORK -> stringResource(R.string.model_waiting_network)
    PreparingStage.VERIFYING -> stringResource(R.string.model_verifying)
}

/** "1.1 GB of 2.6 GB" while the download's size is known. */
@Composable
fun preparingProgress(preparing: AiAvailability.Preparing): String? {
    val total = preparing.totalBytes ?: return null
    if (preparing.stage == PreparingStage.VERIFYING) return null
    return stringResource(R.string.model_progress, fileSize(preparing.downloadedBytes), fileSize(total))
}

/** A size as the system writes it ("2.6 GB"). */
@Composable
fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * The model download on the dashboard, so its state is visible without
 * starting an import: progress, a wait for Wi-Fi, the check — or why it
 * failed, with "Try Again". Nothing once the model is ready.
 */
@Composable
fun ModelStatusCard(
    availability: AiAvailability,
    engine: AiEngine,
    onTryAgain: () -> Unit,
    onUseMobileData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availability !is AiAvailability.Preparing && availability !is AiAvailability.PreparationFailed) return
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(
                MaterialShapes.Cookie9Sided, LabCategory.HEMATOLOGY.color,
                if (availability is AiAvailability.PreparationFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.Download, size = 48.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (availability) {
                    is AiAvailability.Preparing -> {
                        Text(preparingTitle(availability, engine), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        val fraction = availability.fraction.takeIf { availability.stage == PreparingStage.DOWNLOADING }
                        if (fraction != null) {
                            LinearWavyProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
                        } else {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        }
                        preparingProgress(availability)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(R.string.processing_preparing_detail), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (engine == AiEngine.GEMMA && availability.stage == PreparingStage.WAITING_FOR_WIFI) {
                            OutlinedButton(onClick = onUseMobileData) { Text(stringResource(R.string.model_use_mobile_data)) }
                        }
                    }
                    is AiAvailability.PreparationFailed -> {
                        Text(stringResource(availability.problem.messageRes), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onTryAgain) { Text(stringResource(R.string.try_again)) }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Preview(name = "Downloading Gemma", widthDp = 400)
@Composable
private fun ModelStatusDownloadingPreview() {
    LabImporterTheme(dynamicColor = false) {
        ModelStatusCard(AiAvailability.Preparing(1_100_000_000, GemmaModel.SIZE_BYTES), AiEngine.GEMMA, {}, {})
    }
}

@Preview(name = "Waiting for Wi-Fi – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ModelStatusWaitingPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ModelStatusCard(AiAvailability.Preparing(0, GemmaModel.SIZE_BYTES, PreparingStage.WAITING_FOR_WIFI), AiEngine.GEMMA, {}, {})
    }
}

@Preview(name = "Checking the download", widthDp = 400)
@Composable
private fun ModelStatusVerifyingPreview() {
    LabImporterTheme(dynamicColor = false) {
        ModelStatusCard(AiAvailability.Preparing(GemmaModel.SIZE_BYTES, GemmaModel.SIZE_BYTES, PreparingStage.VERIFYING), AiEngine.GEMMA, {}, {})
    }
}

@Preview(name = "Download failed", widthDp = 400)
@Composable
private fun ModelStatusFailedPreview() {
    LabImporterTheme(dynamicColor = false) {
        ModelStatusCard(AiAvailability.PreparationFailed(AiProblem.DOWNLOAD_FAILED), AiEngine.GEMMA, {}, {})
    }
}

@Preview(name = "Downloading Gemini Nano – RTL", widthDp = 400, locale = "ar")
@Composable
private fun ModelStatusGeminiPreview() {
    LabImporterTheme(dynamicColor = false) {
        ModelStatusCard(AiAvailability.Preparing(300_000_000, 1_000_000_000), AiEngine.GEMINI_NANO, {}, {})
    }
}
