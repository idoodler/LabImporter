package dev.idoodler.labimporter

import android.app.Application
import android.content.Context
import android.os.Build
import dev.idoodler.labimporter.ai.DevelopmentStandIn
import dev.idoodler.labimporter.ai.GeminiNanoAi
import dev.idoodler.labimporter.ai.LabParserService
import dev.idoodler.labimporter.ai.SelectableAi
import dev.idoodler.labimporter.core.LabCore
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
        val version = BuildConfigCompat.versionStamp(context)
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
    val softwareIdentity: String = "LabImporter ${BuildConfigCompat.versionName(context)} (${BuildConfigCompat.versionCode(context)}) Android"
    val healthConnect = HealthConnectReportStore(context, softwareIdentity)
    val repository = ReportRepository(LocalReportStore(context, softwareIdentity), healthConnect, preferences)
    val parser = LabParserService(ai)
    val textExtractor = TextExtractor(context)
}

/** Version info without enabling the BuildConfig generator. */
object BuildConfigCompat {
    private fun info(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0)

    fun versionName(context: Context): String = info(context).versionName ?: "?"

    fun versionCode(context: Context): Long =
        if (Build.VERSION.SDK_INT >= 28) info(context).longVersionCode else @Suppress("DEPRECATION") info(context).versionCode.toLong()

    fun versionStamp(context: Context): String = "${versionCode(context)}-${info(context).lastUpdateTime}"
}
