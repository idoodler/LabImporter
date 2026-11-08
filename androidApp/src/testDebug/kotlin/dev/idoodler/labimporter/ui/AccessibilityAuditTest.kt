package dev.idoodler.labimporter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ui.preview.PreviewSamples
import dev.idoodler.labimporter.ui.theme.LabImporterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What TalkBack and a finger need from every screen, walked over the Compose
 * semantics tree in light and dark: everything that acts has a name and a 48 dp
 * target, and an image either speaks or is left out of the tree.
 *
 * A semantics walk rather than Compose's `enableAccessibilityChecks()`, which
 * finds nothing under Robolectric: a nameless IconButton passes it, because Google's
 * Accessibility Test Framework needs a real view hierarchy with drawn pixels.
 * Contrast is `DesignContrastTest`'s.
 *
 * The window is tall so that lazy lists compose everything they hold.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class, qualifiers = "w411dp-h2400dp")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNode.spokenName(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }

    private fun SemanticsNode.acts(): Boolean =
        SemanticsActions.OnClick in config || SemanticsProperties.ToggleableState in config

    private fun problems(node: SemanticsNode, density: Density): List<String> {
        val own = buildList {
            val where = "${node.spokenName() ?: "unnamed"} at ${node.boundsInRoot}"
            if (node.acts()) {
                if (node.spokenName() == null) add("acts without a name: $where (role ${node.config.getOrNull(SemanticsProperties.Role)})")
                val touch = node.touchBoundsInRoot
                val width = with(density) { maxOf(touch.width, node.size.width.toFloat()).toDp().value }
                val height = with(density) { maxOf(touch.height, node.size.height.toFloat()).toDp().value }
                // Rounding of a density-scaled 48 dp can land just under it.
                if (width < 47.5f || height < 47.5f) add("target of ${width.toInt()}x${height.toInt()} dp: $where")
            }
            // Compose leaves an image with a null description out of the tree, so that much
            // is decorative by construction; one that's there and silent is the mistake.
            if (node.config.getOrNull(SemanticsProperties.Role) == Role.Image && node.spokenName() == null) {
                add("image without a description: $where")
            }
        }
        return own + node.children.flatMap { problems(it, density) }
    }

    @Test
    fun aReviewFieldSaysWhichTestItIs() {
        compose.setContent { LabImporterTheme(dynamicColor = false) { qualityScreens.first { it.name == "review" }.content() } }
        val first = PreviewSamples.reviewValues.first()
        val names = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        }
        assertTrue("$names", names.contains(first.resolvedName))
    }

    @Test
    fun everyScreenNamesWhatActsAndGivesItATarget() {
        var index by mutableStateOf(0)
        var dark by mutableStateOf(false)
        var density = Density(1f)
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current
            key(index, dark) {
                LabImporterTheme(darkTheme = dark, dynamicColor = false) { qualityScreens[index].content() }
            }
        }
        val found = buildList {
            listOf(false, true).forEach { isDark ->
                qualityScreens.indices.forEach { position ->
                    dark = isDark
                    index = position
                    compose.awaitLoaded(qualityScreens[position])
                    val root = compose.onRoot().fetchSemanticsNode()
                    problems(root, density).forEach { add("${qualityScreens[position].name} (${if (isDark) "dark" else "light"}): $it") }
                }
            }
        }
        assertEquals("Accessibility problems:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }
}
