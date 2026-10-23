package dev.idoodler.labimporter.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.duplicateLoincCodes
import dev.idoodler.labimporter.ui.ReviewDraft
import dev.idoodler.labimporter.ui.components.formatDate
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.parseNumberInput
import dev.idoodler.labimporter.ui.design.CategoryBadge
import dev.idoodler.labimporter.ui.design.CategoryChip
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.dominantColors
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.time.format.FormatStyle
import kotlin.time.Instant

/**
 * Review and correct parsed values before saving — nothing reaches the health
 * store without passing through here. Mirrors iOS `ReviewView`.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val categories = remember(draft.values) {
        draft.values.filter { LabMapping.loincCode(it.code) != null }.map { LabCategory.forCode(it.code) }
    }
    val wash = remember(categories) { dominantColors(categories) }

    // The bar turns solid once the list scrolls under it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize()) {
        CategoryWash(wash)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (draft.original != null) R.string.edit_report else R.string.review_title), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) } },
                    actions = {
                        Button(onClick = onSave, enabled = canSave, modifier = Modifier.padding(end = 8.dp)) {
                            Text(stringResource(R.string.save))
                        }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = readableListPadding(top = padding.calculateTopPadding() + 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().imePadding(),
            ) {
                item(key = "hero") {
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ShapeBadge(
                                    MaterialShapes.Sunny, MaterialTheme.colorScheme.primary,
                                    if (draft.engine == AiEngine.DEVELOPMENT_STAND_IN) Icons.Rounded.Science else Icons.Rounded.AutoAwesome,
                                    size = 60.dp,
                                    brush = Brush.linearGradient(
                                        if (wash.size > 1) wash.take(2) else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                                    ),
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.lab_report), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        pluralStringResource(R.plurals.value_count, draft.values.size, draft.values.size),
                                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(canSave, duplicates.isNotEmpty())
                                when (draft.engine) {
                                    AiEngine.GEMINI_NANO -> EngineChip(Icons.Rounded.AutoAwesome, stringResource(R.string.engine_gemini))
                                    AiEngine.GEMMA -> EngineChip(Icons.Rounded.AutoAwesome, GemmaModel.NAME)
                                    AiEngine.DEVELOPMENT_STAND_IN, null -> Unit
                                }
                            }
                            if (categories.isNotEmpty()) {
                                FlowRow(
                                    Modifier.padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    categories.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { (category, count) ->
                                        CategoryChip(category, count)
                                    }
                                }
                            }
                            // Debug builds: results of the development stand-in are samples, and say so.
                            val standIn = DevelopmentStandIn.texts
                            if (draft.engine == AiEngine.DEVELOPMENT_STAND_IN && standIn != null) {
                                Text(
                                    standIn.reviewBanner, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large).padding(12.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "details") { DetailsCard(draft, onUpdateDraft, onPickDate = { pickingDate = true }) }
                item(key = "values-title") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        SectionTitle(stringResource(R.string.lab_values), Modifier.weight(1f))
                        Text(
                            pluralStringResource(R.plurals.exportable_count, exportable, exportable, draft.values.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp, end = 8.dp),
                        )
                    }
                }
                items(draft.values, key = { it.id }) { value ->
                    ValueCard(
                        value = value,
                        isDuplicate = value.isDuplicate(duplicates),
                        onUpdate = { onUpdateValue(value.id, it) },
                        onPickCode = { pickingFor = value.id },
                        onRemove = { onRemoveValue(value.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "add") {
                    FilledTonalButton(
                        onClick = { pickingFor = onAddValue() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_value))
                    }
                }
                item(key = "save") {
                    Button(
                        onClick = onSave, enabled = canSave,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentPadding = ButtonDefaults.MediumContentPadding,
                    ) {
                        Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
                    }
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
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
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
private fun StatusPill(ready: Boolean, duplicates: Boolean) {
    val quad = when {
        duplicates -> Quad(Icons.Rounded.Warning, R.string.duplicates_short, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ready -> Quad(Icons.Rounded.CheckCircle, R.string.ready_to_save, tintedSurface(LabCategory.HEPATIC.color, 0.35f), MaterialTheme.colorScheme.onSurface)
        else -> Quad(Icons.Rounded.Warning, R.string.choose_loinc, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Surface(shape = CircleShape, color = quad.c, contentColor = quad.d) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(quad.a, null, Modifier.size(18.dp), tint = if (ready && !duplicates) LabCategory.HEPATIC.color else quad.d)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(quad.b), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun EngineChip(icon: ImageVector, label: String) {
    AssistChip(
        onClick = {}, enabled = false, label = { Text(label) }, leadingIcon = { Icon(icon, null) }, shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun DetailsCard(draft: ReviewDraft, onUpdateDraft: ((ReviewDraft) -> ReviewDraft) -> Unit, onPickDate: () -> Unit) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = groupContainerColor())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onPickDate, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.CalendarMonth, null)
                Spacer(Modifier.width(10.dp))
                Text(formatDate(draft.date, FormatStyle.LONG), style = MaterialTheme.typography.titleSmall)
            }
            OutlinedTextField(
                value = draft.authorName, onValueChange = { v -> onUpdateDraft { it.copy(authorName = v) } },
                label = { Text(stringResource(R.string.lab_or_doctor)) }, singleLine = true,
                shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
            )
            Suggestion(draft.detectedAuthorName, draft.authorName) { name -> onUpdateDraft { it.copy(authorName = name) } }
            OutlinedTextField(
                value = draft.patientName, onValueChange = { v -> onUpdateDraft { it.copy(patientName = v) } },
                label = { Text(stringResource(R.string.patient)) }, singleLine = true,
                shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
            )
            Suggestion(draft.detectedPatientName, draft.patientName) { name -> onUpdateDraft { it.copy(patientName = name) } }
        }
    }
}

/** "Detected in report: “…”" with a Use button, when the report names someone other than the field does. */
@Composable
private fun Suggestion(detected: String?, current: String, onUse: (String) -> Unit) {
    if (detected.isNullOrBlank() || detected == current.trim()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text(
            stringResource(R.string.detected_in_report, detected), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onUse(detected) }) { Text(stringResource(R.string.use), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ValueCard(
    value: LabValue,
    isDuplicate: Boolean,
    onUpdate: ((LabValue) -> LabValue) -> Unit,
    onPickCode: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapped = LabMapping.loincCode(value.code) != null
    val category = if (mapped) LabCategory.forCode(value.code) else null
    var valueText by remember(value.id) { mutableStateOf(value.numericValue?.let(::formatNumber) ?: value.displayValue) }
    val container = when {
        !value.isSelected -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f)
        category != null -> tintedSurface(category.color, 0.12f).copy(alpha = 0.94f)
        else -> groupContainerColor()
    }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = value.isSelected, onCheckedChange = { checked -> onUpdate { it.copy(isSelected = checked) } })
                if (category != null) {
                    CategoryBadge(category, size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        value.resolvedName.ifBlank { stringResource(R.string.new_value) },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    if (value.name.isNotBlank() && value.name != value.resolvedName) {
                        Text(value.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.remove)) }
            }
            Row(
                Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CodeChip(value, mapped, isDuplicate, onPickCode)
                if (value.isSuggestedCode && mapped) {
                    TextButton(onClick = { onUpdate { it.copy(isSuggestedCode = false) } }) { Text(stringResource(R.string.confirm)) }
                }
            }
            Row(Modifier.padding(start = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                )
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
                    shape = MaterialTheme.shapes.large, colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value.unit, onValueChange = { text -> onUpdate { it.copy(unit = text) } },
                    label = { Text(stringResource(R.string.unit)) }, singleLine = true,
                    shape = MaterialTheme.shapes.large, colors = fieldColors, modifier = Modifier.weight(1f),
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
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
    }
    AssistChip(
        onClick = onClick, label = { Text(label) }, shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(containerColor = container, labelColor = content),
        border = null,
    )
}

private val previewDraft = ReviewDraft(
    PreviewSamples.reviewValues, LocalDate(2026, 5, 6), "Max Mustermann", "Labor Dr. Muster", AiEngine.GEMINI_NANO,
)

@Preview(name = "Parsed", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewDraft, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "Read by Gemma", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewGemmaPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewDraft.copy(engine = AiEngine.GEMMA), {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "Development stand-in (debug) – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewStandInPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ReviewScreen(previewDraft.copy(engine = AiEngine.DEVELOPMENT_STAND_IN), {}, { _, _ -> }, { "" }, {}, {}, {})
    }
}

@Preview(name = "Manual entry", widthDp = 400, heightDp = 800)
@Composable
private fun ReviewManualPreview() {
    LabImporterTheme(dynamicColor = false) {
        ReviewScreen(
            previewDraft.copy(values = listOf(LabValue(code = "", name = "", displayValue = "", numericValue = null, unit = "")), engine = null),
            {}, { _, _ -> }, { "" }, {}, {}, {},
        )
    }
}
