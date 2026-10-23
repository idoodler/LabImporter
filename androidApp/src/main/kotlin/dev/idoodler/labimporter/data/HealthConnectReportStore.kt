package dev.idoodler.labimporter.data

import android.content.Context
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.FhirResource
import androidx.health.connect.client.records.FhirVersion
import androidx.health.connect.client.records.MedicalDataSource
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.MedicalResourceId
import androidx.health.connect.client.request.CreateMedicalDataSourceRequest
import androidx.health.connect.client.request.GetMedicalDataSourcesRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.request.UpsertMedicalResourceRequest
import dev.idoodler.labimporter.core.LabReportStore
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.model.LabReport

/** What Health Connect can do on this device. */
enum class HealthConnectAvailability {
    /** Health Connect isn't installed / supported. */
    NOT_INSTALLED,

    /** The Health Connect provider must be updated first. */
    UPDATE_REQUIRED,

    /** Health Connect exists but has no Medical Records (needs Android 16+ / a newer module). */
    MEDICAL_RECORDS_UNSUPPORTED,
    AVAILABLE,
}

/** Health Connect as [ReportRepository] uses it — a seam, so moving reports into it is testable. */
interface HealthConnectStore : LabReportStore {
    fun availability(): HealthConnectAvailability

    suspend fun hasPermissions(): Boolean
}

/**
 * Saves reports to Health Connect's *Medical Records* store as FHIR R4
 * laboratory Observations (see `FhirLabCodec`) — the Android counterpart of
 * the iOS app's CDA documents in Apple Health. Reads are restricted to this
 * app's own data source, mirroring the iOS source filter.
 *
 * The Medical Records API is still experimental in Jetpack; everything here is
 * gated on [availability] so the app degrades to [LocalReportStore].
 */
@OptIn(ExperimentalPersonalHealthRecordApi::class)
class HealthConnectReportStore(
    private val context: Context,
    private val softwareIdentity: String,
) : HealthConnectStore {

    val permissions: Set<String> = setOf(
        HealthPermission.PERMISSION_WRITE_MEDICAL_DATA,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_LABORATORY_RESULTS,
    )

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    override fun availability(): HealthConnectAvailability {
        val sdk = HealthConnectClient.getSdkStatus(context)
        if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) return HealthConnectAvailability.UPDATE_REQUIRED
        if (sdk != HealthConnectClient.SDK_AVAILABLE) return HealthConnectAvailability.NOT_INSTALLED
        val medical = runCatching {
            client?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD)
        }.getOrNull()
        return if (medical == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            HealthConnectAvailability.AVAILABLE
        } else {
            HealthConnectAvailability.MEDICAL_RECORDS_UNSUPPORTED
        }
    }

    override suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return runCatching { c.permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)
    }

    override suspend fun loadReports(): List<LabReport> {
        val c = requireClient()
        val source = existingSource(c) ?: return emptyList()
        val resources = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val request = pageToken?.let { ReadMedicalResourcesPageRequest(it, pageSize = PAGE_SIZE) }
                ?: ReadMedicalResourcesInitialRequest(
                    MedicalResource.MEDICAL_RESOURCE_TYPE_LABORATORY_RESULTS,
                    medicalDataSourceIds = setOf(source.id),
                    pageSize = PAGE_SIZE,
                )
            val response = c.readMedicalResources(request)
            resources += response.medicalResources.map { it.fhirResource.data }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        // Health Connect resumes on the caller's thread (often main); parsing every report isn't for it.
        return withContext(Dispatchers.Default) { FhirLabCodec.decode(resources) }
    }

    override suspend fun save(report: LabReport) {
        val c = requireClient()
        val source = existingSource(c) ?: createSource(c)
        val requests = withContext(Dispatchers.Default) {
            FhirLabCodec.encode(report, softwareIdentity).map { UpsertMedicalResourceRequest(source.id, source.fhirVersion, it.json) }
        }
        if (requests.isNotEmpty()) c.upsertMedicalResources(requests)
    }

    override suspend fun delete(report: LabReport) {
        val c = requireClient()
        val source = existingSource(c) ?: return
        val ids = report.entries.map {
            MedicalResourceId(source.id, FhirResource.FHIR_RESOURCE_TYPE_OBSERVATION, it.id)
        }
        if (ids.isNotEmpty()) c.deleteMedicalResources(ids)
    }

    private fun requireClient(): HealthConnectClient =
        client ?: throw IllegalStateException("Health Connect is not available on this device")

    private suspend fun existingSource(c: HealthConnectClient): MedicalDataSource? =
        c.getMedicalDataSources(GetMedicalDataSourcesRequest(listOf(context.packageName))).firstOrNull()

    private suspend fun createSource(c: HealthConnectClient): MedicalDataSource =
        c.createMedicalDataSource(
            CreateMedicalDataSourceRequest(
                fhirBaseUri = "https://github.com/idoodler/LabImporter/fhir".toUri(),
                displayName = "LabImporter",
                fhirVersion = FhirVersion(4, 0, 1),
            ),
        )

    private companion object {
        const val PAGE_SIZE = 500
    }
}
