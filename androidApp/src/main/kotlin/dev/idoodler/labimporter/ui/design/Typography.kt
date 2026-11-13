package dev.idoodler.labimporter.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle

/** Android's largest text settings: what sits side by side in a phone's width gets a letter or two per line, so it stacks. */
val isLargeText: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalDensity.current.fontScale >= 1.5f

/** Tabular (fixed-width) digits so numbers line up and don't jitter while they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
