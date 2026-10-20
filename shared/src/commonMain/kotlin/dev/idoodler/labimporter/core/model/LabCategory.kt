package dev.idoodler.labimporter.core.model

import dev.idoodler.labimporter.core.LabCore
import dev.idoodler.labimporter.core.platform.PlatformLock
import dev.idoodler.labimporter.core.platform.withLock

/**
 * A coarse clinical grouping derived from a LOINC term's class + component,
 * giving each metric a consistent colour. [argb] values match the iOS palette
 * (SwiftUI's system colours plus two custom tones).
 */
enum class LabCategory(val argb: Long) {
    GLYCEMIC(0xFFFF9500),
    LIPIDS(0xFFFFCC00),
    CARDIAC(0xFFFF3B30),
    RENAL(0xFFA2845E),
    HEPATIC(0xFF34C759),
    ENDOCRINE(0xFFAF52DE),
    ELECTROLYTES(0xFF30B0C7),
    BLOOD_GAS(0xFF007AFF),
    HEMATOLOGY(0xFFFF2D55),
    COAGULATION(0xFF5856D6),
    NUTRITION(0xFF00C7BE),
    MICROBIOLOGY(0xFF32ADE6),
    URINALYSIS(0xFFC7A833),
    DRUG(0xFFD94D8C),
    OTHER(0xFF8E8E93),
    ;

    companion object {
        private val lock = PlatformLock()
        private val cache = mutableMapOf<String, LabCategory>()

        /** [categories] by how often they occur, most first; ties in canonical order. */
        fun ranked(categories: List<LabCategory>): List<LabCategory> {
            val counts = categories.groupingBy { it }.eachCount()
            return entries.filter { it in counts }.sortedByDescending { counts.getValue(it) }
        }

        /** Best-effort category for a LOINC code (memoized; catalog lookups aren't free). */
        fun forCode(code: String): LabCategory {
            lock.withLock { cache[code] }?.let { return it }
            // Without the catalog (not started yet) there's nothing to classify by:
            // answer OTHER but don't cache it, or the code would stay grey forever.
            val directory = LabCore.loinc ?: return OTHER
            val resolved = directory.classification(code)
                ?.let { (cls, component) -> category(cls, component) }
                ?: OTHER
            lock.withLock { cache[code] = resolved }
            return resolved
        }

        fun category(loincClass: String, component: String): LabCategory {
            val cls = loincClass.uppercase()
            if (cls.startsWith("HEM/BC") || cls == "CELLMARK") return HEMATOLOGY
            if (cls.startsWith("COAG")) return COAGULATION
            if (cls.startsWith("UA")) return URINALYSIS
            if (cls.startsWith("DRUG")) return DRUG
            if (cls.startsWith("MICRO") || cls.startsWith("ABXBACT") || cls.startsWith("SERO")) return MICROBIOLOGY

            val comp = component.lowercase()
            fun has(vararg needles: String) = needles.any { comp.contains(it) }

            return when {
                comp == "ph" || has("oxygen", "carbon dioxide", "po2", "pco2", "base excess") -> BLOOD_GAS
                has("cholesterol", "triglyceride", "lipoprotein", "hdl", "ldl") -> LIPIDS
                has("glucose", "hemoglobin a1c", "hba1c", "fructosamine") -> GLYCEMIC
                has("troponin", "natriuretic peptide", "creatine kinase", "ck-mb") -> CARDIAC
                has("creatinine", "urea", "cystatin", "glomerular filtration") -> RENAL
                has("aminotransferase", "gamma glutamyl", "bilirubin", "alkaline phosphatase", "albumin") -> HEPATIC
                has(
                    "thyrotropin", "thyroxine", "triiodothyronine", "cortisol", "testosterone",
                    "estradiol", "insulin", "prolactin", "parathyrin",
                ) -> ENDOCRINE
                has("sodium", "potassium", "chloride", "calcium", "magnesium", "phosphate") -> ELECTROLYTES
                has(
                    "vitamin", "folate", "cobalamin", "ferritin", "iron", "25-hydroxy",
                    "calcidiol", "calcitriol", "transferrin",
                ) -> NUTRITION
                else -> OTHER
            }
        }
    }
}
