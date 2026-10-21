package dev.idoodler.labimporter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.model.CodeName
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabMapping
import dev.idoodler.labimporter.core.model.ReferenceRange
import dev.idoodler.labimporter.core.model.SortEditorState
import dev.idoodler.labimporter.ui.components.formatNumber
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.SectionTitle
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.dominantColors
import dev.idoodler.labimporter.ui.design.washTopBarColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/**
 * Sort & Visibility (the iOS `LabSortEditor`): reorder the dashboard by
 * dragging, pin values to the top, hide and restore them, and open a value's
 * LOINC details (where it's renamed and given a reference range). The rules
 * live in the shared [SortEditorState]; every edit saves right away.
 *
 * @param codes every code with a reading (`Dashboard.codeNames`).
 * @param onOpenDetails opens the LOINC term screen from a row; null leaves rows inert.
 */
@Composable
fun SortEditorScreen(
    codes: List<CodeName>,
    preferences: LabDisplayPreferences,
    onUpdatePreferences: ((LabDisplayPreferences) -> LabDisplayPreferences) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetails: ((String) -> Unit)? = null,
) {
    var state by remember(codes) { mutableStateOf(SortEditorState.from(preferences, codes.map { it.code })) }
    val names = remember(codes) { codes.associate { it.code to it.name } }
    val listState = rememberLazyListState()
    val edit = { updated: SortEditorState ->
        if (updated != state) {
            // A first pin adds a section above everything: stay at the top instead of
            // keeping the old first row in place (which would slide the new rows under the bar).
            val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            state = updated
            onUpdatePreferences { updated.applyTo(it) }
            if (atTop) listState.requestScrollToItem(0)
        }
    }
    val drag = remember { DragState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Box(modifier.fillMaxSize()) {
        CategoryWash(dominantColors(codes.map { LabCategory.forCode(it.code) }), intensity = 0.7f)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.sort_visibility), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                    colors = washTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                contentPadding = readableListPadding(top = padding.calculateTopPadding(), bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val sections = listOf(
                    R.string.pinned to state.pinnedCodes,
                    R.string.visible to state.unpinnedCodes,
                )
                for ((title, section) in sections) {
                    if (section.isEmpty() && title == R.string.pinned) continue
                    item(key = "title-$title") { SectionTitle(stringResource(title)) }
                    itemsIndexed(section, key = { _, code -> rowKey(code) }) { index, code ->
                        val dragging = drag.code == code
                        VisibleRow(
                            code = code,
                            name = names[code] ?: code,
                            index = index,
                            count = section.size,
                            preferences = preferences,
                            pinned = code in state.pinned,
                            onTogglePin = { edit(state.togglePin(code)) },
                            onHide = { edit(state.hide(code)) },
                            onMove = { delta -> edit(state.moveBy(code, delta)) },
                            onOpenDetails = onOpenDetails?.takeIf { LabCore.loinc?.term(code) != null }?.let { open -> { open(code) } },
                            dragKeys = arrayOf(code, codes),
                            onHandleDrag = {
                                detectDragGestures(
                                    onDragStart = { drag.start(code) },
                                    onDragEnd = drag::stop,
                                    onDragCancel = drag::stop,
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.offset += amount.y
                                        drag.swapIfOver(listState, state) { edit(it) }
                                    },
                                )
                            },
                            modifier = if (dragging) {
                                Modifier.zIndex(1f).graphicsLayer { translationY = drag.offset; shadowElevation = 12f }
                            } else {
                                Modifier.animateItem()
                            },
                        )
                    }
                }
                if (state.hiddenCodes.isNotEmpty()) {
                    item(key = "title-hidden") { SectionTitle(stringResource(R.string.hidden)) }
                    itemsIndexed(state.hiddenCodes, key = { _, code -> "hidden-$code" }) { index, code ->
                        GroupedListItem(
                            index, state.hiddenCodes.size,
                            leading = { Dot(LabCategory.forCode(code), Modifier.alpha(0.4f)) },
                            headline = { Text(names[code] ?: code, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailing = { TextButton(onClick = { edit(state.restore(code)) }) { Text(stringResource(R.string.restore)) } },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

private fun rowKey(code: String) = "row-$code"

/** The row being dragged and how far it has moved since its last swap. */
private class DragState {
    var code by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)

    fun start(code: String) {
        this.code = code
        offset = 0f
    }

    fun stop() {
        code = null
        offset = 0f
    }

    /** Swaps the dragged row with a neighbour of its section once its centre crosses into it. */
    fun swapIfOver(list: LazyListState, state: SortEditorState, edit: (SortEditorState) -> Unit) {
        val code = code ?: return
        val items = list.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == rowKey(code) } ?: return
        val centre = current.offset + offset + current.size / 2f
        val section = if (code in state.pinned) state.pinnedCodes else state.unpinnedCodes
        val target = items.firstOrNull { item ->
            item.key != current.key && (item.key as? String)?.removePrefix("row-") in section &&
                centre > item.offset && centre < item.offset + item.size
        } ?: return
        val from = section.indexOf(code)
        val to = section.indexOf((target.key as String).removePrefix("row-"))
        edit(state.move(code in state.pinned, from, if (to > from) to + 1 else to))
        // The row now sits where the target was: keep it under the finger.
        offset -= (target.offset - current.offset)
    }
}

@Composable
private fun VisibleRow(
    code: String,
    name: String,
    index: Int,
    count: Int,
    preferences: LabDisplayPreferences,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onHide: () -> Unit,
    onMove: (Int) -> Unit,
    onOpenDetails: (() -> Unit)?,
    dragKeys: Array<Any>,
    onHandleDrag: suspend PointerInputScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val detailsLabel = stringResource(R.string.details)
    GroupedListItem(
        index, count,
        // Tapping a row opens its LOINC details (rename, reference range), keeping the row's width for the name.
        onClick = onOpenDetails,
        modifier = modifier.semantics {
            if (onOpenDetails != null) onClick(label = detailsLabel) { onOpenDetails(); true }
            // Dragging needs a pointer; TalkBack and switch users move rows with actions.
            customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(-1); true })
                if (index < count - 1) add(CustomAccessibilityAction(moveDown) { onMove(1); true })
            }
        },
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.DragHandle, null, Modifier.pointerInput(*dragKeys, block = onHandleDrag),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Dot(LabCategory.forCode(code))
            }
        },
        headline = { Text(LabMapping.displayName(code).ifEmpty { name }) },
        supporting = {
            val nickname = preferences.nickname(code)
            val range = preferences.referenceRange(code)
            if (nickname != null || range != null) {
                Column {
                    if (nickname != null) Text(LabMapping.catalogName(code), style = MaterialTheme.typography.bodySmall)
                    if (range != null) Text(range.formatted(number = ::formatNumber), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailing = {
            Row {
                IconButton(onClick = onTogglePin) {
                    if (pinned) {
                        Icon(Icons.Rounded.PushPin, stringResource(R.string.unpin), tint = PIN_COLOR)
                    } else {
                        Icon(Icons.Outlined.PushPin, stringResource(R.string.pin))
                    }
                }
                IconButton(onClick = onHide) { Icon(Icons.Rounded.VisibilityOff, stringResource(R.string.hide)) }
            }
        },
    )
}

/** The iOS pin's yellow. */
private val PIN_COLOR = Color(0xFFFFCC00)

@Composable
private fun Dot(category: LabCategory, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(category.color, CircleShape))
}

private val previewCodes = listOf(
    CodeName("4548-4", "HbA1c"), CodeName("2160-0", "Creatinine"), CodeName("2093-3", "Cholesterol"),
    CodeName("3016-3", "TSH"), CodeName("718-7", "Hemoglobin"),
)

@Preview(name = "Pinned, visible and hidden", widthDp = 400, heightDp = 900)
@Composable
private fun SortEditorPreview() {
    LabImporterTheme(dynamicColor = false) {
        SortEditorScreen(
            previewCodes,
            LabDisplayPreferences(
                pinnedCodes = listOf("4548-4"), hiddenCodes = listOf("718-7"),
                nicknames = mapOf("2160-0" to "Kreatinin"), referenceRanges = mapOf("2160-0" to ReferenceRange(0.5, 1.2)),
            ),
            {}, {},
        )
    }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SortEditorDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        SortEditorScreen(previewCodes, LabDisplayPreferences(pinnedCodes = listOf("2093-3")), {}, {})
    }
}

@Preview(name = "Nothing pinned or hidden", widthDp = 400, heightDp = 700)
@Composable
private fun SortEditorPlainPreview() {
    LabImporterTheme(dynamicColor = false) { SortEditorScreen(previewCodes.take(3), LabDisplayPreferences(), {}, {}) }
}

@Preview(name = "RTL", widthDp = 400, heightDp = 700, locale = "ar")
@Composable
private fun SortEditorRtlPreview() {
    LabImporterTheme(dynamicColor = false) {
        SortEditorScreen(previewCodes, LabDisplayPreferences(hiddenCodes = listOf("3016-3")), {}, {})
    }
}
