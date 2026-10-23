package dev.idoodler.labimporter.ai

import android.util.Log
import dev.idoodler.labimporter.ai.gemma.DownloadStatus
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import dev.idoodler.labimporter.ai.gemma.GemmaModelStore
import dev.idoodler.labimporter.ai.gemma.GemmaRuntime
import dev.idoodler.labimporter.ai.gemma.GemmaSession
import dev.idoodler.labimporter.ai.gemma.ModelDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gemma 4 E2B through LiteRT-LM, for phones that don't offer Gemini Nano to
 * apps — downloaded once, only when the user asks ([download]), verified
 * against its pinned SHA-256, and then run entirely on the phone like Gemini
 * Nano. Not downloaded yet, it is [AiAvailability.Unsupported] with
 * `modelDownload` (the unsupported screen offers it); while it downloads,
 * [AiAvailability.Preparing]. A failed download or load stays failed until
 * [tryAgain] — 2.6 GB never restart by themselves.
 *
 * The download runs in Android's download manager, so it carries on while
 * LabImporter is in the background or closed; when it completes, a
 * background job ([finishDownload], see `ModelDownloadReceiver`) checks and
 * installs it, and [events] tell a user who isn't looking how it went.
 *
 * The model loads on first use (GPU, else CPU) and is released when the app
 * goes to the background ([onUiHidden]), freeing its memory.
 *
 * @param scope outlives screens — downloads are watched and the model is loaded in it.
 */
class GemmaAi(
    private val store: GemmaModelStore,
    private val downloads: ModelDownloads,
    private val runtime: GemmaRuntime,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val pollMillis: Long = POLL_MS,
) : OnDeviceAi {

    private val state = MutableStateFlow<AiAvailability>(AiAvailability.Checking)
    override val availability: StateFlow<AiAvailability> = state.asStateFlow()

    private val _installed = MutableStateFlow(false)
    /** The verified model is on the phone (also while Gemini Nano does the reading — so it can be removed). */
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 8)
    /** What the download just did — for the notifications shown while LabImporter isn't on screen. */
    val events: SharedFlow<ModelEvent> = _events.asSharedFlow()

    private var watch: Job? = null
    /**
     * One decision at a time about starting, restarting or removing the download: the user's
     * Download and the refresh on returning from the permission dialog once both started one.
     */
    private val startLock = Mutex()
    /** One path at a time finishes a download: the in-app watcher or the background job ([finishDownload]). */
    private val installLock = Mutex()
    private val sessionLock = Mutex()
    private var loading: Deferred<GemmaSession?>? = null
    private var session: GemmaSession? = null
    @Volatile private var generating = false
    @Volatile private var visible = false

    override suspend fun refresh() = withContext(io) {
        _installed.value = store.isInstalled
        store.deviceProblem?.let { problem ->
            state.value = AiAvailability.Unsupported(UnsupportedReason.DEVICE, problem)
            return@withContext
        }
        if (store.loading) {
            // The app died while loading the model last time (see GemmaModelStore.loading).
            store.loading = false
            store.lastFailure = AiProblem.LOAD_FAILED
        }
        startLock.withLock {
            val failure = store.lastFailure
            val downloadId = store.downloadId
            when {
                failure != null -> state.value = AiAvailability.PreparationFailed(failure)
                store.isInstalled -> state.value = AiAvailability.Ready
                downloadId != null -> watch(downloadId)
                store.unverifiedFile != null -> installLock.withLock { if (!store.isInstalled) verifyAndInstall() }
                store.wanted -> startDownload()
                else -> state.value = AiAvailability.Unsupported(UnsupportedReason.DEVICE, modelDownload = true)
            }
        }
    }

    override suspend fun tryAgain() {
        withContext(io) { store.lastFailure = null }
        refresh()
    }

    /** The user's go-ahead: fetch the model (Wi-Fi only unless [allowMobileData]). */
    suspend fun download(allowMobileData: Boolean) = withContext(io) {
        startLock.withLock {
            store.wanted = true
            store.allowMobileData = allowMobileData
            store.lastFailure = null
            // A download waiting for Wi-Fi is restarted with mobile data allowed.
            watch?.cancel()
            store.downloadId?.let { downloads.remove(it) }
            store.downloadId = null
            startDownload()
        }
    }

    /** Cancels a download or deletes the model — back to the offer. */
    suspend fun remove() {
        startLock.withLock {
            watch?.cancel()
            store.downloadId?.let { withContext(io) { downloads.remove(it) } }
            closeSession()
            withContext(io) { store.delete() }
            _installed.value = false
            state.value = AiAvailability.Unsupported(UnsupportedReason.DEVICE, modelDownload = true)
        }
    }

    /**
     * Finishes the system download if it's done: checks and installs the
     * file, or records why it failed. The background job runs this when the
     * download manager reports completion — also when LabImporter isn't open.
     */
    suspend fun finishDownload() = withContext(io) {
        val id = store.downloadId ?: return@withContext
        val status = downloads.status(id)
        if (status.isFinal) settle(id, status)
    }

    /** Call with [startLock] held. */
    private suspend fun startDownload() {
        val free = withContext(io) { store.freeBytes() }
        val destination = store.downloadFile
        if (free == null || destination == null) return fail(AiProblem.DOWNLOAD_FAILED, "no external files folder", announce = true)
        if (free < store.requiredFreeBytes) {
            return fail(AiProblem.NOT_ENOUGH_STORAGE, "$free bytes free", announce = true)
        }
        val id = withContext(io) {
            destination.delete() // The download manager would pick another name rather than overwrite.
            downloads.start(destination, store.allowMobileData)
        }
        store.downloadId = id
        watch(id)
    }

    /** Mirrors the system download in [availability] while the app runs; the download itself doesn't depend on it. */
    private fun watch(id: Long) {
        if (watch?.isActive == true) return
        if (state.value !is AiAvailability.Preparing) state.value = AiAvailability.Preparing(0, GemmaModel.SIZE_BYTES)
        watch = scope.launch {
            while (isActive && store.downloadId == id) {
                when (val status = withContext(io) { downloads.status(id) }) {
                    is DownloadStatus.Running ->
                        state.value = AiAvailability.Preparing(status.bytes, status.totalBytes ?: GemmaModel.SIZE_BYTES)
                    is DownloadStatus.Waiting -> state.value = AiAvailability.Preparing(
                        status.bytes, status.totalBytes ?: GemmaModel.SIZE_BYTES,
                        if (status.forWifi) PreparingStage.WAITING_FOR_WIFI else PreparingStage.WAITING_FOR_NETWORK,
                    )
                    else -> return@launch withContext(io) { settle(id, status) }
                }
                delay(pollMillis)
            }
        }
    }

    private suspend fun settle(id: Long, status: DownloadStatus) = installLock.withLock {
        if (store.downloadId != id) return@withLock // The other path got there first.
        store.downloadId = null
        when (status) {
            DownloadStatus.Successful -> {
                verifyAndInstall()
                // The file was moved (or deleted); this only clears the system's entry.
                downloads.remove(id)
            }
            is DownloadStatus.Failed -> {
                downloads.remove(id)
                fail(if (status.insufficientSpace) AiProblem.NOT_ENOUGH_STORAGE else AiProblem.DOWNLOAD_FAILED, status.detail, announce = true)
            }
            DownloadStatus.Missing -> fail(AiProblem.DOWNLOAD_FAILED, "download $id no longer known", announce = true)
            is DownloadStatus.Running, is DownloadStatus.Waiting -> Unit
        }
    }

    /** Call with [installLock] held. */
    private fun verifyAndInstall() {
        state.value = AiAvailability.Preparing(GemmaModel.SIZE_BYTES, GemmaModel.SIZE_BYTES, PreparingStage.VERIFYING)
        _events.tryEmit(ModelEvent.Verifying)
        val installed = store.install()
        _installed.value = installed
        if (installed) {
            state.value = AiAvailability.Ready
            _events.tryEmit(ModelEvent.Installed)
        } else {
            fail(AiProblem.DOWNLOAD_FAILED, "SHA-256 mismatch", announce = true)
        }
    }

    private fun fail(problem: AiProblem, detail: String, announce: Boolean = false) {
        Log.w(TAG, "Gemma model: $problem ($detail)")
        store.lastFailure = problem
        state.value = AiAvailability.PreparationFailed(problem)
        if (announce) _events.tryEmit(ModelEvent.Failed(problem))
    }

    override suspend fun prewarm() {
        if (state.value != AiAvailability.Ready) return
        try {
            loadedSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            Log.i(TAG, "Warm-up failed: ${e.problem}") // The parse reports it.
        }
    }

    /** The loaded model, loading it first (GPU, else CPU). Loading runs in [scope], so a cancelled parse doesn't strand it. */
    private suspend fun loadedSession(): GemmaSession {
        val pending = sessionLock.withLock {
            session?.let { return it }
            loading ?: scope.async(compute) { load() }.also { loading = it }
        }
        val loaded = pending.await()
        sessionLock.withLock { if (loading === pending) loading = null }
        return loaded ?: throw AiException(AiProblem.LOAD_FAILED)
    }

    private suspend fun load(): GemmaSession? {
        val path = store.modelFile?.takeIf { store.isInstalled }?.absolutePath ?: return null
        store.loading = true
        val loaded = listOf(true, false).firstNotNullOfOrNull { gpu ->
            try {
                runtime.load(path, gpu).also { Log.i(TAG, "Gemma loaded on the ${if (gpu) "GPU" else "CPU"}") }
            } catch (e: Throwable) {
                Log.w(TAG, "Gemma didn't load on the ${if (gpu) "GPU" else "CPU"}", e)
                null
            }
        }
        store.loading = false
        if (loaded == null) {
            fail(AiProblem.LOAD_FAILED, "no backend loaded the model")
            return null
        }
        sessionLock.withLock { session = loaded }
        if (!visible && !generating) closeSession()
        return loaded
    }

    override suspend fun generate(instructions: String, prompt: String, responseSchema: String?, onPartialReply: (String) -> Unit): String {
        // Set before loading: a model that finishes loading after the app went to the background
        // must not be released under this parse (see load()).
        generating = true
        try {
            val session = loadedSession()
            val reply = StringBuilder()
            var pieces = 0
            session.generate(instructions, prompt, responseSchema, MAX_OUTPUT_TOKENS).flowOn(compute).collect { piece ->
                reply.append(piece)
                pieces++
                onPartialReply(reply.toString())
            }
            // Constrained output always closes its object; one that doesn't was cut off at a limit.
            if (pieces >= MAX_OUTPUT_TOKENS || (responseSchema != null && !reply.trimEnd().endsWith("}"))) {
                throw AiException(AiProblem.DOCUMENT_TOO_LONG)
            }
            return reply.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Generation failed", e)
            throw AiException(GemmaErrors.generationProblem(e), e)
        } finally {
            generating = false
            if (!visible) closeSession()
        }
    }

    /** The app went to the background: free the model's memory (after a parse that's still running). */
    fun onUiHidden() {
        visible = false
        if (!generating) scope.launch { closeSession() }
    }

    fun onUiVisible() {
        visible = true
    }

    private suspend fun closeSession() {
        sessionLock.withLock {
            session?.close()
            session = null
        }
    }

    private companion object {
        const val TAG = "GemmaAi"
        const val POLL_MS = 500L

        /** As for Gemini Nano: room for a long panel. */
        const val MAX_OUTPUT_TOKENS = 2_048
    }
}

/** A step of the model download worth telling a user who isn't looking at the app. */
sealed interface ModelEvent {
    data object Verifying : ModelEvent

    data object Installed : ModelEvent

    data class Failed(val problem: AiProblem) : ModelEvent
}

/** The download is over, one way or the other. */
private val DownloadStatus.isFinal: Boolean
    get() = this is DownloadStatus.Successful || this is DownloadStatus.Failed || this is DownloadStatus.Missing

/** How LiteRT-LM failures reach the user. */
object GemmaErrors {
    /** LiteRT-LM reports a too-long prompt as "Input token ids are too long. Exceeding the maximum number of tokens…". */
    fun generationProblem(error: Throwable): AiProblem {
        val message = error.message.orEmpty()
        return if (message.contains("too long", ignoreCase = true) || message.contains("maximum number of tokens", ignoreCase = true)) {
            AiProblem.DOCUMENT_TOO_LONG
        } else {
            AiProblem.FAILED
        }
    }
}
