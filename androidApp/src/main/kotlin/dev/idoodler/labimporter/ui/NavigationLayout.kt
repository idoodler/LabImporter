package dev.idoodler.labimporter.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/*
 * Wide windows (a tablet, an unfolded phone) get a navigation rail with the
 * three sections, each with its own back stack — the Android counterpart of
 * the iOS sidebar split view (`SidebarSection`, regular × regular). Phones keep
 * the stack rooted at the dashboard, with Reports and Settings in its top bar.
 */

/** Both dimensions roomy, like iOS's regular × regular: a rotated phone stays a phone. */
fun usesRail(width: Dp, height: Dp): Boolean = width >= 840.dp && height >= 480.dp

/** A top-level section and the back-stack entry it starts from. */
enum class Section(val root: String, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    DASHBOARD("home", R.string.dashboard_title, Icons.Outlined.GridView, Icons.Rounded.GridView),
    REPORTS("reports", R.string.history_title, Icons.Outlined.Description, Icons.Rounded.Description),
    SETTINGS("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
    ;

    companion object {
        /** The section a back stack belongs to. */
        fun of(stack: List<String>): Section = entries.firstOrNull { it.root == stack.firstOrNull() } ?: DASHBOARD
    }
}

/**
 * The back stack for the other layout, so rotating or unfolding keeps the
 * user's place: phones root every stack at the dashboard; the rail roots
 * Reports and Settings at themselves.
 */
fun stackForLayout(stack: List<String>, rail: Boolean): List<String> {
    val sectionRoots = setOf(Section.REPORTS.root, Section.SETTINGS.root)
    return when {
        stack.isEmpty() -> listOf(Section.DASHBOARD.root)
        rail && stack.size >= 2 && stack[0] == Section.DASHBOARD.root && stack[1] in sectionRoots -> stack.drop(1)
        !rail && stack[0] in sectionRoots -> listOf(Section.DASHBOARD.root) + stack
        else -> stack
    }
}

@Composable
fun SectionRail(selected: Section, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.height(12.dp))
        Section.entries.forEach { section ->
            NavigationRailItem(
                selected = section == selected,
                onClick = { onSelect(section) },
                icon = { Icon(if (section == selected) section.selectedIcon else section.icon, null) },
                label = { Text(stringResource(section.label)) },
            )
        }
    }
}

@Preview(name = "Rail", heightDp = 600)
@Composable
private fun SectionRailPreview() {
    LabImporterTheme(dynamicColor = false) { SectionRail(Section.REPORTS, {}) }
}

@Preview(name = "Rail – dark", heightDp = 600, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SectionRailDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { SectionRail(Section.DASHBOARD, {}) }
}
