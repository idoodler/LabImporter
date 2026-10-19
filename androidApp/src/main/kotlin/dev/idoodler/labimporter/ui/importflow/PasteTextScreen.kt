package dev.idoodler.labimporter.ui.importflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** Paste report text (e.g. copied from a portal or an e-mail). */
@Composable
fun PasteTextScreen(onCancel: () -> Unit, onSubmit: (String) -> Unit, initial: String = "") {
    var text by rememberSaveable { mutableStateOf(initial) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_paste)) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) } },
                actions = {
                    Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank(), modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.analyze))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).imePadding()) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.paste_placeholder)) },
                modifier = Modifier.fillMaxSize().weight(1f),
            )
            TextButton(onClick = { text = PreviewSamples.SAMPLE_REPORT_TEXT }) { Text(stringResource(R.string.insert_sample)) }
        }
    }
}

@Preview(name = "Empty", widthDp = 400, heightDp = 600)
@Composable
private fun PasteEmptyPreview() {
    LabImporterTheme(dynamicColor = false) { PasteTextScreen({}, {}) }
}

@Preview(name = "Filled – dark", widthDp = 400, heightDp = 600, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PasteFilledPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { PasteTextScreen({}, {}, PreviewSamples.SAMPLE_REPORT_TEXT) }
}
