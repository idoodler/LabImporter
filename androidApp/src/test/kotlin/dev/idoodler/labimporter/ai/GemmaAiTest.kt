package dev.idoodler.labimporter.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.ai.gemma.DownloadStatus
import dev.idoodler.labimporter.ai.gemma.GemmaModelStore
import dev.idoodler.labimporter.ai.gemma.GemmaRuntime
import dev.idoodler.labimporter.ai.gemma.GemmaSession
import dev.idoodler.labimporter.ai.gemma.ModelDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Scripted system downloads: [status] is what the download manager reports. */
class FakeDownloads : ModelDownloads {
    val started = mutableListOf<Boolean>()
    val removed = mutableListOf<Long>()
    var status: DownloadStatus = DownloadStatus.Running(0, null)
    var destination: File? = null
    private var next = 1L

    override fun start(destination: File, allowMobileData: Boolean): Long {
        started += allowMobileData
        this.destination = destination
        return next++
    }

    override fun status(id: Long) = status

    override fun remove(id: Long) {
        removed += id
    }
}

/** A model that loads where it's told to and streams a scripted reply. */
class FakeRuntime : GemmaRuntime {
    val loads = mutableListOf<Boolean>()
    var failGpu = false
    var failCpu = false
    var reply = listOf("{", "\"entries\":[]", "}")
    var error: Throwable? = null
    var closed = 0

    override fun load(modelPath: String, gpu: Boolean): GemmaSession {
        loads += gpu
        if ((gpu && failGpu) || (!gpu && failCpu)) throw IllegalStateException("can't run on ${if (gpu) "GPU" else "CPU"}")
        return object : GemmaSession {
            private var isClosed = false

            override fun generate(instructions: String, prompt: String, responseSchema: String?, maxOutputTokens: Int): Flow<String> = flow {
                check(!isClosed) { "engine closed" } // Like the native engine after close().
                error?.let { throw it }
                reply.forEach { emit(it) }
            }

            override fun close() {
                isClosed = true
                closed++
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class GemmaAiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("gemma_test", Context.MODE_PRIVATE)

    /** A five-byte stand-in for the 2.6 GB model, with its real SHA-256. */
    private val modelBytes = "model".toByteArray()
    private val modelSha by lazy { File(folder.root, "sha").apply { writeBytes(modelBytes) }.let(GemmaModelStore::sha256) }

    private val downloads = FakeDownloads()
    private val runtime = FakeRuntime()

    @Before
    fun clear() = prefs.edit().clear().apply()

    private fun store(
        device: GemmaModelStore.DeviceFacts = GemmaModelStore.DeviceFacts(supports64Bit = true, isLowRamDevice = false),
        free: Long = Long.MAX_VALUE,
    ) = GemmaModelStore(
        { File(folder.root, "models") }, { prefs }, { device }, File(folder.root, "litertlm"),
        expectedSize = modelBytes.size.toLong(), expectedSha256 = modelSha, freeSpace = { free },
    )

    private fun TestScope.gemma(store: GemmaModelStore = store(), scope: CoroutineScope = backgroundScope): GemmaAi {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return GemmaAi(store, downloads, runtime, scope, io = dispatcher, compute = dispatcher, pollMillis = 100)
    }

    /** The finished download as the download manager leaves it. */
    private fun finishWith(bytes: ByteArray) {
        checkNotNull(downloads.destination).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        downloads.status = DownloadStatus.Successful
    }

    private suspend fun TestScope.installed(ai: GemmaAi) {
        File(folder.root, "models").mkdirs()
        checkNotNull(store().downloadFile).writeBytes(modelBytes)
        ai.refresh()
        runCurrent()
        assertEquals(AiAvailability.Ready, ai.availability.value)
    }

    @Test
    fun theDownloadIsOfferedUntilTheUserAsks() = runTest {
        val ai = gemma()
        ai.refresh()
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE, modelDownload = true), ai.availability.value)
        assertTrue(downloads.started.isEmpty())
    }

    @Test
    fun aPhoneThatCantRunTheModelIsNotOfferedIt() = runTest {
        val ai = gemma(store(GemmaModelStore.DeviceFacts(supports64Bit = false, isLowRamDevice = false)))
        ai.refresh()
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE, "Gemma: needs 64-bit Android"), ai.availability.value)
        val lowRam = gemma(store(GemmaModelStore.DeviceFacts(supports64Bit = true, isLowRamDevice = true)))
        lowRam.refresh()
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE, "Gemma: low-RAM device"), lowRam.availability.value)
    }

    @Test
    fun theDownloadShowsProgressWaitsForWifiAndInstallsTheVerifiedFile() = runTest {
        val ai = gemma()
        val events = mutableListOf<ModelEvent>()
        backgroundScope.launch { ai.events.collect { events += it } }
        ai.download(allowMobileData = false)
        assertEquals(listOf(false), downloads.started)

        downloads.status = DownloadStatus.Running(2, 5)
        advanceTimeBy(150)
        assertEquals(AiAvailability.Preparing(2, 5), ai.availability.value)

        downloads.status = DownloadStatus.Waiting(2, 5, forWifi = true)
        advanceTimeBy(150)
        assertEquals(AiAvailability.Preparing(2, 5, PreparingStage.WAITING_FOR_WIFI), ai.availability.value)

        finishWith(modelBytes)
        advanceTimeBy(150)
        assertEquals(AiAvailability.Ready, ai.availability.value)
        assertTrue(ai.installed.value)
        assertEquals(listOf(ModelEvent.Verifying, ModelEvent.Installed), events)
        assertEquals(listOf(1L), downloads.removed)
        assertTrue(checkNotNull(store().modelFile).isFile)
        assertFalse(checkNotNull(store().downloadFile).exists())
    }

    @Test
    fun returningFromThePermissionDialogDoesNotStartASecondDownload() = runTest {
        val ai = gemma()
        // The user taps Download; the app resumes from the notification permission dialog at once.
        val tap = backgroundScope.launch { ai.download(allowMobileData = false) }
        val resume = backgroundScope.launch { ai.refresh() }
        runCurrent()
        tap.join()
        resume.join()
        assertEquals(1, downloads.started.size)
    }

    @Test
    fun aDamagedDownloadIsDeletedAndOnlyRetriedOnRequest() = runTest {
        val ai = gemma()
        ai.download(allowMobileData = true)
        finishWith("mudel".toByteArray()) // Right size, wrong bytes.
        advanceTimeBy(150)
        assertEquals(AiAvailability.PreparationFailed(AiProblem.DOWNLOAD_FAILED), ai.availability.value)
        assertFalse(checkNotNull(store().downloadFile).exists())

        // 2.6 GB never restart by themselves — not on resume, not on the next launch.
        ai.refresh()
        gemma().refresh()
        assertEquals(1, downloads.started.size)
        assertEquals(AiAvailability.PreparationFailed(AiProblem.DOWNLOAD_FAILED), ai.availability.value)

        downloads.status = DownloadStatus.Running(0, null)
        ai.tryAgain()
        assertEquals(listOf(true, true), downloads.started)
    }

    @Test
    fun aFailedDownloadSaysWhy() = runTest {
        val ai = gemma()
        val events = mutableListOf<ModelEvent>()
        backgroundScope.launch { ai.events.collect { events += it } }
        ai.download(allowMobileData = false)
        downloads.status = DownloadStatus.Failed(insufficientSpace = true, detail = "DownloadManager: error 1006")
        advanceTimeBy(150)
        assertEquals(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE), ai.availability.value)
        assertEquals(listOf(ModelEvent.Failed(AiProblem.NOT_ENOUGH_STORAGE)), events)
    }

    @Test
    fun withTooLittleSpaceTheDownloadNeverStarts() = runTest {
        val ai = gemma(store(free = 1_000))
        ai.download(allowMobileData = false)
        assertEquals(AiAvailability.PreparationFailed(AiProblem.NOT_ENOUGH_STORAGE), ai.availability.value)
        assertTrue(downloads.started.isEmpty())
    }

    @Test
    fun theBackgroundJobFinishesADownloadWhileTheAppIsAway() = runTest {
        // What a process that started the download left behind before it went away.
        val left = store()
        left.wanted = true
        left.downloadId = 7
        downloads.destination = left.downloadFile
        finishWith(modelBytes)
        // A new process, started by the download manager's broadcast: no screen, no watcher.
        val ai = gemma()
        val events = mutableListOf<ModelEvent>()
        backgroundScope.launch { ai.events.collect { events += it } }
        runCurrent()
        ai.finishDownload()
        assertEquals(AiAvailability.Ready, ai.availability.value)
        assertEquals(listOf(ModelEvent.Verifying, ModelEvent.Installed), events)
        assertEquals(listOf(7L), downloads.removed)
    }

    @Test
    fun theJobAndTheWatcherInstallItOnce() = runTest {
        val ai = gemma()
        val events = mutableListOf<ModelEvent>()
        backgroundScope.launch { ai.events.collect { events += it } }
        ai.download(allowMobileData = false)
        finishWith(modelBytes)
        backgroundScope.launch { ai.finishDownload() }
        advanceTimeBy(150)
        assertEquals(AiAvailability.Ready, ai.availability.value)
        assertEquals(1, events.count { it == ModelEvent.Installed })
        assertEquals(listOf(1L), downloads.removed)
    }

    @Test
    fun itLoadsOnTheGpuElseTheCpuAndReadsReports() = runTest {
        val ai = gemma()
        installed(ai)
        ai.onUiVisible()
        runtime.failGpu = true
        assertEquals("{\"entries\":[]}", ai.generate("i", "p", "{}") {})
        assertEquals(listOf(true, false), runtime.loads)
        // Loaded once; the next report reuses it.
        ai.generate("i", "p", "{}") {}
        assertEquals(2, runtime.loads.size)
    }

    @Test
    fun aModelNoBackendCanRunFailsWithItsOwnReason() = runTest {
        val ai = gemma()
        installed(ai)
        runtime.failGpu = true
        runtime.failCpu = true
        try {
            ai.generate("i", "p", "{}") {}
            fail("expected LOAD_FAILED")
        } catch (e: AiException) {
            assertEquals(AiProblem.LOAD_FAILED, e.problem)
        }
        assertEquals(AiAvailability.PreparationFailed(AiProblem.LOAD_FAILED), ai.availability.value)
        runtime.failCpu = false
        ai.tryAgain()
        assertEquals(AiAvailability.Ready, ai.availability.value)
    }

    @Test
    fun aLoadThatTookTheAppDownIsNotRepeatedOnLaunch() = runTest {
        installed(gemma())
        store().loading = true // As left by a process that died while loading.
        val ai = gemma()
        ai.refresh()
        assertEquals(AiAvailability.PreparationFailed(AiProblem.LOAD_FAILED), ai.availability.value)
        ai.tryAgain()
        assertEquals(AiAvailability.Ready, ai.availability.value)
    }

    @Test
    fun cutOffAndOverlongReportsAreTooLong() = runTest {
        val ai = gemma()
        installed(ai)
        runtime.reply = listOf("{", "\"entries\":[")
        assertProblem(AiProblem.DOCUMENT_TOO_LONG) { ai.generate("i", "p", "{}") {} }
        runtime.error = RuntimeException("Status Code: 3. Message: Input token ids are too long. Exceeding the maximum number of tokens allowed: 600 >= 256")
        assertProblem(AiProblem.DOCUMENT_TOO_LONG) { ai.generate("i", "p", "{}") {} }
        runtime.error = RuntimeException("Status Code: 13. Message: internal")
        assertProblem(AiProblem.FAILED) { ai.generate("i", "p", "{}") {} }
    }

    private suspend fun assertProblem(problem: AiProblem, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $problem")
        } catch (e: AiException) {
            assertEquals(problem, e.problem)
        }
    }

    @Test
    fun theModelIsReleasedInTheBackground() = runTest {
        val ai = gemma()
        installed(ai)
        ai.onUiVisible()
        ai.generate("i", "p", "{}") {}
        ai.onUiHidden()
        runCurrent()
        assertEquals(1, runtime.closed)
    }

    @Test
    fun aParseKeepsItsModelWhenTheAppLeavesWhileItLoads() = runTest {
        val ai = gemma()
        installed(ai)
        ai.onUiHidden() // The user switched away right after starting the import.
        assertEquals("{\"entries\":[]}", ai.generate("i", "p", "{}") {})
        assertEquals(1, runtime.closed) // Released after the parse, not before it.
    }

    @Test
    fun removingCancelsOrDeletesAndOffersItAgain() = runTest {
        val ai = gemma()
        installed(ai)
        val cache = File(folder.root, "litertlm/weights.bin").apply { parentFile?.mkdirs(); writeText("gpu weights") }
        ai.remove()
        assertFalse("the runtime's cache goes with the model", cache.exists())
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE, modelDownload = true), ai.availability.value)
        assertFalse(ai.installed.value)
        assertFalse(checkNotNull(store().modelFile).exists())

        ai.download(allowMobileData = false)
        ai.remove()
        assertEquals(listOf(1L), downloads.removed)
        ai.refresh()
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE, modelDownload = true), ai.availability.value)
    }

    @Test
    fun gemmaReadsOnlyWhereTheSystemSaysGeminiNanoIsUnsupported() = runTest {
        val gemini = FakeAi(AiAvailability.Unsupported(UnsupportedReason.DEVICE, "Prompt API: UNAVAILABLE"))
        val gemma = gemma()
        val ai = SelectableAi(gemini, gemma, standIn = null, useStandIn = false, scope = backgroundScope)
        ai.refresh()
        runCurrent()
        // The system's verdict stays the reason; the offer comes from Gemma.
        assertEquals(AiAvailability.Unsupported(UnsupportedReason.DEVICE, "Prompt API: UNAVAILABLE", modelDownload = true), ai.availability.value)

        installed(gemma)
        runCurrent()
        assertEquals(AiAvailability.Ready, ai.availability.value)
        assertEquals(AiEngine.GEMMA, ai.engine)
        assertEquals("{\"entries\":[]}", ai.generate("i", "p", "{}") {})

        // Gemini Nano arrives (a system update): it reads, the download only takes up space now.
        gemini.state.value = AiAvailability.Ready
        gemini.reply = { "gemini" }
        runCurrent()
        assertEquals(AiEngine.GEMINI_NANO, ai.engine)
        assertEquals("gemini", ai.generate("i", "p", "{}") {})
        assertTrue(ai.gemmaInstalled.value)
    }

    @Test
    fun tryingAgainRetriesGemmaOnlyWhereItIsTheModel() = runTest {
        val gemini = FakeAi(AiAvailability.Ready)
        val gemma = gemma()
        val ai = SelectableAi(gemini, gemma, standIn = null, useStandIn = false, scope = backgroundScope)
        gemma.download(allowMobileData = false)
        downloads.status = DownloadStatus.Failed(insufficientSpace = false, detail = "x")
        advanceTimeBy(150)
        ai.tryAgain()
        assertEquals(1, gemini.tryAgains)
        assertEquals(1, downloads.started.size) // Gemini Nano reads: no new 2.6 GB download.
    }
}
