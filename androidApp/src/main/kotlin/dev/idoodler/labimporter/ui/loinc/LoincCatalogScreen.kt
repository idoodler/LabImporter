package dev.idoodler.labimporter.ui.loinc

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.loinc.LoincTerm
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.ui.design.CategoryBadge
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.GroupedListItem
import dev.idoodler.labimporter.ui.design.dominantColors
import dev.idoodler.labimporter.ui.design.readableListPadding
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Terms loaded per page as the list scrolls (as on iOS). */
private const val PAGE_SIZE = 100

/**
 * Browse and search the bundled LOINC catalog (the iOS `LoincCatalogView`):
 * the same search the code picker uses, read-only, paged as the list scrolls.
 * An empty query lists the most commonly ordered tests.
 *
 * @param search the catalog query, swappable for previews.
 */
@Composable
fun LoincCatalogScreen(
    onOpenTerm: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    search: (query: String, offset: Int) -> List<LoincTerm> = { query, offset ->
        LabCore.loinc?.search(query, limit = PAGE_SIZE, offset = offset).orEmpty()
    },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<LoincTerm>()) }
    var reachedEnd by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // The search header turns solid once results scroll under it.
    val headerColor by animateColorAsState(
        if (listState.canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, label = "header",
    )

    LaunchedEffect(query) {
        loading = true
        val page = withContext(Dispatchers.IO) { search(query, 0) }
        results = page
        reachedEnd = page.size < PAGE_SIZE
        loading = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val loadMore = {
        if (!reachedEnd && !loading) {
            loading = true
            val current = query
            val offset = results.size
            scope.launch {
                val page = withContext(Dispatchers.IO) { search(current, offset) }
                if (current == query && offset == results.size) {
                    results = results + page
                    reachedEnd = page.size < PAGE_SIZE
                }
                loading = false
            }
        }
    }
    val wash = remember(results.take(30)) { dominantColors(results.take(30).map { LabCategory.forCode(it.code) }, 3) }

    Box(modifier.fillMaxSize()) {
        CategoryWash(wash, intensity = 0.6f)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(Modifier.background(headerColor)) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.loinc_catalog), fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_lab_tests)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                    )
                }
            },
        ) { padding ->
            if (results.isEmpty() && query.isNotBlank() && !loading) {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.no_results), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                return@Scaffold
            }
            LazyColumn(
                state = listState,
                contentPadding = readableListPadding(top = padding.calculateTopPadding() + 8.dp, bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(results, key = { _, term -> term.code }) { index, term ->
                    if (index == results.lastIndex) LaunchedEffect(term.code) { loadMore() }
                    GroupedListItem(
                        index, results.size,
                        onClick = { onOpenTerm(term.code) },
                        leading = { CategoryBadge(LabCategory.forCode(term.code), size = 40.dp) },
                        headline = { Text(term.name) },
                        supporting = {
                            Column {
                                term.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(term.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        },
                    )
                }
            }
        }
    }
}

private val previewTerms = listOf(
    LoincTerm("2160-0", "Creatinine [Mass/volume] in Serum or Plasma", "Creatinine", "Kidney function", "mg/dL", 1),
    LoincTerm("4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood", "HbA1c", null, "%", 2),
    LoincTerm("2093-3", "Cholesterol [Mass/volume] in Serum or Plasma", "Cholesterol", "Total cholesterol", "mg/dL", 3),
)

@Preview(name = "Top tests", widthDp = 400, heightDp = 800)
@Composable
private fun LoincCatalogPreview() {
    LabImporterTheme(dynamicColor = false) { LoincCatalogScreen({}, {}, search = { _, offset -> if (offset == 0) previewTerms else emptyList() }) }
}

@Preview(name = "Dark", widthDp = 400, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoincCatalogDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) {
        LoincCatalogScreen({}, {}, search = { _, offset -> if (offset == 0) previewTerms else emptyList() })
    }
}

@Preview(name = "Loading / empty catalog", widthDp = 400, heightDp = 600)
@Composable
private fun LoincCatalogEmptyPreview() {
    LabImporterTheme(dynamicColor = false) { LoincCatalogScreen({}, {}, search = { _, _ -> emptyList() }) }
}
