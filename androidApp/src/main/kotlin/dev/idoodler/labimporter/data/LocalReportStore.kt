package dev.idoodler.labimporter.data

import android.content.Context
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.model.LabReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-device fallback for devices without Health Connect Medical Records. Each
 * report is one file in app-private, no-backup storage holding exactly the
 * FHIR Observations that would be written to Health Connect, so switching
 * stores later is a straight copy.
 */
class LocalReportStore(context: Context, private val softwareIdentity: String) : LabReportStore {
    private val directory = File(context.noBackupFilesDir, "reports").apply { mkdirs() }
    private val serializer = ListSerializer(String.serializer())

    override suspend fun loadReports(): List<LabReport> = withContext(Dispatchers.IO) {
        val resources = directory.listFiles { f -> f.extension == "json" }.orEmpty().flatMap { file ->
            runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        }
        FhirLabCodec.decode(resources)
    }

    override suspend fun save(report: LabReport) = withContext(Dispatchers.IO) {
        val resources = FhirLabCodec.encode(report, softwareIdentity).map { it.json }
        val tmp = File(directory, "${report.id}.json.tmp")
        tmp.writeText(Json.encodeToString(serializer, resources))
        tmp.renameTo(file(report))
        Unit
    }

    override suspend fun delete(report: LabReport) = withContext(Dispatchers.IO) {
        file(report).delete()
        Unit
    }

    private fun file(report: LabReport) = File(directory, "${report.id.filter { it.isLetterOrDigit() || it == '-' }}.json")
}
