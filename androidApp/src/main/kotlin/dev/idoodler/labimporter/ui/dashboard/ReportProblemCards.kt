package dev.idoodler.labimporter.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.data.ReportsLoadProblem
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** What each [ReportsLoadProblem] offers. */
data class LoadProblemActions(
    val onTryAgain: () -> Unit = {},
    val onAllowAccess: () -> Unit = {},
    val onUseAppStorage: () -> Unit = {},
)

/**
 * The reports couldn't be read just now, which must never look like there being none. Says
 * why and offers what helps: trying again does nothing for a withdrawn permission.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsLoadFailedCard(problem: ReportsLoadProblem, actions: LoadProblemActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    ReportProblemCard(stringResource(problem.title), stringResource(problem.message), modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (problem) {
                ReportsLoadProblem.READ_FAILED -> Button(onClick = actions.onTryAgain) { Text(stringResource(R.string.try_again)) }
                ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF -> {
                    Button(onClick = actions.onAllowAccess) { Text(stringResource(R.string.hc_allow_access)) }
                    OutlinedButton(onClick = actions.onUseAppStorage) { Text(stringResource(R.string.hc_use_app_storage)) }
                }
                ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED -> {
                    Button(onClick = { openHealthConnectUpdate(context) }) { Text(stringResource(R.string.hc_update)) }
                    OutlinedButton(onClick = actions.onUseAppStorage) { Text(stringResource(R.string.hc_use_app_storage)) }
                }
                ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE -> {
                    Button(onClick = actions.onUseAppStorage) { Text(stringResource(R.string.hc_use_app_storage)) }
                    OutlinedButton(onClick = actions.onTryAgain) { Text(stringResource(R.string.try_again)) }
                }
            }
        }
    }
}

private val ReportsLoadProblem.title: Int
    get() = when (this) {
        ReportsLoadProblem.READ_FAILED -> R.string.reports_load_failed_title
        ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF -> R.string.hc_access_off_title
        ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED -> R.string.hc_needs_update_title
        ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE -> R.string.hc_gone_title
    }

private val ReportsLoadProblem.message: Int
    get() = when (this) {
        ReportsLoadProblem.READ_FAILED -> R.string.reports_load_failed_message
        ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF -> R.string.hc_access_off_message
        ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED -> R.string.hc_needs_update_message
        ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE -> R.string.hc_gone_message
    }

/** Opens Health Connect's store page, the only place it can be updated. A device without a store just says what's needed. */
fun openHealthConnectUpdate(context: Context) {
    val page = "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, page).putExtra("overlay", true).putExtra("callerId", context.packageName))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.hc_update_required, Toast.LENGTH_LONG).show()
    }
}

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/** [count] saved reports came from a newer version of the app. Updating it brings them back. */
@Composable
fun NewerVersionReportsCard(count: Int, modifier: Modifier = Modifier) {
    ReportProblemCard(
        stringResource(R.string.reports_newer_title), stringResource(R.string.reports_newer_message, count), modifier,
    )
}

/** [count] saved reports can't be read. They're kept, so a later version can still read them. */
@Composable
fun UnreadableReportsCard(count: Int, modifier: Modifier = Modifier) {
    ReportProblemCard(
        stringResource(R.string.reports_unreadable_title), stringResource(R.string.reports_unreadable_message, count), modifier,
    )
}

/** The saved reports a list leaves out, as cards. Nothing when there are none. */
@Composable
fun StoredReportNotices(newerVersion: Int, unreadable: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (newerVersion > 0) NewerVersionReportsCard(newerVersion)
        if (unreadable > 0) UnreadableReportsCard(unreadable)
    }
}

@Composable
private fun ReportProblemCard(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            ShapeBadge(MaterialShapes.Cookie9Sided, MaterialTheme.colorScheme.error, Icons.Rounded.ErrorOutline, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action?.invoke()
            }
        }
    }
}

@Preview(name = "Load failed", widthDp = 400)
@Composable
private fun ReportsLoadFailedPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.READ_FAILED, LoadProblemActions()) }
}

@Preview(name = "Load failed – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReportsLoadFailedDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.READ_FAILED, LoadProblemActions()) }
}

@Preview(name = "Load failed – narrow, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun ReportsLoadFailedLargeTextPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.READ_FAILED, LoadProblemActions()) }
}

@Preview(name = "Load failed – RTL", widthDp = 400, locale = "ar")
@Composable
private fun ReportsLoadFailedRtlPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.READ_FAILED, LoadProblemActions()) }
}

@Preview(name = "Health Connect access off", widthDp = 400)
@Composable
private fun AccessOffPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF, LoadProblemActions()) }
}

@Preview(name = "Health Connect access off – narrow, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun AccessOffLargeTextPreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_ACCESS_OFF, LoadProblemActions()) }
}

@Preview(name = "Health Connect needs an update – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NeedsUpdateDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_UPDATE_REQUIRED, LoadProblemActions()) }
}

@Preview(name = "Health Connect unavailable", widthDp = 400)
@Composable
private fun UnavailablePreview() {
    LabImporterTheme(dynamicColor = false) { ReportsLoadFailedCard(ReportsLoadProblem.HEALTH_CONNECT_UNAVAILABLE, LoadProblemActions()) }
}

@Preview(name = "Newer reports", widthDp = 400)
@Composable
private fun NewerReportsPreview() {
    LabImporterTheme(dynamicColor = false) { NewerVersionReportsCard(2) }
}

@Preview(name = "Newer reports – dark, RTL", widthDp = 400, locale = "ar", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewerReportsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { NewerVersionReportsCard(1) }
}

@Preview(name = "Unreadable reports", widthDp = 400)
@Composable
private fun UnreadableReportsPreview() {
    LabImporterTheme(dynamicColor = false) { UnreadableReportsCard(2) }
}

@Preview(name = "Unreadable reports – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnreadableReportsDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { UnreadableReportsCard(1) }
}

@Preview(name = "Unreadable reports – narrow, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun UnreadableReportsLargeTextPreview() {
    LabImporterTheme(dynamicColor = false) { UnreadableReportsCard(12) }
}
