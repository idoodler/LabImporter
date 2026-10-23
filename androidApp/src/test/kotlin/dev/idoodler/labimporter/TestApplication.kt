package dev.idoodler.labimporter

import android.app.Application
import dev.idoodler.labimporter.ai.FakeAi
import dev.idoodler.labimporter.ai.GemmaAi
import dev.idoodler.labimporter.ai.OnDeviceAi
import dev.idoodler.labimporter.ai.SelectableAi
import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.data.AppPreferences
import kotlinx.coroutines.MainScope

/**
 * Robolectric's application for app-level tests: the real container (local
 * report store, preferences, the shared core with the real LOINC catalog)
 * around a scripted model. Configure [ai] / [standIn] before the first
 * view model is created.
 */
class TestApplication : Application(), AppContainerOwner {
    private val preferences by lazy { AppPreferences(this) }

    override val container: AppContainer by lazy {
        AppContainer(this, preferences, SelectableAi(ai, gemma, standIn, preferences.useDevelopmentStandIn, MainScope()))
    }

    override fun onCreate() {
        super.onCreate()
        LabCore.start(checkNotNull(System.getProperty("loinc.db")), "en-US") { preferences.displayPreferences.value }
        LabImporterApplication.catalogReady.value = true
    }

    companion object {
        /** The model the next test application uses. */
        var ai: FakeAi = FakeAi()

        /** The development stand-in it may switch to (null like a release build). */
        var standIn: OnDeviceAi? = null

        /** The downloadable model behind [ai] (null: none, as on a phone that can't run it). */
        var gemma: GemmaAi? = null
    }
}
