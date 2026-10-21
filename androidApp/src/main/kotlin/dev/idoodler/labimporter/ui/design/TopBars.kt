package dev.idoodler.labimporter.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Top app bar colours for screens over a category wash: clear while expanded,
 * so the wash shows, and solid once content scrolls under it — Android has no
 * cheap backdrop blur like iOS bars, so a see-through bar would show the text
 * beneath.
 */
@Composable
fun washTopBarColors(scrolled: Color = MaterialTheme.colorScheme.surfaceContainer): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = scrolled)
