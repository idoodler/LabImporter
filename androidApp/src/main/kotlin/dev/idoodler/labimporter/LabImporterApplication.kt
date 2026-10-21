package dev.idoodler.labimporter

import android.app.Application
import android.content.Context
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.ai.GeminiNanoAi
import dev.idoodler.labimporter.ai.LabParserService
import dev.idoodler.labimporter.ai.SelectableAi
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.about.About
import dev.idoodler.labimporter.core.about.FundingLink
import dev.idoodler.labimporter.data.AppPreferences
import dev.idoodler.labimporter.data.HealthConnectReportStore
import dev.idoodler.labimporter.data.LocalReportStore
import dev.idoodler.labimporter.data.ReportRepository
import dev.idoodler.labimporter.ocr.TextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread
import java.util.Locale

/** Owns the process-wide [AppContainer] (the app; a test application in UI tests). */
interface AppContainerOwner {
    val container: AppContainer
}

/** Wires the shared core and the platform services once per process. */
class LabImporterApplication : Application(), AppContainerOwner {

    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val preferences = AppPreferences(this)
        container = AppContainer(this, preferences, defaultAi(preferences, appScope))
        // Like iOS's launch check: is the on-device model usable (or does it need downloading)?
        appScope.launch { container.ai.refresh() }
        // Unpacking the 34 MB catalog after an install/update takes a moment;
        // do it off the main thread. MainActivity keeps the system splash
        // screen up until it's done.
        thread(name = "loinc-install") {
            LabCore.start(
                databasePath = installLoincDatabase(this).absolutePath,
                appLanguage = Locale.getDefault().toLanguageTag(),
                preferences = { preferences.displayPreferences.value },
            )
            catalogReady.value = true
        }
    }

    /** Work that outlives screens, e.g. the model download. */
    private val appScope: CoroutineScope = MainScope()

    companion object {
        /** True once the LOINC catalog is installed and `LabCore` is started. */
        val catalogReady = MutableStateFlow(false)
    }

    /**
     * SQLite can't open a file inside the APK, so the bundled catalog is
     * copied to no-backup storage once per app version.
     */
    private fun installLoincDatabase(context: Context): File {
        val target = File(context.noBackupFilesDir, "loinc.db")
        val stamp = File(context.noBackupFilesDir, "loinc.version")
        // Re-copied whenever the app is updated (same version code from a fresh install too).
        val version = "${BuildConfig.VERSION_CODE}-${context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime}"
        if (!target.exists() || !stamp.exists() || stamp.readText() != version) {
            context.assets.open("loinc.db").use { input -> target.outputStream().use { input.copyTo(it) } }
            stamp.writeText(version)
        }
        return target
    }
}

/** Gemini Nano; in debug builds optionally the development stand-in (see [DevelopmentStandIn]). */
fun defaultAi(preferences: AppPreferences, scope: CoroutineScope) =
    SelectableAi(GeminiNanoAi(scope), DevelopmentStandIn.create(), preferences.useDevelopmentStandIn, scope)

/** Simple manual DI: everything long-lived the UI needs. */
class AppContainer(context: Context, val preferences: AppPreferences, val ai: SelectableAi) {
    val softwareIdentity: String = "LabImporter ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android"
    val appInfo = AppInfo(context)
    val healthConnect = HealthConnectReportStore(context, softwareIdentity)
    val repository = ReportRepository(LocalReportStore(context, softwareIdentity), healthConnect, preferences)
    val parser = LabParserService(ai)
    val textExtractor = TextExtractor(context)
}

/** Build facts and project links for Settings — the iOS `AppInfo`, from BuildConfig and bundled assets. */
class AppInfo(private val context: Context) {
    val version: String = BuildConfig.VERSION_NAME
    val build: String = BuildConfig.VERSION_CODE.toString()

    /** The repository this build came from (null outside a git checkout); hides the GitHub rows. */
    val repositoryUrl: String? = About.repositoryWebUrl(BuildConfig.GIT_REMOTE)

    val newIssueUrl: String? = repositoryUrl?.let { About.newIssueUrl(it, version, build, BuildConfig.GIT_BRANCH, BuildConfig.GIT_COMMIT) }

    /** From the repo's `.github/FUNDING.yml`; empty hides the Support section. */
    val fundingLinks: List<FundingLink> by lazy { asset("funding.yml")?.let { About.fundingLinks(About.parseFundingYaml(it)) }.orEmpty() }

    /** The app's license (the repo's LICENSE file). */
    val licenseText: String? by lazy { asset("license") }

    private fun asset(name: String): String? = runCatching { context.assets.open(name).use { it.readBytes().decodeToString() } }.getOrNull()
}
