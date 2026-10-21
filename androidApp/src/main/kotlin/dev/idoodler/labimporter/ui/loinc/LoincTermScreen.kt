package dev.idoodler.labimporter.ui.loinc

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.loinc.LoincDetail
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.ui.components.ReferenceRangeDialog
import dev.idoodler.labimporter.ui.components.RenameValueDialog
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.components.labelRes
import dev.idoodler.labimporter.ui.design.CategoryBadge
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.accentFor
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.tintedSurface
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/**
 * One LOINC term's structured attributes — the six-part name, class, status,
 * names and units — like a loinc.org details page, offline (the iOS
 * `LoincTermDetailView`). The user's nickname and reference range for the
 * value show in the header and are edited from the menu.
 *
 * @param detail the term, from the catalog; null shows nothing (unknown code).
 */
@Composable
fun LoincTermScreen(
    code: String,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    detail: LoincDetail? = remember(code) { LabCore.loinc?.detail(code) },
) {
    val category = LabCategory.forCode(code)
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingRange by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        CategoryWash(listOf(category.color, accentFor(category.color)), intensity = 0.9f)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(code, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_reference_range)) },
                                leadingIcon = { Icon(Icons.Rounded.Straighten, null) },
                                onClick = { menu = false; editingRange = true },
                            )
                        }
                    },
                    colors = washTopBarColors(tintedSurface(category.color, 0.12f)),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = readableListPadding(top = padding.calculateTopPadding(), bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") { Header(code, category, detail, preferences) }
                if (detail != null) {
                    val attributes = listOf(
                        R.string.loinc_code to detail.code, R.string.loinc_component to detail.component,
                        R.string.loinc_property to detail.property, R.string.loinc_timing to detail.timing,
                        R.string.loinc_system to detail.system, R.string.loinc_scale to detail.scale,
                        R.string.loinc_method to detail.method, R.string.loinc_class to detail.loincClass,
                        R.string.loinc_status to detail.status,
                    ).filter { it.second.isNotBlank() }
                    val names = listOf(
                        R.string.loinc_long_name to detail.longName, R.string.loinc_short_name to detail.shortName,
                        R.string.loinc_units to detail.ucum,
                    ).filter { it.second.isNotBlank() }
                    item(key = "details-title") { SectionTitle(stringResource(R.string.details)) }
                    itemsIndexed(attributes, key = { _, a -> "a${a.first}" }) { index, (label, value) -> Attribute(index, attributes.size, label, value) }
                    if (names.isNotEmpty()) item(key = "names-gap") { Spacer(Modifier.padding(top = 16.dp)) }
                    itemsIndexed(names, key = { _, a -> "n${a.first}" }) { index, (label, value) -> Attribute(index, names.size, label, value) }
                }
                LabMapping.loincUrl(code)?.let { url ->
                    item(key = "link") {
                        Spacer(Modifier.padding(top = 16.dp))
                        GroupedListItem(
                            0, 1,
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                            leading = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
                            headline = { Text(stringResource(R.string.open_loinc), color = MaterialTheme.colorScheme.primary) },
                        )
                        Text(
                            stringResource(R.string.loinc_link_footer), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
    if (renaming) RenameValueDialog(code, preferences, onUpdatePreferences) { renaming = false }
    if (editingRange) ReferenceRangeDialog(code, preferences, onUpdatePreferences) { editingRange = false }
}

@Composable
private fun Header(code: String, category: LabCategory, detail: LoincDetail?, preferences: LabDisplayPreferences) {
    val term = remember(code) { LabCore.loinc?.term(code) }
    Card(
        colors = CardDefaults.cardColors(containerColor = tintedSurface(category.color, 0.14f).copy(alpha = 0.94f)),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(category, size = 54.dp)
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(detail?.name ?: term?.name ?: code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    CategoryChip(category)
                }
            }
            preferences.nickname(code)?.let { nickname ->
                HorizontalDivider()
                LabeledRow(Icons.Rounded.Label, category, stringResource(R.string.nickname), nickname)
            }
            preferences.referenceRange(code)?.let { range ->
                HorizontalDivider()
                LabeledRow(Icons.Rounded.Straighten, category, stringResource(R.string.reference_range), range.formatted(detail?.ucum.orEmpty(), ::formatNumber))
            }
            (detail?.description ?: term?.description)?.takeIf { it.isNotBlank() }?.let { description ->
                HorizontalDivider()
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CategoryChip(category: LabCategory) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(category.color.copy(alpha = 0.12f), CircleShape)
            .border(0.5.dp, category.color.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.size(8.dp).background(category.color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(category.labelRes), style = MaterialTheme.typography.labelMedium)
    }
}

/** A read-only labelled value (editing happens from the menu), like the iOS nickname/range rows. */
@Composable
private fun LabeledRow(icon: ImageVector, category: LabCategory, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, null, tint = category.color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Attribute(index: Int, count: Int, label: Int, value: String) {
    GroupedListItem(
        index, count,
        headline = { Text(stringResource(label)) },
        trailing = {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(180.dp))
        },
    )
}

private val previewDetail = LoincDetail(
    code = "2160-0", name = "Creatinine [Mass/volume] in Serum or Plasma", description = "Creatinine is a waste product of muscle metabolism.",
    component = "Creatinine", property = "MCnc", timing = "Pt", system = "Ser/Plas", scale = "Qn", method = "",
    loincClass = "CHEM", status = "ACTIVE", longName = "Creatinine [Mass/volume] in Serum or Plasma", shortName = "Creat SerPl-mCnc", ucum = "mg/dL",
)

@Preview(name = "With nickname and range", widthDp = 400, heightDp = 1100)
@Composable
private fun LoincTermPreview() {
    LabImporterTheme(dynamicColor = false) {
        LoincTermScreen(
            "2160-0",
            LabDisplayPreferences(nicknames = mapOf("2160-0" to "Kreatinin"), referenceRanges = mapOf("2160-0" to ReferenceRange(0.5, 1.2))),
            {}, {}, detail = previewDetail,
        )
    }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoincTermDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { LoincTermScreen("2160-0", LabDisplayPreferences(), {}, {}, detail = previewDetail) }
}

@Preview(name = "Unknown code", widthDp = 400, heightDp = 600)
@Composable
private fun LoincTermUnknownPreview() {
    LabImporterTheme(dynamicColor = false) { LoincTermScreen("KREA", LabDisplayPreferences(), {}, {}, detail = null) }
}
