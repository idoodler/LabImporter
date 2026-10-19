package dev.idoodler.labimporter.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.ParseEngine
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.duplicateLoincCodes
import dev.idoodler.labimporter.ui.ReviewDraft
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.parseNumberInput
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.time.format.FormatStyle

/**
 * Review and correct parsed values before saving — nothing reaches the health
 * store without passing through here. Mirrors iOS `ReviewView`.
 */
@Composable
fun ReviewScreen(
    draft: ReviewDraft,
    onUpdateDraft: ((ReviewDraft) -> ReviewDraft) -> Unit,
    onUpdateValue: (String, (LabValue) -> LabValue) -> Unit,
    onAddValue: () -> String,
    onRemoveValue: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var pickingFor by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    val duplicates = remember(draft.values) { draft.values.duplicateLoincCodes() }
    val exportable = remember(draft.values) { draft.values.count { it.isExportable } }
    val canSave = exportable > 0 && duplicates.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (draft.original != null) R.string.edit_report else R.string.review_title)) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) } },
                actions = {
                    Button(onClick = onSave, enabled = canSave, modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().imePadding(),
        ) {
            item { HeaderCard(draft, onUpdateDraft, onPickDate = { pickingDate = true }) }
            if (duplicates.isNotEmpty()) {
                item {
                    Banner(Icons.Default.Warning, stringResource(R.string.duplicates_warning), error = true)
                }
            }
            item {
                Text(
                    pluralStringResource(R.plurals.exportable_count, exportable, exportable, draft.values.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(draft.values, key = { it.id }) { value ->
                ValueRow(
                    value = value,
                    isDuplicate = value.isDuplicate(duplicates),
                    onUpdate = { onUpdateValue(value.id, it) },
                    onPickCode = { pickingFor = value.id },
                    onRemove = { onRemoveValue(value.id) },
                )
            }
            item {
                OutlinedButton(onClick = { pickingFor = onAddValue() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_value))
                }
            }
        }
    }

    pickingFor?.let { id ->
        val value = draft.values.firstOrNull { it.id == id }
        CodePickerSheet(
            initialQuery = value?.name.orEmpty(),
            onDismiss = { pickingFor = null },
            onPick = { term ->
                onUpdateValue(id) {
                    // A hand-entered row has no unit yet: start from LOINC's example unit.
                    it.copy(code = term.code, name = term.name, isSuggestedCode = false, unit = it.unit.ifBlank { term.ucum })
                }
                pickingFor = null
            },
        )
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = draft.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                        onUpdateDraft { it.copy(date = date) }
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun HeaderCard(draft: ReviewDraft, onUpdateDraft: ((ReviewDraft) -> ReviewDraft) -> Unit, onPickDate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onPickDate,
                    label = { Text(formatDate(draft.date, FormatStyle.LONG)) },
                    leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.padding(0.dp)) },
                )
                when (draft.engine) {
                    ParseEngine.GEMINI_NANO -> EngineChip(Icons.Default.AutoAwesome, stringResource(R.string.engine_gemini))
                    ParseEngine.RULES -> EngineChip(Icons.Default.Rule, stringResource(R.string.engine_rules))
                    null -> Unit
                }
            }
            OutlinedTextField(
                value = draft.authorName, onValueChange = { v -> onUpdateDraft { it.copy(authorName = v) } },
                label = { Text(stringResource(R.string.lab_or_doctor)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.patientName, onValueChange = { v -> onUpdateDraft { it.copy(patientName = v) } },
                label = { Text(stringResource(R.string.patient)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (draft.engine == ParseEngine.RULES) {
                Text(
                    stringResource(R.string.engine_rules_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    AssistChip(
        onClick = {}, enabled = false, label = { Text(label) }, leadingIcon = { Icon(icon, null) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ValueRow(
    value: LabValue,
    isDuplicate: Boolean,
    onUpdate: ((LabValue) -> LabValue) -> Unit,
    onPickCode: () -> Unit,
    onRemove: () -> Unit,
) {
    val mapped = LabMapping.loincCode(value.code) != null
    var valueText by remember(value.id) {
        mutableStateOf(value.numericValue?.let(::formatNumber) ?: value.displayValue)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (value.isSelected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = value.isSelected, onCheckedChange = { checked -> onUpdate { it.copy(isSelected = checked) } })
                Column(Modifier.weight(1f)) {
                    Text(
                        value.resolvedName.ifBlank { stringResource(R.string.new_value) },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    )
                    if (value.name.isNotBlank() && value.name != value.resolvedName) {
                        Text(value.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
            }
            Row(
                Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CodeChip(value, mapped, isDuplicate, onPickCode)
                if (value.isSuggestedCode && mapped) {
                    TextButton(onClick = { onUpdate { it.copy(isSuggestedCode = false) } }) { Text(stringResource(R.string.confirm)) }
                }
            }
            Row(Modifier.padding(start = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { text ->
                        valueText = text
                        onUpdate { it.copy(displayValue = text, numericValue = parseNumberInput(text)) }
                    },
                    label = { Text(stringResource(R.string.value)) },
                    isError = value.isSelected && value.numericValue == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value.unit, onValueChange = { text -> onUpdate { it.copy(unit = text) } },
                    label = { Text(stringResource(R.string.unit)) }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CodeChip(value: LabValue, mapped: Boolean, isDuplicate: Boolean, onClick: () -> Unit) {
    val label = when {
        !mapped -> stringResource(R.string.choose_loinc)
        isDuplicate -> stringResource(R.string.loinc_duplicate, value.code)
        value.isSuggestedCode -> stringResource(R.string.loinc_suggested, value.code)
        else -> "LOINC ${value.code}"
    }
    val (container, content) = when {
        !mapped || isDuplicate -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        value.isSuggestedCode -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    AssistChip(
        onClick = onClick, label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = container, labelColor = content),
        border = null,
    )
}

private val previewDraft = ReviewDraft(
    PreviewSamples.reviewValues, LocalDate(2026, 5, 6), "Max Mustermann", "Labor Dr. Muster", ParseEngine.GEMINI_NANO,
)

@Preview(name = "Parsed", widthDp = 400, heightDp = 900)
@Composable
private fun ReviewPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewDraft, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "Rules fallback – dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewRulesPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ReviewScreen(previewDraft.copy(engine = ParseEngine.RULES), {}, { _, _ -> }, { "" }, {}, {}, {})
    }
}

@Preview(name = "Manual entry", widthDp = 400, heightDp = 600)
@Composable
private fun ReviewManualPreview() {
    LabImporterTheme(dynamicColor = false) {
        ReviewScreen(
            previewDraft.copy(values = listOf(LabValue(code = "", name = "", displayValue = "", numericValue = null, unit = "")), engine = null),
            {}, { _, _ -> }, { "" }, {}, {}, {},
        )
    }
}
