package dev.idoodler.labimporter.ui.importflow

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import android.net.Uri

/**
 * The four ways in: scan (ML Kit document scanner), pick a PDF/image, paste
 * text, or type values by hand — the same entry points as iOS `ImportLandingView`.
 */
@Composable
fun ImportSheet(
    onDismiss: () -> Unit,
    onDocuments: (List<Uri>) -> Unit,
    onPaste: () -> Unit,
    onManual: () -> Unit,
    onPrewarm: () -> Unit,
    onError: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activity = LocalActivity.current

    // Start loading the model while the user picks a source.
    LaunchedEffect(Unit) { onPrewarm() }

    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri }
            onDocuments(pages)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onDocuments(uris)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ImportOptions(
            onScan = {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(10)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                val host = activity ?: return@ImportOptions
                GmsDocumentScanning.getClient(options).getStartScanIntent(host)
                    .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { onError(it.localizedMessage ?: host.getString(R.string.error_scanner)) }
            },
            onPick = { picker.launch(arrayOf("application/pdf", "image/*")) },
            onPaste = onPaste,
            onManual = onManual,
        )
    }
}

@Composable
private fun ImportOptions(onScan: () -> Unit, onPick: () -> Unit, onPaste: () -> Unit, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.import_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.import_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionCard(Icons.Outlined.DocumentScanner, R.string.import_scan, R.string.import_scan_detail, primary = true, onClick = onScan)
        OptionCard(Icons.Outlined.FileOpen, R.string.import_file, R.string.import_file_detail, onClick = onPick)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionCard(Icons.Outlined.ContentPaste, R.string.import_paste, null, Modifier.weight(1f), onClick = onPaste)
            OptionCard(Icons.Outlined.EditNote, R.string.import_manual, null, Modifier.weight(1f), onClick = onManual)
        }
    }
}

@Composable
private fun OptionCard(
    icon: ImageVector,
    title: Int,
    detail: Int?,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.large, color = content.copy(alpha = 0.12f), contentColor = content) {
                Icon(icon, null, Modifier.padding(10.dp).size(24.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                if (detail != null) Text(stringResource(detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(name = "Options", widthDp = 400)
@Composable
private fun ImportOptionsPreview() {
    LabImporterTheme(dynamicColor = false) { Surface { ImportOptions({}, {}, {}, {}) } }
}

@Preview(name = "Options – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImportOptionsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { Surface { ImportOptions({}, {}, {}, {}) } }
}
