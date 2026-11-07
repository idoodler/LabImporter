package dev.idoodler.labimporter.data

import android.content.Context
import android.net.Uri
import android.os.ext.SdkExtensions
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.FhirVersion
import androidx.health.connect.client.records.MedicalDataSource
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.MedicalResourceId
import androidx.health.connect.client.request.UpsertMedicalResourceRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.export.FhirLabCodec
import dev.idoodler.labimporter.core.export.ReportEncodingException
import dev.idoodler.labimporter.core.model.LabReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/** The real store over the only part of the Health Connect client it uses. */
@OptIn(ExperimentalPersonalHealthRecordApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class HealthConnectReportStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val upserted = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var source: MedicalDataSource? = null

    private fun newSource() = MedicalDataSource("00000000-0000-4000-8000-000000000000", context.packageName, Uri.parse("https://example.com/fhir"), "LabImporter", FhirVersion(4, 0, 1), null)

    // HealthConnectClient has far more to it than this store calls, so anything else fails the test.
    private val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java)) { _, method, arguments ->
        when (method.name) {
            "getMedicalDataSources" -> listOfNotNull(source)
            "createMedicalDataSource" -> newSource().also { source = it }
            "upsertMedicalResources" -> {
                arguments[0].let { it as List<*> }.mapTo(upserted) { (it as UpsertMedicalResourceRequest).data }
                emptyList<MedicalResource>()
            }
            "deleteMedicalResources" -> {
                arguments[0].let { it as List<*> }.mapTo(deleted) { (it as MedicalResourceId).fhirResourceId }
                Unit
            }
            else -> error("Unexpected Health Connect call: ${method.name}")
        }
    } as HealthConnectClient

    private val store = HealthConnectReportStore(ApplicationProvider.getApplicationContext(), "test", newClient = { client })

    private fun entry(id: String, code: String, value: Double) = LabReport.Entry(id, code, code, value.toString(), value, "mg/dl")

    private fun report(vararg entries: LabReport.Entry) = LabReport("a", LocalDate(2026, 9, 1), "", "", entries.toList())

    private fun setMedicalRecordsExtension(version: Int) = ReflectionHelpers.setStaticField(SdkExtensions::class.java, "U_EXTENSION_INT", version)

    /** The library's request types refuse to exist on a platform without Medical Records. */
    @Before
    fun offerMedicalRecords() = setMedicalRecordsExtension(16)

    @After
    fun withdrawMedicalRecords() = setMedicalRecordsExtension(0)

    @Test
    fun aSaveWritesEveryValue() = runTest {
        val saved = report(entry("e1", "2160-0", 0.8), entry("e2", "2823-3", 4.1))

        store.save(saved)

        assertEquals(saved.entries.toSet(), FhirLabCodec.decode(upserted).single().entries.toSet())
    }

    @Test
    fun aResultTheCatalogDoesntKnowFailsTheWholeSaveInsteadOfDroppingIt() = runTest {
        val unknown = report(entry("e1", "2160-0", 0.8), entry("e2", "NOPE", 1.0))

        assertThrows(ReportEncodingException::class.java) { runBlocking { store.save(unknown) } }

        assertTrue(upserted.isEmpty())
    }

    @Test
    fun aDeleteRemovesTheReportsRecords() = runTest {
        source = newSource()

        store.delete(report(entry("e1", "2160-0", 0.8), entry("e2", "2823-3", 4.1)))

        assertEquals(listOf("e1", "e2"), deleted)
    }

    @Test
    fun anEditRemovesOnlyTheRecordsItDropped() = runTest {
        source = newSource()
        val old = report(entry("dropped", "2160-0", 0.8), entry("kept", "2823-3", 4.1))

        store.removeDropped(old, report(entry("kept", "2823-3", 5.0)))

        assertEquals(listOf("dropped"), deleted)
    }
}
