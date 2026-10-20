package dev.idoodler.labimporter.ui.design

import androidx.compose.ui.text.TextStyle

/** Tabular (fixed-width) digits so numbers line up and don't jitter while they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
