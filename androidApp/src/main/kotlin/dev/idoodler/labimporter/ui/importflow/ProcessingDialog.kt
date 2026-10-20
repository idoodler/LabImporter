package dev.idoodler.labimporter.ui.importflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.parse.ParseProgress
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import java.text.NumberFormat

/** What an import is doing right now. */
sealed interface ProcessingStage {
    data object Reading : ProcessingStage

    /** Gemini Nano is still downloading; [fraction] is null while its size is unknown. */
    data class PreparingModel(val fraction: Float?) : ProcessingStage

    data class Parsing(val progress: ParseProgress) : ProcessingStage
}

/** Modal progress while reading a document, while the model downloads and while it streams values. */
@Composable
fun ProcessingDialog(stage: ProcessingStage, onCancel: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)) {
        ProcessingCard(stage, onCancel)
    }
}

@Composable
private fun ProcessingCard(stage: ProcessingStage, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
      Box(Modifier.background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), Color.Transparent)))) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val fraction = (stage as? ProcessingStage.PreparingModel)?.fraction
            if (fraction != null) {
                CircularWavyProgressIndicator(progress = { fraction }, modifier = Modifier.size(88.dp))
            } else {
                ContainedLoadingIndicator(Modifier.size(88.dp))
            }
            Text(
                stringResource(
                    when (stage) {
                        ProcessingStage.Reading -> R.string.processing_reading
                        is ProcessingStage.PreparingModel -> R.string.processing_preparing
                        is ProcessingStage.Parsing -> R.string.processing_parsing
                    },
                ),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            when (stage) {
                ProcessingStage.Reading -> Unit
                is ProcessingStage.PreparingModel -> {
                    if (fraction != null) {
                        Text(
                            remember(fraction) { NumberFormat.getPercentInstance().format(fraction) },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.processing_preparing_detail), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ProcessingStage.Parsing -> {
                    val progress = stage.progress
                    if (progress.entryCount > 0) {
                        Text(
                            pluralStringResource(R.plurals.values_found, progress.entryCount, progress.entryCount),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedContent(progress.latestName.orEmpty(), label = "latest") { name ->
                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
      }
    }
}

@Preview(name = "Reading")
@Composable
private fun ReadingPreview() {
    LabImporterTheme(dynamicColor = false) { ProcessingCard(ProcessingStage.Reading) {} }
}

@Preview(name = "Downloading the model")
@Composable
private fun PreparingPreview() {
    LabImporterTheme(dynamicColor = false) { ProcessingCard(ProcessingStage.PreparingModel(0.42f)) {} }
}

@Preview(name = "Downloading – size unknown, dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreparingUnknownPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ProcessingCard(ProcessingStage.PreparingModel(null)) {} }
}

@Preview(name = "Parsing – dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ParsingPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ProcessingCard(ProcessingStage.Parsing(ParseProgress(7, "Kreatinin"))) {} }
}
