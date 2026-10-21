package dev.idoodler.labimporter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.ReferenceRange

/*
 * The per-value edits — a nickname and a reference range — offered from the
 * trend screen and the LOINC term screen (the iOS rename / reference range alerts).
 */

/** Sets or resets the nickname shown instead of the catalog name (never exported). */
@Composable
fun RenameValueDialog(
    code: String,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(preferences.nickname(code).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, null) },
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text(LabMapping.catalogName(code)) },
                supportingText = { Text(stringResource(R.string.rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onUpdatePreferences { it.withNickname(text, code) }; onDismiss() }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onUpdatePreferences { it.withNickname(null, code) }; onDismiss() }) {
                Text(stringResource(R.string.reset))
            }
        },
    )
}

/** Sets or resets the range that flags results as high or low; a blank bound means no limit. */
@Composable
fun ReferenceRangeDialog(
    code: String,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    val range = preferences.referenceRange(code)
    var low by remember { mutableStateOf(range?.low?.let(::formatNumber).orEmpty()) }
    var high by remember { mutableStateOf(range?.high?.let(::formatNumber).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Straighten, null) },
        title = { Text(stringResource(R.string.reference_range)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reference_range_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    low, { low = it }, label = { Text(stringResource(R.string.range_lower)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    high, { high = it }, label = { Text(stringResource(R.string.range_upper)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUpdatePreferences { it.withReferenceRange(ReferenceRange(parseNumberInput(low), parseNumberInput(high)), code) }
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = { onUpdatePreferences { it.withReferenceRange(null, code) }; onDismiss() }) {
                Text(stringResource(R.string.reset))
            }
        },
    )
}
