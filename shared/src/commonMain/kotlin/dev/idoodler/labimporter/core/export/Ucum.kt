package dev.idoodler.labimporter.core.export

/** Maps common (largely German) printed lab units to UCUM codes. */
object Ucum {
    fun fromPrinted(raw: String): String = when (raw.trim().lowercase()) {
        "mg/dl" -> "mg/dL"
        "mmol/l" -> "mmol/L"
        "u/", "u/l" -> "U/L"
        "mu/l", "miu/l" -> "m[IU]/L"
        "iu/l" -> "[IU]/L"
        "mmol/mol" -> "mmol/mol"
        "%" -> "%"
        "ml/min/1,73m2kof", "ml/min/1.73m2kof", "ml/min/1.73 m2" -> "mL/min/{1.73_m2}"
        "nmol/l" -> "nmol/L"
        "pmol/l" -> "pmol/L"
        "g/dl" -> "g/dL"
        "g/l" -> "g/L"
        "pg/ml" -> "pg/mL"
        "ng/ml" -> "ng/mL"
        "ng/dl" -> "ng/dL"
        "µg/dl", "ug/dl", "μg/dl" -> "ug/dL"
        "µg/l", "ug/l", "μg/l" -> "ug/L"
        "fl" -> "fL"
        "pg" -> "pg"
        "1/µl", "1/ul", "/µl", "/ul" -> "/uL"
        "1/nl", "/nl" -> "/nL"
        else -> raw
    }
}
