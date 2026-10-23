package dev.idoodler.labimporter.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
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
import androidx.core.net.toUri
import androidx.graphics.shapes.RoundedPolygon
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.AiEngine
import dev.idoodler.labimporter.ai.PreparingStage
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.ui.components.fileSize
import dev.idoodler.labimporter.ui.components.preparingProgress
import dev.idoodler.labimporter.ui.components.preparingTitle
import dev.idoodler.labimporter.ai.AiProblem
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.ai.messageRes
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.about.FundingLink
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.data.PatientDetails
import dev.idoodler.labimporter.data.PatientSex
import dev.idoodler.labimporter.ui.components.formatDate
import kotlinx.datetime.LocalDate
import java.time.format.FormatStyle
import dev.idoodler.labimporter.ui.HealthConnectState
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** Settings as Material 3 Expressive grouped lists with coloured shape icons. */
@Composable
fun SettingsScreen(
    patient: PatientSettings,
    healthConnect: HealthConnectState,
    onConnectHealthConnect: () -> Unit,
    onUseHealthConnect: (Boolean) -> Unit,
    ai: AiSettings,
    softwareIdentity: String,
    modifier: Modifier = Modifier,
    transfer: TransferSettings = TransferSettings(),
    /** Null at the root of a navigation-rail section (wide windows): no back arrow. */
    onBack: (() -> Unit)? = null,
    onOpenSortEditor: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    about: AboutSettings = AboutSettings(),
    search: SearchSettings = SearchSettings(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showLicense by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showAppLicense by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    Box(modifier.fillMaxSize()) {
        CategoryWash(listOf(LabCategory.ENDOCRINE.color, LabCategory.BLOOD_GAS.color, LabCategory.HEMATOLOGY.color), intensity = 0.7f)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.tab_settings), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = readableListPadding(top = padding.calculateTopPadding(), bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { SectionTitle(stringResource(R.string.settings_patient)) }
                item { PatientItems(patient) }

                item { SectionTitle(stringResource(R.string.settings_dashboard)) }
                item {
                    GroupedListItem(
                        0, 1, onClick = onOpenSortEditor,
                        leading = { Badge(MaterialShapes.Ghostish, LabCategory.LIPIDS.color, Icons.Rounded.SwapVert) },
                        headline = { Text(stringResource(R.string.sort_visibility)) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    )
                }

                item { SectionTitle(stringResource(R.string.search)) }
                item {
                    GroupedListItem(
                        0, 1,
                        leading = { Badge(MaterialShapes.Cookie7Sided, LabCategory.GLYCEMIC.color, Icons.Rounded.Search) },
                        headline = { Text(stringResource(R.string.show_latest_in_search)) },
                        trailing = { Switch(checked = search.showLatestValue, onCheckedChange = null) },
                        modifier = Modifier.toggleable(search.showLatestValue, role = Role.Switch, onValueChange = search.onShowLatestValue),
                    )
                    Text(
                        stringResource(R.string.show_latest_in_search_footer), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item { SectionTitle(stringResource(R.string.settings_storage)) }
                item { HealthConnectItem(healthConnect, onConnectHealthConnect, onUseHealthConnect) }

                item { SectionTitle(stringResource(R.string.settings_ai)) }
                item { AiItem(ai) }

                item { SectionTitle(stringResource(R.string.transfer_title)) }
                item { TransferItems(transfer) }

                item { SectionTitle(stringResource(R.string.settings_loinc)) }
                item {
                    val loinc = LabCore.loinc
                    GroupedListItem(
                        0, 2, onClick = onOpenCatalog,
                        leading = { Badge(MaterialShapes.Gem, LabCategory.COAGULATION.color, Icons.Rounded.Search) },
                        headline = { Text(stringResource(R.string.browse_catalog)) },
                        supporting = {
                            Text(stringResource(R.string.loinc_catalog_detail, loinc?.version.orEmpty(), java.text.NumberFormat.getIntegerInstance().format(loinc?.count ?: 0)))
                        },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    )
                }
                item {
                    GroupedListItem(
                        1, 2, onClick = { showLicense = true },
                        leading = { Badge(MaterialShapes.Cookie6Sided, LabCategory.ELECTROLYTES.color, Icons.AutoMirrored.Rounded.MenuBook) },
                        headline = { Text(stringResource(R.string.loinc_license)) },
                    )
                }

                item { SectionTitle(stringResource(R.string.settings_about)) }
                item {
                    // Rows shown depend on what the build knows (a fork without a remote has no GitHub rows).
                    val rows = buildList<@Composable (Int, Int) -> Unit> {
                        add { i, n ->
                            GroupedListItem(
                                i, n,
                                leading = { Badge(MaterialShapes.Clover4Leaf, LabCategory.HEPATIC.color, Icons.Rounded.Lock) },
                                headline = { Text(stringResource(R.string.privacy_title)) },
                                supporting = { Text(stringResource(R.string.privacy_message)) },
                            )
                        }
                        add { i, n ->
                            GroupedListItem(
                                i, n, onClick = { showDisclaimer = true },
                                leading = { Badge(MaterialShapes.SoftBurst, LabCategory.GLYCEMIC.color, Icons.Rounded.Info) },
                                headline = { Text(stringResource(R.string.disclaimer_title)) },
                            )
                        }
                        if (about.licenseText != null) {
                            add { i, n ->
                                GroupedListItem(
                                    i, n, onClick = { showAppLicense = true },
                                    leading = { Badge(MaterialShapes.Cookie4Sided, LabCategory.HEMATOLOGY.color, Icons.Rounded.Description) },
                                    headline = { Text(stringResource(R.string.license)) },
                                )
                            }
                        }
                        about.repositoryUrl?.let { url ->
                            add { i, n ->
                                GroupedListItem(
                                    i, n, onClick = { open(url) },
                                    leading = { Badge(MaterialShapes.Square, LabCategory.OTHER.color, Icons.Rounded.Code) },
                                    headline = { Text(stringResource(R.string.view_on_github)) },
                                    trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                                )
                            }
                        }
                        about.issueReportUrl?.let { url ->
                            add { i, n ->
                                GroupedListItem(
                                    i, n, onClick = { open(url) },
                                    leading = { Badge(MaterialShapes.Burst, LabCategory.CARDIAC.color, Icons.Rounded.BugReport) },
                                    headline = { Text(stringResource(R.string.report_issue)) },
                                    trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                                )
                            }
                        }
                        add { i, n ->
                            GroupedListItem(
                                i, n,
                                leading = { Badge(MaterialShapes.Pentagon, LabCategory.OTHER.color, Icons.Rounded.Tag) },
                                headline = { Text(stringResource(R.string.version)) },
                                supporting = { Text(softwareIdentity) },
                            )
                        }
                    }
                    Column { rows.forEachIndexed { index, row -> row(index, rows.size) } }
                }

                if (about.fundingLinks.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.support)) }
                    item {
                        Column {
                            about.fundingLinks.forEachIndexed { index, link ->
                                GroupedListItem(
                                    index, about.fundingLinks.size, onClick = { open(link.url) },
                                    leading = { Badge(MaterialShapes.Heart, LabCategory.CARDIAC.color, Icons.Rounded.Favorite) },
                                    headline = { Text(link.title) },
                                    trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                                )
                            }
                            Text(
                                stringResource(R.string.support_footer), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) },
            title = { Text(stringResource(R.string.loinc_license)) },
            text = { Text(LabCore.loinc?.license.orEmpty().take(1500)) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (showAppLicense) {
        AlertDialog(
            onDismissRequest = { showAppLicense = false },
            icon = { Icon(Icons.Rounded.Description, null) },
            title = { Text(stringResource(R.string.license)) },
            text = { Text(about.licenseText.orEmpty(), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showAppLicense = false }) { Text(stringResource(R.string.ok)) } },
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

/** Whether launcher shortcuts and search show each value's latest reading (names only when off). */
data class SearchSettings(val showLatestValue: Boolean = false, val onShowLatestValue: (Boolean) -> Unit = {})

/** Project links and the license from the build (the iOS `AppInfo`); missing ones hide their rows. */
data class AboutSettings(
    val repositoryUrl: String? = null,
    val issueReportUrl: String? = null,
    val fundingLinks: List<FundingLink> = emptyList(),
    val licenseText: String? = null,
)

/** The patient's name (for new reports) and the details shown on the PDF cover. */
data class PatientSettings(
    val name: String = "",
    val details: PatientDetails = PatientDetails(),
    val onName: (String) -> Unit = {},
    val onDetails: (PatientDetails) -> Unit = {},
)

@Composable
private fun PatientItems(patient: PatientSettings) {
    var name by remember(patient.name) { mutableStateOf(patient.name) }
    var pickingDate by remember { mutableStateOf(false) }
    val details = patient.details
    Column {
        GroupedListItem(
            0, 3,
            leading = { Badge(MaterialShapes.Cookie9Sided, LabCategory.BLOOD_GAS.color, Icons.Rounded.Badge) },
            headline = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; patient.onName(it) }, singleLine = true,
                    label = { Text(stringResource(R.string.patient)) }, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            supporting = { Text(stringResource(R.string.patient_hint), Modifier.padding(top = 4.dp)) },
        )
        GroupedListItem(
            1, 3,
            onClick = { pickingDate = true },
            leading = { Badge(MaterialShapes.Square, LabCategory.ENDOCRINE.color, Icons.Rounded.Cake) },
            headline = { Text(stringResource(R.string.date_of_birth)) },
            supporting = { Text(details.dateOfBirth?.let { formatDate(it, FormatStyle.LONG) } ?: stringResource(R.string.not_set)) },
            trailing = {
                if (details.dateOfBirth != null) {
                    IconButton(onClick = { patient.onDetails(details.copy(dateOfBirth = null)) }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.clear))
                    }
                }
            },
        )
        var choosingSex by remember { mutableStateOf(false) }
        GroupedListItem(
            2, 3,
            onClick = { choosingSex = true },
            leading = { Badge(MaterialShapes.Pill, LabCategory.CARDIAC.color, Icons.Rounded.Person) },
            headline = { Text(stringResource(R.string.sex)) },
            supporting = { Text(stringResource(details.sex?.labelRes ?: R.string.not_set)) },
            trailing = {
                Box {
                    Icon(Icons.Rounded.ArrowDropDown, null)
                    DropdownMenu(expanded = choosingSex, onDismissRequest = { choosingSex = false }) {
                        (listOf(null) + PatientSex.entries).forEach { sex ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sex?.labelRes ?: R.string.not_set)) },
                                onClick = { patient.onDetails(details.copy(sex = sex)); choosingSex = false },
                                trailingIcon = { if (sex == details.sex) Icon(Icons.Rounded.Check, null) },
                            )
                        }
                    }
                }
            },
        )
        Text(
            stringResource(R.string.patient_details_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = details.dateOfBirth?.let { it.toEpochDays() * MILLIS_PER_DAY },
            // A birthday is quicker typed than paged to, decades back.
            initialDisplayMode = if (details.dateOfBirth == null) DisplayMode.Input else DisplayMode.Picker,
            selectableDates = PastDates,
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        patient.onDetails(details.copy(dateOfBirth = LocalDate.fromEpochDays(millis / MILLIS_PER_DAY)))
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/** A date of birth is never in the future. */
private object PastDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
    override fun isSelectableYear(year: Int): Boolean = year <= java.time.Year.now().value
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

private val PatientSex.labelRes: Int
    get() = when (this) {
        PatientSex.FEMALE -> R.string.sex_female
        PatientSex.MALE -> R.string.sex_male
        PatientSex.OTHER -> R.string.sex_other
    }

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
    /** Debug builds: the development stand-in replaces the real models. */
    val usingStandIn: Boolean = false,
    val onTryAgain: () -> Unit = {},
    val onUseGemini: () -> Unit = {},
    /** Which model reads reports (Gemma where the system says Gemini Nano is unsupported). */
    val engine: AiEngine = AiEngine.GEMINI_NANO,
    /** The downloaded Gemma model is on the phone. */
    val modelInstalled: Boolean = false,
    /** Restarts the model download with mobile data allowed. */
    val onUseMobileData: () -> Unit = {},
    /** Cancels the model download or deletes the model. */
    val onRemoveModel: () -> Unit = {},
)

@Composable
private fun AiItem(ai: AiSettings) {
    val standIn = DevelopmentStandIn.texts?.takeIf { ai.usingStandIn }
    val gemma = ai.engine == AiEngine.GEMMA && standIn == null
    // The downloaded model can be managed while it downloads, once it's there — and after Gemini Nano made it unnecessary.
    val showModel = standIn == null && (ai.modelInstalled || (gemma && ai.availability !is AiAvailability.Unsupported))
    val count = if (showModel) 2 else 1
    GroupedListItem(
        0, count,
        leading = {
            Badge(
                MaterialShapes.Sunny, MaterialTheme.colorScheme.primary, Icons.Rounded.AutoAwesome,
                brush = Brush.linearGradient(listOf(LabCategory.BLOOD_GAS.color, LabCategory.ENDOCRINE.color, LabCategory.HEMATOLOGY.color)),
            )
        },
        headline = { Text(if (gemma) GemmaModel.NAME else stringResource(R.string.ai_title), fontWeight = FontWeight.SemiBold) },
        supporting = {
            Column {
                val availability = ai.availability
                if (standIn != null) {
                    Text(standIn.active)
                    OutlinedButton(onClick = ai.onUseGemini, modifier = Modifier.padding(top = 10.dp)) { Text(standIn.useGemini) }
                } else when (availability) {
                    AiAvailability.Ready -> Text(stringResource(if (gemma) R.string.ai_available_gemma else R.string.ai_available))
                    AiAvailability.Checking -> Text(stringResource(R.string.ai_checking))
                    is AiAvailability.Preparing -> {
                        Text(preparingTitle(availability, ai.engine))
                        preparingProgress(availability)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val fraction = availability.fraction.takeIf { availability.stage == PreparingStage.DOWNLOADING }
                        val modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        if (fraction != null) LinearWavyProgressIndicator(progress = { fraction }, modifier) else LinearWavyProgressIndicator(modifier)
                        if (gemma && availability.stage == PreparingStage.WAITING_FOR_WIFI) {
                            OutlinedButton(onClick = ai.onUseMobileData, modifier = Modifier.padding(top = 10.dp)) {
                                Text(stringResource(R.string.model_use_mobile_data))
                            }
                        }
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
    if (showModel) ModelItem(ai, downloading = gemma && ai.availability is AiAvailability.Preparing && !ai.modelInstalled)
}

/** The downloaded Gemma model: what it is, and cancelling or deleting it. */
@Composable
private fun ModelItem(ai: AiSettings, downloading: Boolean) {
    var confirmDelete by remember { mutableStateOf(false) }
    val size = fileSize(GemmaModel.SIZE_BYTES)
    GroupedListItem(
        1, 2,
        leading = { Badge(MaterialShapes.Cookie9Sided, LabCategory.HEMATOLOGY.color, Icons.Rounded.Download) },
        headline = { Text(stringResource(R.string.model_settings_title), fontWeight = FontWeight.SemiBold) },
        supporting = {
            Column {
                Text(stringResource(R.string.model_settings_summary, GemmaModel.NAME, size, GemmaModel.LICENSE))
                // Gemini Nano became available after the download: the model only takes up space now.
                if (ai.engine != AiEngine.GEMMA && ai.modelInstalled) {
                    Text(stringResource(R.string.model_not_needed), Modifier.padding(top = 6.dp))
                }
                OutlinedButton(
                    onClick = { if (downloading) ai.onRemoveModel() else confirmDelete = true },
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(stringResource(if (downloading) R.string.model_cancel_download else R.string.model_delete))
                }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Download, null) },
            title = { Text(stringResource(R.string.model_delete_title)) },
            text = {
                Text(stringResource(if (ai.engine == AiEngine.GEMMA) R.string.model_delete_message else R.string.model_delete_message_unneeded, size))
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; ai.onRemoveModel() }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Preview(name = "Local storage, model downloading", widthDp = 400, heightDp = 1100)
@Composable
private fun SettingsPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            PatientSettings("Max Mustermann", PatientDetails(LocalDate(1980, 4, 2), PatientSex.MALE)),
            HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED), {}, {},
            AiSettings(AiAvailability.Preparing(420_000_000, 1_000_000_000)),
            "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}

@Preview(name = "Health Connect + Gemini – dark", widthDp = 400, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(
            PatientSettings(), HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = true), {}, {},
            AiSettings(AiAvailability.Ready), "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}

@Preview(name = "Gemma ready", widthDp = 400, heightDp = 1200)
@Composable
private fun SettingsGemmaPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            PatientSettings(), HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = true), {}, {},
            AiSettings(AiAvailability.Ready, engine = AiEngine.GEMMA, modelInstalled = true),
            "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}

@Preview(name = "Gemma waiting for Wi-Fi – dark", widthDp = 400, heightDp = 1200, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsGemmaWaitingPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(
            PatientSettings(), HealthConnectState(HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED), {}, {},
            AiSettings(AiAvailability.Preparing(800_000_000, 2_588_147_712, PreparingStage.WAITING_FOR_WIFI), engine = AiEngine.GEMMA),
            "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}

@Preview(name = "Gemma no longer needed", widthDp = 400, heightDp = 1200)
@Composable
private fun SettingsGemmaUnneededPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            PatientSettings(), HealthConnectState(HealthConnectAvailability.AVAILABLE, granted = true, inUse = true), {}, {},
            AiSettings(AiAvailability.Ready, engine = AiEngine.GEMINI_NANO, modelInstalled = true),
            "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}

@Preview(name = "Gemma couldn't load", widthDp = 400, heightDp = 1200)
@Composable
private fun SettingsGemmaLoadFailedPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            PatientSettings(), HealthConnectState(HealthConnectAvailability.NOT_INSTALLED), {}, {},
            AiSettings(AiAvailability.PreparationFailed(AiProblem.LOAD_FAILED), engine = AiEngine.GEMMA, modelInstalled = true),
            "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}

@Preview(name = "Model download failed", widthDp = 400, heightDp = 1100)
@Composable
private fun SettingsDownloadFailedPreview() {
    LabImporterTheme(dynamicColor = false) {
        SettingsScreen(
            PatientSettings(), HealthConnectState(HealthConnectAvailability.NOT_INSTALLED), {}, {},
            AiSettings(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE)),
            "LabImporter 1.0 (1) Android", onBack = {},
        )
    }
}
