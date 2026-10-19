package dev.idoodler.labimporter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiStatus
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.ui.HealthConnectState
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

@Composable
fun SettingsScreen(
    patientName: String,
    onPatientName: (String) -> Unit,
    healthConnect: HealthConnectState,
    onConnectHealthConnect: () -> Unit,
    onUseHealthConnect: (Boolean) -> Unit,
    aiStatus: AiStatus,
    onDownloadModel: () -> Unit,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    softwareIdentity: String,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showLicense by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.tab_settings)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item { Section(R.string.settings_patient) }
            item {
                var name by remember(patientName) { mutableStateOf(patientName) }
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Badge, null) },
                    headlineContent = {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it; onPatientName(it) }, singleLine = true,
                            label = { Text(stringResource(R.string.patient)) }, modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.patient_hint)) },
                )
            }

            item { Section(R.string.settings_storage) }
            item { HealthConnectItem(healthConnect, onConnectHealthConnect, onUseHealthConnect) }

            item { Section(R.string.settings_ai) }
            item { AiItem(aiStatus, onDownloadModel) }

            if (preferences.hiddenCodes.isNotEmpty()) {
                item { Section(R.string.settings_hidden) }
                preferences.hiddenCodes.forEach { code ->
                    item(key = "hidden-$code") {
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                            headlineContent = { Text(LabMapping.displayName(code)) },
                            supportingContent = { Text(code) },
                            trailingContent = {
                                TextButton(onClick = { onUpdatePreferences { it.toggleHidden(code) } }) { Text(stringResource(R.string.show)) }
                            },
                        )
                    }
                }
            }

            item { Section(R.string.settings_about) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Lock, null) },
                    headlineContent = { Text(stringResource(R.string.privacy_title)) },
                    supportingContent = { Text(stringResource(R.string.privacy_message)) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showDisclaimer = true },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text(stringResource(R.string.disclaimer_title)) },
                )
            }
            item {
                val loinc = LabCore.loinc
                ListItem(
                    modifier = Modifier.clickable { showLicense = true },
                    leadingContent = { Icon(Icons.Outlined.MenuBook, null) },
                    headlineContent = { Text(stringResource(R.string.loinc_catalog)) },
                    supportingContent = {
                        Text(stringResource(R.string.loinc_catalog_detail, loinc?.version.orEmpty(), java.text.NumberFormat.getIntegerInstance().format(loinc?.count ?: 0)))
                    },
                )
            }
            item { ListItem(headlineContent = { Text(softwareIdentity, style = MaterialTheme.typography.bodySmall) }) }
        }
    }
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.loinc_catalog)) },
            text = { Text(LabCore.loinc?.license.orEmpty().take(1500)) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
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
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Favorite, null) },
        headlineContent = { Text(stringResource(R.string.hc_title)) },
        supportingContent = {
            Column {
                Text(supporting)
                if (state.availability == HealthConnectAvailability.AVAILABLE && !state.granted) {
                    Button(onClick = onConnect, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.hc_connect)) }
                }
            }
        },
        trailingContent = {
            if (state.granted) Switch(checked = state.inUse, onCheckedChange = onUse)
        },
    )
}

@Composable
private fun AiItem(status: AiStatus, onDownload: () -> Unit) {
    val text = when (status) {
        AiStatus.Available -> stringResource(R.string.ai_available)
        AiStatus.Checking -> stringResource(R.string.ai_checking)
        AiStatus.Downloadable -> stringResource(R.string.ai_downloadable)
        is AiStatus.Downloading -> stringResource(R.string.ai_downloading)
        AiStatus.Unavailable -> stringResource(R.string.ai_unavailable)
    }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.AutoAwesome, null) },
        headlineContent = { Text(stringResource(R.string.ai_title)) },
        supportingContent = {
            Column {
                Text(text)
                if (status == AiStatus.Downloadable) {
                    Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.ai_download)) }
                }
            }
        },
    )
}

@Preview(name = "Local storage, no Gemini", widthDp = 400, heightDp = 1000)
@Composable
private fun SettingsPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            "Max Mustermann", {}, HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED), {}, {},
            AiStatus.Unavailable, {}, LabDisplayPreferences(hiddenCodes = listOf("2160-0")), {}, "LabImporter 1.0 (1) Android",
        )
    }
}

@Preview(name = "Health Connect + Gemini – dark", widthDp = 400, heightDp = 1000, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(
            "", {}, HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = true), {}, {},
            AiStatus.Available, {}, LabDisplayPreferences(), {}, "LabImporter 1.0 (1) Android",
        )
    }
}
