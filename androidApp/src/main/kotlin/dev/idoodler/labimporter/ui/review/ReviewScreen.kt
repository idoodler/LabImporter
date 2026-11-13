package dev.idoodler.labimporter.ui.review

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.LabValue
import dev.idoodler.labimporter.core.model.SaveConfirmation
import dev.idoodler.labimporter.core.model.confirmationLine
import dev.idoodler.labimporter.core.model.saveConfirmation
import dev.idoodler.labimporter.core.model.duplicateLoincCodes
import dev.idoodler.labimporter.core.model.valuesToCheckCount
import dev.idoodler.labimporter.core.model.valuesToCorrectCount
import dev.idoodler.labimporter.ui.ReviewDraft
import dev.idoodler.labimporter.ui.components.formatDate
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
import kotlinx.coroutines.launch
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
@Composable
fun ReviewScreen(
    draft: ReviewDraft,
    onUpdateDraft: ((ReviewDraft) -> ReviewDraft) -> Unit,
    onUpdateValue: (String, (LabValue) -> LabValue) -> Unit,
    onAddValue: () -> String,
    onRemoveValue: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    /** A save is under way: Save and Close stay off until it ends, so a second tap can't save the report twice. */
    isSaving: Boolean = false,
) {
    var pickingFor by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    // Asked before saving when a value is flagged or would be left out: flag, don't block (iOS the same).
    var confirmation by remember { mutableStateOf<SaveConfirmation?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val requestSave: () -> Unit = {
        val asked = draft.values.saveConfirmation()
        if (asked != null) confirmation = asked else onSave()
    }
    val duplicates = remember(draft.values) { draft.values.duplicateLoincCodes() }
    val exportable = remember(draft.values) { draft.values.count { it.isExportable } }
    val valuesToCorrect = remember(draft.values) { draft.values.valuesToCorrectCount() }
    val canSave = exportable > 0 && duplicates.isEmpty() && valuesToCorrect == 0
    val saveEnabled = canSave && !isSaving
    val valuesToCheck = remember(draft.values) { draft.values.valuesToCheckCount() }
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
                    // Off while saving: the save would finish anyway, after the review was left.
                    navigationIcon = { IconButton(onClick = onCancel, enabled = !isSaving) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) } },
                    actions = {
                        Button(onClick = requestSave, enabled = saveEnabled, modifier = Modifier.padding(end = 8.dp)) {
                            Text(stringResource(R.string.save))
                        }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                contentPadding = readableListPadding(top = padding.calculateTopPadding() + 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().imePadding(),
            ) {
                item(key = "hero") {
                    SummaryCard(draft, wash, StatusCounts(canSave, duplicates.isNotEmpty(), valuesToCorrect, valuesToCheck), categories) {
                        pickingDate = true
                    }
                }
                item(key = "values-title") {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.Bottom) {
                        SectionTitle(stringResource(R.string.lab_values))
                        Text(
                            pluralStringResource(R.plurals.exportable_count, exportable, exportable, draft.values.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, end = 8.dp),
                        )
                    }
                }
                items(draft.values, key = { it.id }) { value ->
                    ValueCard(
                        value = value,
                        isDuplicate = value.isDuplicate(duplicates),
                        fromSeveralDates = draft.severalResultDates,
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
                item(key = "details") { DetailsCard(draft, onUpdateDraft) }
                item(key = "save") {
                    Button(
                        onClick = requestSave, enabled = saveEnabled,
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
                    it.copy(code = term.code, name = LabMapping.plainName(term.code), isSuggestedCode = false, unit = it.unit.ifBlank { term.ucum })
                }
                pickingFor = null
            },
        )
    }

    confirmation?.let { asked ->
        SaveConfirmationDialog(
            asked,
            onSave = {
                confirmation = null
                onSave()
            },
            onGoBack = {
                confirmation = null
                // The values start after the summary and the "Lab values" title.
                val index = draft.values.indexOfFirst { it.id == asked.firstId }
                if (index >= 0) scope.launch { listState.animateScrollToItem(VALUES_START + index) }
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
                        onUpdateDraft { it.withDate(date) }
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** What the summary's pill says. */
private data class StatusCounts(val ready: Boolean, val duplicates: Boolean, val valuesToCorrect: Int, val valuesToCheck: Int)

/** The count, what's left to do, the categories and the report's date: above the values, kept short. */
@Composable
private fun SummaryCard(
    draft: ReviewDraft,
    wash: List<Color>,
    status: StatusCounts,
    categories: List<LabCategory>,
    onPickDate: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(
                    MaterialShapes.Sunny, MaterialTheme.colorScheme.primary,
                    if (draft.engine == AiEngine.DEVELOPMENT_STAND_IN) Icons.Rounded.Science else Icons.Rounded.AutoAwesome,
                    size = 48.dp,
                    brush = Brush.linearGradient(
                        if (wash.size > 1) wash.take(2) else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        pluralStringResource(R.plurals.value_count, draft.values.size, draft.values.size),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    StatusPill(status.ready, status.duplicates, status.valuesToCorrect, status.valuesToCheck)
                }
            }
            if (categories.isNotEmpty()) {
                // One scrolling line, however many categories: the values must start on the first screen.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large).padding(12.dp),
                )
            }
            DateRow(draft, onPickDate)
        }
    }
}

@Composable
private fun StatusPill(ready: Boolean, duplicates: Boolean, valuesToCorrect: Int, valuesToCheck: Int) {
    val quad = when {
        duplicates -> Quad(Icons.Rounded.Warning, stringResource(R.string.duplicates_short), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        valuesToCorrect > 0 -> Quad(Icons.Rounded.Warning, pluralStringResource(R.plurals.values_to_correct, valuesToCorrect, valuesToCorrect), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        valuesToCheck > 0 -> Quad(Icons.Rounded.FindInPage, pluralStringResource(R.plurals.values_to_check, valuesToCheck, valuesToCheck), tintedSurface(CheckColor, 0.35f), MaterialTheme.colorScheme.onSurface)
        ready -> Quad(Icons.Rounded.CheckCircle, stringResource(R.string.ready_to_save), tintedSurface(LabCategory.HEPATIC.color, 0.35f), MaterialTheme.colorScheme.onSurface)
        else -> Quad(Icons.Rounded.Warning, stringResource(R.string.choose_loinc), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Surface(shape = CircleShape, color = quad.c, contentColor = quad.d) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = when {
                duplicates || valuesToCorrect > 0 -> quad.d
                valuesToCheck > 0 -> CheckColor
                ready -> LabCategory.HEPATIC.color
                else -> quad.d
            }
            Icon(quad.a, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(quad.b, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The list items before the first value: the summary card and the "Lab values" title. */
private const val VALUES_START = 2

/** Each value it asks about with its plain name, result and unit; Go Back is the safe answer. */
@Composable
private fun SaveConfirmationDialog(confirmation: SaveConfirmation, onSave: () -> Unit, onGoBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onGoBack,
        title = { Text(stringResource(R.string.save_check_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmationList(stringResource(R.string.save_check_compare), confirmation.toCheck)
                ConfirmationList(stringResource(R.string.save_check_left_out), confirmation.leftOut)
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save_anyway)) } },
        dismissButton = { TextButton(onClick = onGoBack) { Text(stringResource(R.string.go_back), fontWeight = FontWeight.SemiBold) } },
    )
}

@Composable
private fun ConfirmationList(heading: String, values: List<LabValue>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(heading, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        values.forEach { Text(it.confirmationLine, style = MaterialTheme.typography.bodyMedium) }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** The colour of "compare this with the report": a caution, not the error colour of what blocks saving. */
private val CheckColor: Color get() = LabCategory.GLYCEMIC.color

/** The report's date; says so when the import found none or it may be misread, until the user changes it. */
@Composable
private fun DateRow(draft: ReviewDraft, onPickDate: () -> Unit) {
    val note = when {
        draft.dateNotFound -> stringResource(R.string.no_date_found)
        draft.dateToCheck -> stringResource(if (draft.severalResultDates) R.string.several_result_dates else R.string.date_to_check)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            onClick = onPickDate,
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .semantics { if (note != null) stateDescription = note },
        ) {
            Icon(Icons.Rounded.CalendarMonth, null)
            Spacer(Modifier.width(10.dp))
            Text(formatDate(draft.date, FormatStyle.LONG), style = MaterialTheme.typography.titleSmall)
        }
        if (note != null) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FindInPage, null, Modifier.size(18.dp), tint = CheckColor)
                Spacer(Modifier.width(6.dp))
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Lab and patient, after the values and folded to one line of what's filled
 * in: comparing values with the report is the job here. Names found in the
 * report stay in view even then: they're offers to take up.
 */
@Composable
private fun DetailsCard(draft: ReviewDraft, onUpdateDraft: ((ReviewDraft) -> ReviewDraft) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = listOf(draft.patientName, draft.authorName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = groupContainerColor())) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics {
                    if (expanded) collapse { expanded = false; true } else expand { expanded = true; true }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.patient_and_lab), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    summary.ifEmpty { stringResource(R.string.not_filled_in) }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (expanded) {
                OutlinedTextField(
                    value = draft.authorName, onValueChange = { v -> onUpdateDraft { it.copy(authorName = v) } },
                    label = { Text(stringResource(R.string.lab_or_doctor)) }, singleLine = true,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
                )
            }
            Suggestion(draft.detectedAuthorName, draft.authorName) { name -> onUpdateDraft { it.copy(authorName = name) } }
            if (expanded) {
                OutlinedTextField(
                    value = draft.patientName, onValueChange = { v -> onUpdateDraft { it.copy(patientName = v) } },
                    label = { Text(stringResource(R.string.patient)) }, singleLine = true,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
                )
            }
            Suggestion(draft.detectedPatientName, draft.patientName) { name -> onUpdateDraft { it.copy(patientName = name) } }
        }
    }
}

/** The result isn't in the report text. TalkBack hears it in full, here and on the value field. */
@Composable
private fun CompareBadge(detail: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = detail },
        shape = CircleShape, color = tintedSurface(CheckColor, 0.35f), contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FindInPage, null, Modifier.size(16.dp), tint = CheckColor)
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.compare_with_report), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The result can't be saved as written. The field's red outline alone says nothing to TalkBack or about why. */
@Composable
private fun CorrectBadge(detail: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier, shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(detail, style = MaterialTheme.typography.labelMedium)
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
    // A cumulative report's value is printed, maybe in another date's column: the badge says that, not "not found".
    fromSeveralDates: Boolean = false,
) {
    val mapped = LabMapping.loincCode(value.code) != null
    val compareDetail = stringResource(if (fromSeveralDates) R.string.several_result_dates else R.string.compare_with_report_detail)
    val includeLabel = "${value.resolvedName}, ${stringResource(R.string.include_in_export)}"
    val correctDetail = stringResource(R.string.correct_or_deselect)
    val category = if (mapped) LabCategory.forCode(value.code) else null
    // As printed, like iOS. A grouped "263,000" would read as 263.5 once edited to "263,500".
    var valueText by remember(value.id) { mutableStateOf(value.displayValue) }
    // A qualitative result ("Negativ", "1:160") or a new row may need letters; a number gets the number pad.
    val keyboard = remember(value.id) { if (value.numericValue == null) KeyboardType.Text else KeyboardType.Decimal }
    val container = when {
        !value.isSelected -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f)
        category != null -> tintedSurface(category.color, 0.12f).copy(alpha = 0.94f)
        else -> groupContainerColor()
    }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = value.isSelected, onCheckedChange = { checked -> onUpdate { it.copy(isSelected = checked) } },
                    modifier = Modifier.semantics { contentDescription = includeLabel },
                )
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
            FlowRow(
                Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                CodeChip(value, mapped, isDuplicate, onPickCode)
                if (value.isSuggestedCode && mapped) {
                    TextButton(onClick = { onUpdate { it.copy(isSuggestedCode = false) } }) { Text(stringResource(R.string.confirm)) }
                }
            }
            if (value.needsSourceCheck) CompareBadge(compareDetail, Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
            if (value.needsCorrection) CorrectBadge(correctDetail, Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
            Row(Modifier.padding(start = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                )
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { text ->
                        valueText = text
                        onUpdate { it.withEditedResult(text) }
                    },
                    label = { Text(stringResource(R.string.value)) },
                    isError = value.isSelected && !value.hasResult,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    shape = MaterialTheme.shapes.large, colors = fieldColors,
                    modifier = Modifier.weight(1f).semantics {
                        // Every row's field says "Value" otherwise: TalkBack has to say which test it is.
                        contentDescription = value.resolvedName
                        if (value.needsSourceCheck) stateDescription = compareDetail
                    },
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
    // The test's name says what's duplicated or suggested; the code stays below, as this chip is where it's chosen.
    val label = when {
        !mapped -> stringResource(R.string.choose_loinc)
        isDuplicate -> stringResource(R.string.loinc_duplicate, value.resolvedName)
        value.isSuggestedCode -> stringResource(R.string.loinc_suggested, value.resolvedName)
        else -> "LOINC ${value.code}"
    }
    val code = if (mapped && (isDuplicate || value.isSuggestedCode)) "LOINC ${value.code}" else null
    val (container, content) = when {
        !mapped || isDuplicate -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        value.isSuggestedCode -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
    }
    AssistChip(
        onClick = onClick, shape = CircleShape,
        label = {
            Column(Modifier.padding(vertical = if (code != null) 4.dp else 0.dp)) {
                Text(label)
                code?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
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

/** While the report is being saved: Save is off until it ends. */
@Preview(name = "Saving", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewSavingPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewDraft, {}, { _, _ -> }, { "" }, {}, {}, {}, isSaving = true) }
}

/** A misread value to compare, no date in the report, and names found in it. */
/** A result that can't be saved as written, next to one that can. */
private val previewToCorrect = previewDraft.copy(
    values = listOf(PreviewSamples.reviewValues.first().copy(displayValue = "neg 1", numericValue = null)) + PreviewSamples.reviewValues.drop(1),
)

@Preview(name = "To correct", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewToCorrectPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewToCorrect, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "To correct – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewToCorrectDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReviewScreen(previewToCorrect, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

private val previewToCheck = previewDraft.copy(
    values = listOf(PreviewSamples.reviewValues.first().copy(displayValue = "65", numericValue = 65.0, needsSourceCheck = true)) + PreviewSamples.reviewValues.drop(1),
    patientName = "",
    authorName = "",
    detectedPatientName = "Max Mustermann",
    detectedAuthorName = "Labor Dr. Muster",
    dateNotFound = true,
)

@Preview(name = "To check, no date, details folded", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewToCheckPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewToCheck, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "To check, no date, details folded – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewToCheckDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReviewScreen(previewToCheck, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

private val previewDateToCheck = previewDraft.copy(dateToCheck = true)

@Preview(name = "Date to check", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewDateToCheckPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewDateToCheck, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "Date to check – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewDateToCheckDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReviewScreen(previewDateToCheck, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

private val previewSeveralDates = previewDraft.copy(
    values = previewDraft.values.map { it.copy(needsSourceCheck = true) },
    dateToCheck = true,
    severalResultDates = true,
)

@Preview(name = "Several result dates", widthDp = 400, heightDp = 1100)
@Composable
private fun ReviewSeveralDatesPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewSeveralDates, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "Several result dates – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewSeveralDatesDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReviewScreen(previewSeveralDates, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "To check – small phone", widthDp = 360, heightDp = 640)
@Composable
private fun ReviewToCheckSmallPreview() {
    LabImporterTheme(dynamicColor = false) { ReviewScreen(previewToCheck, {}, { _, _ -> }, { "" }, {}, {}, {}) }
}

@Preview(name = "Development stand-in (debug) – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewStandInPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ReviewScreen(previewDraft.copy(engine = AiEngine.DEVELOPMENT_STAND_IN), {}, { _, _ -> }, { "" }, {}, {}, {})
    }
}

@Preview(name = "Qualitative results", widthDp = 400, heightDp = 900)
@Composable
private fun ReviewQualitativePreview() {
    LabImporterTheme(dynamicColor = false) {
        ReviewScreen(previewDraft.copy(values = PreviewSamples.qualitativeValues), {}, { _, _ -> }, { "" }, {}, {}, {})
    }
}

@Preview(name = "Qualitative results – dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewQualitativeDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        ReviewScreen(previewDraft.copy(values = PreviewSamples.qualitativeValues), {}, { _, _ -> }, { "" }, {}, {}, {})
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
