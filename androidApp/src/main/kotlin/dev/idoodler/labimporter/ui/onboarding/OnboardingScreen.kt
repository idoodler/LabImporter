package dev.idoodler.labimporter.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.data.HealthConnectAvailability
import dev.idoodler.labimporter.ui.HealthConnectState
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

/** First launch: welcome → medical disclaimer → optional Health Connect. */
@Composable
fun OnboardingScreen(
    healthConnect: HealthConnectState,
    onConnectHealthConnect: () -> Unit,
    onFinish: () -> Unit,
    initialStep: Int = 0,
) {
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(step, label = "onboarding", modifier = Modifier.safeDrawingPadding()) { current ->
            when (current) {
                0 -> Welcome(onNext = { step = 1 })
                1 -> Disclaimer(onAccept = { step = 2 })
                else -> HealthConnectStep(healthConnect, onConnectHealthConnect, onFinish)
            }
        }
    }
}

@Composable
private fun Scaffolded(content: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) { content() }
        actions()
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Scaffolded(
        content = {
            Image(painterResource(R.drawable.ic_launcher_foreground_art), null, Modifier.size(140.dp))
            Text(
                stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.welcome_subtitle), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Feature(Icons.Outlined.DocumentScanner, R.string.feature_scan)
            Feature(Icons.Outlined.AutoAwesome, R.string.feature_ai)
            Feature(Icons.Outlined.Lock, R.string.feature_private)
        },
        actions = {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.get_started)) }
        },
    )
}

@Composable
private fun Feature(icon: ImageVector, text: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Disclaimer(onAccept: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    Scaffolded(
        content = {
            Text(stringResource(R.string.disclaimer_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge)
        },
        actions = {
            Row(
                Modifier.fillMaxWidth().toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(accepted, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(stringResource(R.string.disclaimer_accept), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.continue_label))
            }
        },
    )
}

@Composable
private fun HealthConnectStep(state: HealthConnectState, onConnect: () -> Unit, onFinish: () -> Unit) {
    val available = state.availability == HealthConnectAvailability.AVAILABLE
    Scaffolded(
        content = {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Icon(Icons.Outlined.Favorite, null, Modifier.padding(24.dp).size(48.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(stringResource(R.string.hc_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    when {
                        state.granted -> R.string.onboarding_hc_connected
                        available -> R.string.onboarding_hc_message
                        else -> R.string.onboarding_hc_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
        },
        actions = {
            if (available && !state.granted) {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.hc_connect)) }
                TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.not_now)) }
            } else {
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.continue_label)) }
            }
        },
    )
}

@Preview(name = "Welcome", widthDp = 400, heightDp = 800)
@Composable
private fun WelcomePreview() {
    LabImporterTheme(dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}) }
}

@Preview(name = "Disclaimer – dark", widthDp = 400, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DisclaimerPreview() {
    LabImporterTheme(darkTheme = true, dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}, initialStep = 1) }
}

@Preview(name = "Health Connect", widthDp = 400, heightDp = 800)
@Composable
private fun HealthConnectPreview() {
    LabImporterTheme(dynamicColor = false) {
        OnboardingScreen(HealthConnectState(HealthConnectAvailability.AVAILABLE), {}, {}, initialStep = 2)
    }
}

@Preview(name = "Landscape", widthDp = 800, heightDp = 400)
@Composable
private fun WelcomeLandscapePreview() {
    LabImporterTheme(dynamicColor = false) { OnboardingScreen(HealthConnectState(), {}, {}) }
}
