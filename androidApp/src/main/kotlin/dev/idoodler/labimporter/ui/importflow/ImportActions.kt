package dev.idoodler.labimporter.ui.importflow

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** The four ways in — scan, file, paste, manual — mirroring iOS `ImportLandingView`. */
data class ImportActions(
    val scan: () -> Unit,
    val pickFile: () -> Unit,
    val paste: () -> Unit,
    val manual: () -> Unit,
    val prewarm: () -> Unit,
)

/** Registers the scanner and file-picker launchers and bundles every entry point. */
@Composable
fun rememberImportActions(
    onDocuments: (List<Uri>) -> Unit,
    onPaste: () -> Unit,
    onManual: () -> Unit,
    onPrewarm: () -> Unit,
    onError: (String) -> Unit,
): ImportActions {
    val activity = LocalActivity.current
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri }
            if (pages.isNotEmpty()) onDocuments(pages)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onDocuments(uris)
    }
    val scannerError = stringResource(R.string.error_scanner)
    return remember(activity) {
        ImportActions(
            scan = {
                onPrewarm()
                val host = activity
                if (host == null) {
                    onError(scannerError)
                } else {
                    val options = GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setPageLimit(10)
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build()
                    GmsDocumentScanning.getClient(options).getStartScanIntent(host)
                        .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                        .addOnFailureListener { onError(it.localizedMessage ?: scannerError) }
                }
            },
            pickFile = { onPrewarm(); picker.launch(arrayOf("application/pdf", "image/*")) },
            paste = onPaste,
            manual = onManual,
            prewarm = onPrewarm,
        )
    }
}

private data class ImportOption(val icon: ImageVector, val title: Int, val detail: Int?, val polygon: RoundedPolygon, val color: Color)

private fun options(): List<Pair<ImportOption, (ImportActions) -> Unit>> = listOf(
    ImportOption(Icons.Rounded.DocumentScanner, R.string.import_scan, R.string.import_scan_detail, MaterialShapes.Cookie9Sided, LabCategory.BLOOD_GAS.color) to { a -> a.scan() },
    ImportOption(Icons.Rounded.FileOpen, R.string.import_file, R.string.import_file_detail, MaterialShapes.Clover4Leaf, LabCategory.ELECTROLYTES.color) to { a -> a.pickFile() },
    ImportOption(Icons.Rounded.ContentPaste, R.string.import_paste, null, MaterialShapes.Sunny, LabCategory.GLYCEMIC.color) to { a -> a.paste() },
    ImportOption(Icons.Rounded.EditNote, R.string.import_manual, null, MaterialShapes.SoftBurst, LabCategory.HEMATOLOGY.color) to { a -> a.manual() },
)

/**
 * Import FAB that unfolds into the four entry points (Material 3 Expressive
 * FAB menu), with a scrim over the content. Place it in a full-size [BoxScope].
 */
@Composable
fun BoxScope.ImportFabMenu(actions: ImportActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = false },
        )
    }
    val label = stringResource(R.string.import_report)
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier.align(Alignment.BottomEnd),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it; if (it) actions.prewarm() },
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                Icon(if (expanded) Icons.Rounded.Close else Icons.Rounded.Add, null)
            }
        },
    ) {
        options().forEach { (option, run) ->
            FloatingActionButtonMenuItem(
                onClick = { expanded = false; run(actions) },
                icon = { Icon(option.icon, null) },
                text = { Text(stringResource(option.title)) },
            )
        }
    }
}

/** The import options as large tonal cards — used by empty states. */
@Composable
fun ImportOptionCards(actions: ImportActions, modifier: Modifier = Modifier) {
    val all = options()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionCard(all[0].first, Modifier.fillMaxWidth()) { all[0].second(actions) }
        OptionCard(all[1].first, Modifier.fillMaxWidth()) { all[1].second(actions) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionCard(all[2].first, Modifier.weight(1f)) { all[2].second(actions) }
            OptionCard(all[3].first, Modifier.weight(1f)) { all[3].second(actions) }
        }
    }
}

@Composable
private fun OptionCard(option: ImportOption, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = tintedSurface(option.color, 0.16f).copy(alpha = 0.92f)),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(option.polygon, option.color, option.icon, size = 44.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(option.title), style = MaterialTheme.typography.titleMedium)
                if (option.detail != null) {
                    Text(stringResource(option.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val previewActions = ImportActions({}, {}, {}, {}, {})

@Preview(name = "Option cards", widthDp = 380)
@Composable
private fun OptionCardsPreview() {
    LabImporterTheme(dynamicColor = false) { Surface { ImportOptionCards(previewActions, Modifier.padding(16.dp)) } }
}

@Preview(name = "Option cards – dark", widthDp = 380, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OptionCardsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { Surface { ImportOptionCards(previewActions, Modifier.padding(16.dp)) } }
}
