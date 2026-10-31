package dev.idoodler.labimporter.ui.preview

import dev.idoodler.labimporter.core.model.LabReport
import dev.idoodler.labimporter.core.model.LabValue
import kotlinx.datetime.LocalDate

/** Fixtures for Compose previews (mirrors iOS `PreviewSampleData`). */
object PreviewSamples {
    private fun entry(id: String, code: String, name: String, value: Double, unit: String) =
        LabReport.Entry(id, code, name, value.toString(), value, unit)

    val reports: List<LabReport> = listOf(
        LabReport(
            "r3", LocalDate(2026, 9, 2), "Max Mustermann", "Labor Dr. Muster",
            listOf(
                entry("e31", "4548-4", "HbA1c", 6.1, "%"),
                entry("e32", "2160-0", "Kreatinin", 0.82, "mg/dl"),
                entry("e33", "2093-3", "Cholesterin", 212.0, "mg/dl"),
                entry("e34", "3016-3", "TSH", 1.9, "mU/l"),
            ),
        ),
        LabReport(
            "r2", LocalDate(2026, 5, 6), "Max Mustermann", "Labor Dr. Muster",
            listOf(
                entry("e21", "4548-4", "HbA1c", 6.5, "%"),
                entry("e22", "2160-0", "Kreatinin", 0.8, "mg/dl"),
                entry("e23", "2093-3", "Cholesterin", 198.0, "mg/dl"),
            ),
        ),
        LabReport(
            "r1", LocalDate(2026, 1, 14), "Max Mustermann", "",
            listOf(
                entry("e11", "4548-4", "HbA1c", 6.9, "%"),
                entry("e12", "2160-0", "Kreatinin", 0.78, "mg/dl"),
            ),
        ),
    )

    val reviewValues: List<LabValue> = listOf(
        LabValue("v1", "4548-4", "HbA1c", "6,5", 6.5, "%"),
        LabValue("v2", "2160-0", "Kreatinin", "0,80", 0.8, "mg/dl", isSuggestedCode = true),
        LabValue("v3", "GADAAK", "Glutamatdecarboxylase-Antikörper", "-", null, "U/ml"),
        LabValue("v4", "2093-3", "Cholesterin", "198", 198.0, "mg/dl", isSelected = false),
    )

    /** Results that aren't numbers, saved as text: a finding, a titer, a threshold, a strip grade. */
    val qualitativeValues: List<LabValue> = listOf(
        LabValue("q1", "5196-1", "HBsAg", "nicht reaktiv", null, ""),
        LabValue("q2", "5048-4", "ANA-Titer", "1:160", null, ""),
        LabValue("q3", "1988-5", "CRP", "<0,5", null, "mg/l"),
        LabValue("q4", "20454-5", "Eiweiß (Urin)", "2+", null, ""),
    )

    /** The latest report with qualitative results added. */
    val reportWithQualitative: LabReport = reports.first().let { report ->
        report.copy(entries = report.entries + qualitativeValues.map { LabReport.Entry(it.id, it.code, it.name, it.displayValue, null, it.unit) })
    }

    /** A terse PatMed-style report for trying the import flow on an emulator. */
    const val SAMPLE_REPORT_TEXT = """Labor Dr. Muster · Befund vom 06.05.2026
Patient: Max Mustermann
KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl; GPT: 59 U/l; GGT: 31 U/l
HB-A1C: 6,5 %; Glucose nüchtern: 104 mg/dl
Cholesterin: 198 mg/dl; HDL-Cholesterin: 52 mg/dl; LDL-Cholesterin: 121 mg/dl; Triglyceride: 133 mg/dl
Kalium: 4,1 mmol/l; Natrium: 139 mmol/l
TSH: 1,9 mU/l; Ferritin: 88 ng/ml"""
}
