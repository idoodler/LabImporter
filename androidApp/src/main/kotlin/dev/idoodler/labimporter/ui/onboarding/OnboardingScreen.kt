package dev.idoodler.labimporter.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.ui.HealthConnectState
import dev.idoodler.labimporter.ui.design.CategoryWash
import dev.idoodler.labimporter.ui.design.MorphingShape
import dev.idoodler.labimporter.ui.design.ShapeBadge
import dev.idoodler.labimporter.ui.design.color
import dev.idoodler.labimporter.ui.design.groupContainerColor
import dev.idoodler.labimporter.ui.design.welcomePalette
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

private const val STEPS = 3

/** First launch: welcome → medical disclaimer → optional Health Connect, over a living colour wash. */
@Composable
fun OnboardingScreen(
    healthConnect: HealthConnectState,
    onConnectHealthConnect: () -> Unit,
    onFinish: () -> Unit,
    initialStep: Int = 0,
) {
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    val progress by animateFloatAsState((step + 1f) / STEPS, label = "progress")
    Box(Modifier.fillMaxSize()) {
        CategoryWash(welcomePalette, animated = true, intensity = 1.25f)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.step_of, step + 1, STEPS), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedContent(
                step, label = "onboarding",
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    0 -> Welcome(onNext = { step = 1 })
                    1 -> Disclaimer(onAccept = { step = 2 })
                    else -> HealthConnectStep(healthConnect, onConnectHealthConnect, onFinish)
                }
            }
        }
    }
}

/** A centred, scrollable page with its actions pinned below — shared by onboarding and the unsupported-device screen. */
@Composable
internal fun OnboardingPage(content: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) { content() }
        Column(Modifier.widthIn(max = 560.dp)) { actions() }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    OnboardingPage(
        content = {
            MorphingShape(welcomePalette, 208.dp) {
                Box(Modifier.size(132.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground_art), null, Modifier.size(124.dp))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.welcome_to), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.welcome_title), style = MaterialTheme.typography.displayMediumEmphasized,
                    fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.welcome_subtitle), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = groupContainerColor())) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Feature(MaterialShapes.Cookie9Sided, LabCategory.BLOOD_GAS.color, Icons.Rounded.DocumentScanner, R.string.feature_scan)
                    Feature(MaterialShapes.Sunny, LabCategory.ENDOCRINE.color, Icons.Rounded.AutoAwesome, R.string.feature_ai)
                    Feature(MaterialShapes.Clover4Leaf, LabCategory.HEPATIC.color, Icons.Rounded.Lock, R.string.feature_private)
                }
            }
        },
        actions = {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.get_started), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

@Composable
private fun Feature(polygon: RoundedPolygon, color: Color, icon: ImageVector, text: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ShapeBadge(polygon, color, icon, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Disclaimer(onAccept: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    OnboardingPage(
        content = {
            ShapeBadge(MaterialShapes.SoftBurst, LabCategory.GLYCEMIC.color, Icons.Rounded.Info, size = 112.dp)
            Text(
                stringResource(R.string.disclaimer_title), style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = groupContainerColor())) {
                Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
            }
        },
        actions = {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = groupContainerColor()),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }).padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(accepted, onCheckedChange = null, modifier = Modifier.padding(16.dp))
                    Text(stringResource(R.string.disclaimer_accept), style = MaterialTheme.typography.titleSmall)
                }
            }
            Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.continue_label), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

@Composable
private fun HealthConnectStep(state: HealthConnectState, onConnect: () -> Unit, onFinish: () -> Unit) {
    val available = state.availability == HealthConnectAvailability.AVAILABLE
    OnboardingPage(
        content = {
            ShapeBadge(
                MaterialShapes.Heart, LabCategory.HEMATOLOGY.color,
                if (state.granted) Icons.Rounded.CheckCircle else Icons.Rounded.Favorite, size = 128.dp,
            )
            Text(
                stringResource(R.string.hc_title), style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when {
                        state.granted -> R.string.onboarding_hc_connected
                        available -> R.string.onboarding_hc_message
                        else -> R.string.onboarding_hc_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            if (available && !state.granted) {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(stringResource(R.string.hc_connect), style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.not_now)) }
            } else {
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(stringResource(R.string.continue_label), style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    )
}

@Preview(name = "Welcome", widthDp = 400, heightDp = 860)
@Composable
private fun WelcomePreview() {
    LabImporterTheme(dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}) }
}

@Preview(name = "Welcome – dark", widthDp = 400, heightDp = 860, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WelcomeDarkPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}) }
}

@Preview(name = "Disclaimer", widthDp = 400, heightDp = 860)
@Composable
private fun DisclaimerPreview() {
    LabImporterTheme(dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}, initialStep = 1) }
}

@Preview(name = "Health Connect", widthDp = 400, heightDp = 860)
@Composable
private fun HealthConnectPreview() {
    LabImporterTheme(dynamicColor = false) {
        OnboardingScreen(HealthConnectState(HealthConnectAvailability.AVAILABLE), {}, {}, initialStep = 2)
    }
}

@Preview(name = "Landscape", widthDp = 860, heightDp = 400)
@Composable
private fun WelcomeLandscapePreview() {
    LabImporterTheme(dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}) }
}
