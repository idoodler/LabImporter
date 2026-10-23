package dev.idoodler.labimporter.ai.gemma

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.StatFs
import androidx.core.content.edit
import dev.idoodler.labimporter.ai.AiProblem
import java.io.File
import java.security.MessageDigest

/**
 * The downloaded model on disk and what the app remembers about it. The file
 * lives in the app's own external files folder (where the system download
 * manager can write; private to the app, removed with it, never backed up)
 * and is only used once its SHA-256 matched [GemmaModel.SHA256].
 *
 * Everything here touches the disk: call it off the main thread.
 *
 * @param directory where the model goes; null when there's no such storage.
 * @param runtimeCache where LiteRT-LM keeps what it derives from the model
 *   (the GPU backend's converted weights — 1.5 GB on the Mac); deleted with the model.
 * @param expectedSize / [expectedSha256] the pinned file (tests use a small one).
 */
class GemmaModelStore(
    directory: () -> File?,
    preferences: () -> SharedPreferences,
    device: () -> DeviceFacts,
    val runtimeCache: File,
    private val expectedSize: Long = GemmaModel.SIZE_BYTES,
    private val expectedSha256: String = GemmaModel.SHA256,
    private val freeSpace: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    constructor(context: Context) : this(
        { context.getExternalFilesDir(MODELS_FOLDER) },
        { context.getSharedPreferences("gemma_model", Context.MODE_PRIVATE) },
        { DeviceFacts.of(context) },
        File(context.cacheDir, "litertlm"),
    )

    private val directory: File? by lazy(directory)
    private val preferences: SharedPreferences by lazy(preferences)
    private val device: DeviceFacts by lazy(device)

    /** What decides whether this phone can run the model at all. */
    data class DeviceFacts(val supports64Bit: Boolean, val isLowRamDevice: Boolean) {
        companion object {
            fun of(context: Context) = DeviceFacts(
                supports64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
                isLowRamDevice = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
            )
        }
    }

    val modelFile: File? get() = directory?.resolve(GemmaModel.FILE_NAME)

    /** Where the download manager writes; renamed to [modelFile] once verified. */
    val downloadFile: File? get() = directory?.resolve(GemmaModel.FILE_NAME + ".download")

    /**
     * Why this phone can't run the model, for the details line — or null.
     * LiteRT-LM ships 64-bit libraries only; Android's own low-RAM flag rules
     * out phones that can't hold a 2.6 GB model.
     */
    val deviceProblem: String?
        get() = when {
            !device.supports64Bit -> "Gemma: needs 64-bit Android"
            device.isLowRamDevice -> "Gemma: low-RAM device"
            else -> null
        }

    /** The verified model is in place (checked by size and timestamp; hashed once after download). */
    val isInstalled: Boolean
        get() {
            val file = modelFile ?: return false
            return file.isFile && file.length() == expectedSize &&
                file.lastModified() == preferences.getLong(VERIFIED_MODIFIED, -1L)
        }

    /** A complete-looking file that hasn't been checked yet (a finished download, or a kept model whose record was lost). */
    val unverifiedFile: File?
        get() = listOfNotNull(downloadFile, modelFile).firstOrNull { it.isFile && it.length() == expectedSize }
            ?.takeIf { !isInstalled }

    /** The user chose to download the model (cleared when they remove it). */
    var wanted: Boolean
        get() = preferences.getBoolean(WANTED, false)
        set(value) = preferences.edit { putBoolean(WANTED, value) }

    var allowMobileData: Boolean
        get() = preferences.getBoolean(MOBILE_DATA, false)
        set(value) = preferences.edit { putBoolean(MOBILE_DATA, value) }

    /** The system download in progress, if any. */
    var downloadId: Long?
        get() = preferences.getLong(DOWNLOAD_ID, -1L).takeIf { it >= 0 }
        set(value) = preferences.edit { if (value == null) remove(DOWNLOAD_ID) else putLong(DOWNLOAD_ID, value) }

    /** Why the last download or check failed; stays until the user tries again (a 2.6 GB download never restarts by itself). */
    var lastFailure: AiProblem?
        get() = preferences.getString(LAST_FAILURE, null)?.let { name -> AiProblem.entries.firstOrNull { it.name == name } }
        set(value) = preferences.edit { if (value == null) remove(LAST_FAILURE) else putString(LAST_FAILURE, value.name) }

    /**
     * Set while the model loads. Still set on the next start means the app
     * died loading it (out of memory, a driver crash) — then it isn't loaded
     * again until the user tries again, instead of crashing on every launch.
     */
    var loading: Boolean
        get() = preferences.getBoolean(LOADING, false)
        // commit(): the flag must be on disk before the load that might take the process down.
        set(value) = preferences.edit(commit = true) { putBoolean(LOADING, value) }

    /** Free space where the model goes, or null without storage. */
    fun freeBytes(): Long? = directory?.let { dir -> dir.mkdirs(); runCatching { freeSpace(dir) }.getOrNull() }

    /** What has to be free to start the download: the model and a margin, so it never fills the phone. */
    val requiredFreeBytes: Long get() = expectedSize + GemmaModel.SPACE_MARGIN_BYTES

    /**
     * Checks [unverifiedFile] against the pinned size and SHA-256 and moves it
     * into place. False (and the file deleted) when it doesn't match.
     */
    fun install(): Boolean {
        val file = unverifiedFile ?: return false
        val target = modelFile ?: return false
        if (sha256(file) != expectedSha256) {
            file.delete()
            return false
        }
        if (file != target && !file.renameTo(target)) return false
        preferences.edit { putLong(VERIFIED_MODIFIED, target.lastModified()) }
        return true
    }

    /** Deletes the model, any partial download and the runtime's cache, and forgets the choice to download it. */
    fun delete() {
        listOfNotNull(modelFile, downloadFile).forEach { it.delete() }
        runtimeCache.deleteRecursively()
        preferences.edit { clear() }
    }

    companion object {
        const val MODELS_FOLDER = "models"
        private const val WANTED = "wanted"
        private const val MOBILE_DATA = "mobile_data"
        private const val DOWNLOAD_ID = "download_id"
        private const val LAST_FAILURE = "last_failure"
        private const val LOADING = "loading"
        private const val VERIFIED_MODIFIED = "verified_modified"

        /** Lowercase hex SHA-256 of [file], read in 1 MB blocks. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
