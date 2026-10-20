package dev.idoodler.labimporter.core

import dev.idoodler.labimporter.core.export.CdaExport
import dev.idoodler.labimporter.core.model.LabValue
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact CDA document LabImporter writes. The same fixture and
 * expected text live in the iOS unit tests (LabImporterTests/CDAExportTests),
 * which is how the shared generator was proven identical to the Swift one it
 * replaced — Apple Health already holds documents in this exact format.
 */
class GoldenCdaTest {

    @Test
    fun documentMatchesGolden() {
        startCatalog("en-US")
        val actual = normalize(CdaExport.generate(GoldenCda.values, LocalDate(2026, 5, 6), "IDENTITY", "Max Mustermann", "Labor & Co"))
        val golden = File(System.getProperty("golden.dir"), "report.cda.xml")
        if (System.getProperty("golden.record") == "true") golden.writeText(actual)
        assertEquals(golden.readText(), actual)
    }

    companion object {
        /** Random document/observation ids → a fixed token. */
        fun normalize(xml: String): String = xml.replace(Regex("root=\"[0-9a-f-]{36}\""), "root=\"UUID\"")
    }
}

object GoldenCda {
    val values = listOf(
        LabValue(code = "2160-0", name = "Creatinine", displayValue = "0,8", numericValue = 0.8, unit = "mg/dl"),
        LabValue(code = "2823-3", name = "Potassium", displayValue = "4,1", numericValue = 4.1, unit = "mmol/l"),
        LabValue(code = "4548-4", name = "HbA1c", displayValue = "6,53", numericValue = 6.53, unit = "%"),
        LabValue(code = "1742-6", name = "ALT", displayValue = "12345,6", numericValue = 12345.6, unit = "U/l"),
        LabValue(code = "KREA", name = "unmapped", displayValue = "1", numericValue = 1.0, unit = ""),
        LabValue(code = "2093-3", name = "deselected", displayValue = "198", numericValue = 198.0, unit = "mg/dl", isSelected = false),
        LabValue(code = "2951-2", name = "no value", displayValue = "-", numericValue = null, unit = "mmol/l"),
    )
}
