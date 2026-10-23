package dev.idoodler.labimporter.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/**
 * Health Connect became available (after the update to Android 16, say) while
 * the reports are still kept in the app: offer to connect, which moves them
 * there. "Not Now" hides it for good — Settings still offers the same.
 */
@Composable
fun HealthConnectOfferCard(onConnect: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            ShapeBadge(MaterialShapes.Heart, LabCategory.HEMATOLOGY.color, Icons.Rounded.Favorite, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.hc_ready_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.hc_ready_message), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onConnect) { Text(stringResource(R.string.hc_connect)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
                }
            }
        }
    }
}

@Preview(name = "Health Connect available", widthDp = 400)
@Composable
private fun HealthConnectOfferPreview() {
    LabImporterTheme(dynamicColor = false) { HealthConnectOfferCard({}, {}) }
}

@Preview(name = "Health Connect available – dark", widthDp = 400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HealthConnectOfferDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { HealthConnectOfferCard({}, {}) }
}

@Preview(name = "Health Connect available – narrow, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun HealthConnectOfferLargeTextPreview() {
    LabImporterTheme(dynamicColor = false) { HealthConnectOfferCard({}, {}) }
}

@Preview(name = "Health Connect available – RTL", widthDp = 400, locale = "ar")
@Composable
private fun HealthConnectOfferRtlPreview() {
    LabImporterTheme(dynamicColor = false) { HealthConnectOfferCard({}, {}) }
}
