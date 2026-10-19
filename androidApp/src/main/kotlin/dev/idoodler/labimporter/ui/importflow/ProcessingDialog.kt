package dev.idoodler.labimporter.ui.importflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** Modal progress while reading a document and while the model streams values. */
@Composable
fun ProcessingDialog(progress: ParseProgress?, onCancel: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)) {
        ProcessingCard(progress, onCancel)
    }
}

@Composable
private fun ProcessingCard(progress: ParseProgress?, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ContainedLoadingIndicator(Modifier.size(72.dp))
            Text(
                stringResource(if (progress == null) R.string.processing_reading else R.string.processing_parsing),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            if (progress != null && progress.entryCount > 0) {
                Text(
                    pluralStringResource(R.plurals.values_found, progress.entryCount, progress.entryCount),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedContent(progress?.latestName.orEmpty(), label = "latest") { name ->
                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Preview(name = "Reading")
@Composable
private fun ReadingPreview() {
    LabImporterTheme(dynamicColor = false) { ProcessingCard(null) {} }
}

@Preview(name = "Parsing – dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ParsingPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ProcessingCard(ParseProgress(7, "Kreatinin")) {} }
}
