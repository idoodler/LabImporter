package dev.idoodler.labimporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.idoodler.labimporter.ai.AiAvailability
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.shortcuts.LauncherShortcuts
import dev.idoodler.labimporter.ui.AppViewModel
import dev.idoodler.labimporter.ui.LaunchRequest
import dev.idoodler.labimporter.ui.LabImporterRoot
import dev.idoodler.labimporter.ui.LegacyCaption
import dev.idoodler.labimporter.ui.theme.LabImporterTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Covers unpacking the catalog and the first on-device AI check, so the
        // app never flashes the dashboard before "Device Not Supported".
        installSplashScreen().setKeepOnScreenCondition {
            !LabImporterApplication.catalogReady.value || viewModel.aiAvailability.value == AiAvailability.Checking
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds, for scripted emulator runs: `--ez $EXTRA_STAND_IN true` opts into the stand-in.
        if (intent?.getBooleanExtra(EXTRA_STAND_IN, false) == true && DevelopmentStandIn.texts != null) viewModel.useDevelopmentStandIn(true)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            // Names and categories come from the LOINC catalog; compose nothing
            // until it's unpacked (the splash screen covers that moment).
            val catalogReady by LabImporterApplication.catalogReady.collectAsState()
            LabImporterTheme { if (catalogReady) LabImporterRoot(viewModel) }
        }
        // Freeform windows on Android 13 and older (Waydroid): the caption isn't always reported as an inset.
        LegacyCaption.keepContentBelow(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Launcher shortcuts, and "Open with" / "Share to" LabImporter for PDFs, images and plain text. */
    private fun handleIncoming(intent: Intent?) {
        when (intent?.action) {
            LauncherShortcuts.ACTION_SCAN -> return viewModel.request(LaunchRequest.Scan)
            LauncherShortcuts.ACTION_OPEN_METRIC -> {
                intent.getStringExtra(LauncherShortcuts.EXTRA_CODE)?.let { code ->
                    ShortcutManagerCompat.reportShortcutUsed(this, LauncherShortcuts.id(code))
                    viewModel.request(LaunchRequest.OpenMetric(code))
                }
                return
            }
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(viewModel::importText)
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) viewModel.importDocuments(listOf(uri))
    }

    companion object {
        /** Boolean launch extra (debug builds): use the development stand-in instead of Gemini Nano. */
        const val EXTRA_STAND_IN = "dev.idoodler.labimporter.DEVELOPMENT_STAND_IN"
    }
}
