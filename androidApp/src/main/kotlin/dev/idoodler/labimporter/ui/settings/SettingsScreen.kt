package dev.idoodler.labimporter.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.ai.messageRes
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.ui.HealthConnectState
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** Settings as Material 3 Expressive grouped lists with coloured shape icons. */
@Composable
fun SettingsScreen(
    patientName: String,
    onPatientName: (String) -> Unit,
    healthConnect: HealthConnectState,
    onConnectHealthConnect: () -> Unit,
    onUseHealthConnect: (Boolean) -> Unit,
    ai: AiSettings,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    softwareIdentity: String,
    transfer: TransferSettings = TransferSettings(),
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showLicense by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        CategoryWash(listOf(LabCategory.ENDOCRINE.color, LabCategory.BLOOD_GAS.color, LabCategory.HEMATOLOGY.color), intensity = 0.7f)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.tab_settings), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { SectionTitle(stringResource(R.string.settings_patient)) }
                item {
                    var name by remember(patientName) { mutableStateOf(patientName) }
                    GroupedListItem(
                        0, 1,
                        leading = { Badge(MaterialShapes.Cookie9Sided, LabCategory.BLOOD_GAS.color, Icons.Rounded.Badge) },
                        headline = {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it; onPatientName(it) }, singleLine = true,
                                label = { Text(stringResource(R.string.patient)) }, shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        supporting = { Text(stringResource(R.string.patient_hint), Modifier.padding(top = 4.dp)) },
                    )
                }

                item { SectionTitle(stringResource(R.string.settings_storage)) }
                item { HealthConnectItem(healthConnect, onConnectHealthConnect, onUseHealthConnect) }

                item { SectionTitle(stringResource(R.string.settings_ai)) }
                item { AiItem(ai) }

                item { SectionTitle(stringResource(R.string.transfer_title)) }
                item { TransferItems(transfer) }

                if (preferences.hiddenCodes.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.settings_hidden)) }
                    preferences.hiddenCodes.forEachIndexed { index, code ->
                        item(key = "hidden-$code") {
                            GroupedListItem(
                                index, preferences.hiddenCodes.size,
                                leading = { Badge(MaterialShapes.Pill, LabCategory.OTHER.color, Icons.Rounded.VisibilityOff) },
                                headline = { Text(LabMapping.displayName(code)) },
                                supporting = { Text(code) },
                                trailing = {
                                    TextButton(onClick = { onUpdatePreferences { it.toggleHidden(code) } }) { Text(stringResource(R.string.show)) }
                                },
                            )
                        }
                    }
                }

                item { SectionTitle(stringResource(R.string.settings_about)) }
                item {
                    GroupedListItem(
                        0, 4,
                        leading = { Badge(MaterialShapes.Clover4Leaf, LabCategory.HEPATIC.color, Icons.Rounded.Lock) },
                        headline = { Text(stringResource(R.string.privacy_title)) },
                        supporting = { Text(stringResource(R.string.privacy_message)) },
                    )
                }
                item {
                    GroupedListItem(
                        1, 4, onClick = { showDisclaimer = true },
                        leading = { Badge(MaterialShapes.SoftBurst, LabCategory.GLYCEMIC.color, Icons.Rounded.Info) },
                        headline = { Text(stringResource(R.string.disclaimer_title)) },
                    )
                }
                item {
                    val loinc = LabCore.loinc
                    GroupedListItem(
                        2, 4, onClick = { showLicense = true },
                        leading = { Badge(MaterialShapes.Gem, LabCategory.COAGULATION.color, Icons.AutoMirrored.Rounded.MenuBook) },
                        headline = { Text(stringResource(R.string.loinc_catalog)) },
                        supporting = {
                            Text(stringResource(R.string.loinc_catalog_detail, loinc?.version.orEmpty(), java.text.NumberFormat.getIntegerInstance().format(loinc?.count ?: 0)))
                        },
                    )
                }
                item {
                    GroupedListItem(
                        3, 4,
                        leading = { Badge(MaterialShapes.Pentagon, LabCategory.OTHER.color, Icons.Rounded.Tag) },
                        headline = { Text(stringResource(R.string.version)) },
                        supporting = { Text(softwareIdentity) },
                    )
                }
            }
        }
    }
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) },
            title = { Text(stringResource(R.string.loinc_catalog)) },
            text = { Text(LabCore.loinc?.license.orEmpty().take(1500)) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            icon = { Icon(Icons.Rounded.Info, null) },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** Moving reports between devices (iPhone ⇄ Android) as a file — see `ReportTransfer`. */
data class TransferSettings(
    val hasReports: Boolean = false,
    /** Writes every report to the transfer file and returns it for sharing. */
    val onExport: () -> Uri? = { null },
    val onImport: (Uri) -> Unit = {},
)

@Composable
private fun TransferItems(transfer: TransferSettings) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(transfer.onImport) }
    val chooserTitle = stringResource(R.string.transfer_export)
    Column {
        GroupedListItem(
            0, 2,
            onClick = if (transfer.hasReports) {
                {
                    transfer.onExport()?.let { uri ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, chooserTitle))
                    }
                }
            } else {
                null
            },
            leading = { Badge(MaterialShapes.Arch, LabCategory.HEPATIC.color, Icons.Rounded.Upload) },
            headline = { Text(stringResource(R.string.transfer_export)) },
            supporting = { Text(stringResource(R.string.transfer_export_detail)) },
            modifier = Modifier.alpha(if (transfer.hasReports) 1f else 0.5f),
        )
        GroupedListItem(
            1, 2,
            onClick = { picker.launch(TRANSFER_TYPES) },
            leading = { Badge(MaterialShapes.Arch, LabCategory.ELECTROLYTES.color, Icons.Rounded.Download) },
            headline = { Text(stringResource(R.string.transfer_import)) },
            supporting = { Text(stringResource(R.string.transfer_import_detail)) },
        )
    }
}

/** What a transfer file can arrive as (providers label JSON inconsistently). */
private val TRANSFER_TYPES = arrayOf(
    "application/json", "application/fhir+json", "application/xml", "text/xml", "text/plain", "application/octet-stream",
)

@Composable
private fun Badge(polygon: RoundedPolygon, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, brush: Brush? = null) {
    ShapeBadge(polygon, color, icon, size = 42.dp, brush = brush)
}

@Composable
private fun HealthConnectItem(state: HealthConnectState, onConnect: () -> Unit, onUse: (Boolean) -> Unit) {
    val supporting = when {
        state.inUse -> stringResource(R.string.hc_in_use)
        state.availability == HealthConnectAvailability.AVAILABLE && !state.granted -> stringResource(R.string.hc_available)
        state.availability == HealthConnectAvailability.AVAILABLE -> stringResource(R.string.hc_local_selected)
        state.availability == HealthConnectAvailability.UPDATE_REQUIRED -> stringResource(R.string.hc_update_required)
        state.availability == HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED -> stringResource(R.string.hc_medical_unsupported)
        else -> stringResource(R.string.hc_not_installed)
    }
    GroupedListItem(
        0, 1,
        leading = { Badge(MaterialShapes.Heart, LabCategory.HEMATOLOGY.color, Icons.Rounded.Favorite) },
        headline = { Text(stringResource(R.string.hc_title), fontWeight = FontWeight.SemiBold) },
        supporting = {
            Column {
                Text(supporting)
                if (state.availability == HealthConnectAvailability.AVAILABLE && !state.granted) {
                    Button(onClick = onConnect, modifier = Modifier.padding(top = 10.dp)) { Text(stringResource(R.string.hc_connect)) }
                }
            }
        },
        trailing = { if (state.granted) Switch(checked = state.inUse, onCheckedChange = onUse) },
    )
}

/** The on-device AI row: the model's state and what the user can do about it. */
data class AiSettings(
    val availability: AiAvailability,
    /** Debug builds: the development stand-in replaces Gemini Nano. */
    val usingStandIn: Boolean = false,
    val onTryAgain: () -> Unit = {},
    val onUseGemini: () -> Unit = {},
)

@Composable
private fun AiItem(ai: AiSettings) {
    val standIn = DevelopmentStandIn.texts?.takeIf { ai.usingStandIn }
    GroupedListItem(
        0, 1,
        leading = {
            Badge(
                MaterialShapes.Sunny, MaterialTheme.colorScheme.primary, Icons.Rounded.AutoAwesome,
                brush = Brush.linearGradient(listOf(LabCategory.BLOOD_GAS.color, LabCategory.ENDOCRINE.color, LabCategory.HEMATOLOGY.color)),
            )
        },
        headline = { Text(stringResource(R.string.ai_title), fontWeight = FontWeight.SemiBold) },
        supporting = {
            Column {
                val availability = ai.availability
                if (standIn != null) {
                    Text(standIn.active)
                    OutlinedButton(onClick = ai.onUseGemini, modifier = Modifier.padding(top = 10.dp)) { Text(standIn.useGemini) }
                } else when (availability) {
                    AiAvailability.Ready -> Text(stringResource(R.string.ai_available))
                    AiAvailability.Checking -> Text(stringResource(R.string.ai_checking))
                    is AiAvailability.Preparing -> {
                        Text(stringResource(R.string.processing_preparing))
                        val fraction = availability.fraction
                        val modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        if (fraction != null) LinearWavyProgressIndicator(progress = { fraction }, modifier) else LinearWavyProgressIndicator(modifier)
                    }
                    is AiAvailability.PreparationFailed -> {
                        Text(stringResource(availability.problem.messageRes))
                        Button(onClick = ai.onTryAgain, modifier = Modifier.padding(top = 10.dp)) { Text(stringResource(R.string.try_again)) }
                    }
                    is AiAvailability.Unsupported -> Text(stringResource(R.string.unsupported_title))
                }
            }
        },
    )
}

@Preview(name = "Local storage, model downloading", widthDp = 400, heightDp = 1100)
@Composable
private fun SettingsPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            "Max Mustermann", {}, HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED), {}, {},
            AiSettings(AiAvailability.Preparing(420_000_000, 1_000_000_000)), LabDisplayPreferences(hiddenCodes = listOf("2160-0")), {},
            "LabImporter 1.0 (1) Android",
        )
    }
}

@Preview(name = "Health Connect + Gemini – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(
            "", {}, HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = true), {}, {},
            AiSettings(AiAvailability.Ready), LabDisplayPreferences(), {}, "LabImporter 1.0 (1) Android",
        )
    }
}

@Preview(name = "Model download failed", widthDp = 400, heightDp = 1100)
@Composable
private fun SettingsDownloadFailedPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            "", {}, HealthConnectState(HealthConnectAvailability.NOT_INSTALLED), {}, {},
            AiSettings(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE)), LabDisplayPreferences(), {},
            "LabImporter 1.0 (1) Android",
        )
    }
}
