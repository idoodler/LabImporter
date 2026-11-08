package dev.idoodler.labimporter.data

import android.content.Context
import android.util.Log
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.StoredReports
import dev.idoodler.labimporter.core.model.LabReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-device fallback for devices without Health Connect Medical Records. Each
 * report is one file in app-private, no-backup storage holding exactly the
 * FHIR Observations that would be written to Health Connect, so switching
 * stores later is a straight copy.
 *
 * @param moveFile puts a finished temporary file in place; tests make it fail.
 */
class LocalReportStore(
    context: Context,
    private val softwareIdentity: String,
    private val moveFile: (File, File) -> Unit = ::replaceAtomically,
) : LabReportStore {
    private val directory = File(context.noBackupFilesDir, "reports").apply { mkdirs() }
    private val serializer = ListSerializer(String.serializer())

    @Volatile
    override var unreadableReports = 0
        private set

    @Volatile
    override var newerVersionReports = 0
        private set

    override suspend fun loadReports(): List<LabReport> = withContext(Dispatchers.IO) {
        val files = directory.listFiles { f -> f.extension == "json" }.orEmpty().map(::read)
        unreadableReports = files.sumOf { it.unreadableReports }
        newerVersionReports = files.sumOf { it.newerVersionReports }
        files.flatMap { it.reports }
    }

    /** What [file] holds. One that can't be read isn't listed, but stays: a later version may read it. */
    private fun read(file: File): StoredReports {
        val resources = try {
            Json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // Only the type: a parse error's message quotes the file, and that's a health record.
            Log.w(TAG, "Couldn't read a saved report: ${e::class.simpleName}")
            return StoredReports(emptyList(), unreadableReports = 1)
        }
        val stored = FhirLabCodec.decodeStored(resources)
        if (stored.newerVersionReports > 0) return StoredReports(emptyList(), newerVersionReports = stored.newerVersionReports)
        if (stored.reports.sumOf { it.entries.size } < resources.size) {
            // Showing what's left would let a move into Health Connect delete the rest.
            Log.w(TAG, "Couldn't read every value of a saved report")
            return StoredReports(emptyList(), unreadableReports = 1)
        }
        return stored
    }

    override suspend fun save(report: LabReport) = withContext(Dispatchers.IO) {
        val resources = FhirLabCodec.encodeForSaving(report, softwareIdentity).map { it.json }
        val target = file(report)
        val tmp = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(Json.encodeToString(serializer, resources).encodeToByteArray())
                out.fd.sync() // On disk before it replaces the old version, or a power cut could leave neither.
            }
            moveFile(tmp, target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    override suspend fun delete(report: LabReport) = withContext(Dispatchers.IO) {
        // Throws when the file stays, which File.delete() only hints at; a file already gone is deleted.
        Files.deleteIfExists(file(report).toPath())
        Unit
    }

    /** A save already replaced the whole file; only a changed id leaves another one behind. */
    override suspend fun removeDropped(old: LabReport, updated: LabReport) {
        if (file(old) != file(updated)) delete(old)
    }

    /**
     * Ids can come from a transfer file, so whatever isn't safe in a file name goes.
     * The hash keeps two such ids from landing on (and overwriting) one file.
     */
    private fun file(report: LabReport): File {
        val safe = report.id.filter { it.isLetterOrDigit() || it == '-' }
        val name = if (safe == report.id) safe else "$safe-${report.id.hashCode().toUInt().toString(16)}"
        return File(directory, "$name.json")
    }

    private companion object {
        const val TAG = "LocalReportStore"
    }
}

private fun replaceAtomically(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
