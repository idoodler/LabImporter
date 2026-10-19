package dev.idoodler.labimporter.ui.components

import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.R
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Locale-aware number rendering for the UI (shared code always uses `.`). */
fun formatNumber(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 4; minimumFractionDigits = 0 }.format(value)

fun formatDate(date: LocalDate, style: FormatStyle = FormatStyle.MEDIUM): String =
    date.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(style))

/** Parses user-typed numbers in either decimal convention. */
fun parseNumberInput(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

val LabCategory.labelRes: Int
    get() = when (this) {
        LabCategory.GLYCEMIC -> R.string.category_glycemic
        LabCategory.LIPIDS -> R.string.category_lipids
        LabCategory.CARDIAC -> R.string.category_cardiac
        LabCategory.RENAL -> R.string.category_renal
        LabCategory.HEPATIC -> R.string.category_hepatic
        LabCategory.ENDOCRINE -> R.string.category_endocrine
        LabCategory.ELECTROLYTES -> R.string.category_electrolytes
        LabCategory.BLOOD_GAS -> R.string.category_blood_gas
        LabCategory.HEMATOLOGY -> R.string.category_hematology
        LabCategory.COAGULATION -> R.string.category_coagulation
        LabCategory.NUTRITION -> R.string.category_nutrition
        LabCategory.MICROBIOLOGY -> R.string.category_microbiology
        LabCategory.URINALYSIS -> R.string.category_urinalysis
        LabCategory.DRUG -> R.string.category_drug
        LabCategory.OTHER -> R.string.category_other
    }
