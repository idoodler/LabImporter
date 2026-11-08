package dev.idoodler.labimporter.core.loinc

/**
 * A term's plain clinical name, derived from its catalog label by rule: what
 * the main screens call a value, where "HbA1c (Bld) [Mass fraction]" would
 * read like syntax. The catalog label itself stays what tells terms apart
 * (code picker, LOINC browser, term screen) and what exports carry.
 *
 * Each catalog language prints a label in its own template, but its parts are
 * always the same: component, property, specimen, scale, method. The rule keeps
 * the component and the method (LDL Calc and LDL direct are different tests) and
 * drops a property only if the unit or the result beside the value says it anyway
 * ([PROPERTIES_THE_RESULT_STATES]); any other names the test. The specimen
 * goes only for the usual blood, serum and plasma, decided by the term's
 * language-neutral LOINC system: urine or stool protein is a different test, so
 * nothing below may remove a specimen unless [USUAL_SYSTEMS] says so.
 *
 * The tables here hold how a template spells a specimen or a scale, never a test.
 */
internal object LoincPlainName {

    /** @param language the catalog language [label] is written in. */
    fun of(label: String, language: String, system: String, property: String): String {
        val usual = system in USUAL_SYSTEMS
        val keepsProperty = property !in PROPERTIES_THE_RESULT_STATES && !(property == "EntMeanVol" && system == "RBC")
        val name = when (language) {
            in IN_TEMPLATE_LANGUAGES -> fromInTemplate(label, usual, keepsProperty, propertyIsFirst = true)
            "pt-BR" -> fromPortuguese(label, usual, keepsProperty, property)
            "fr" -> fromFrench(label, usual, keepsProperty)
            else -> fromBracketed(label, language, usual, keepsProperty)
        }
        return tidy(name).ifEmpty { tidy(label) }
    }

    /** "ALT [Catalytic activity/Vol]", "Protein (U) [Mass/Vol]" (en, uk), "Protein [Masse/Volumen] in Urin mittels …" (de, nl). */
    private fun fromBracketed(label: String, language: String, usual: Boolean, keepsProperty: Boolean): String {
        val open = label.indexOf('[')
        val close = if (open >= 0) label.indexOf(']', open) else -1
        var head = (if (close >= 0) label.substring(0, open) else label).replace("]", "").trim()
        val property = if (close >= 0) label.substring(open + 1, close) else ""
        val tail = if (close >= 0) label.substring(close + 1).trim() else ""
        val specimens = PARENTHESIZED_SPECIMENS[language]
        val group = trailingGroupStart(head)
        if (usual && specimens != null && group >= 0 && head.substring(group + 1, head.length - 1) in specimens) {
            head = head.substring(0, group).trimEnd()
        }
        // A susceptibility or an unspecified specimen prints no specimen at all, and then the
        // property is all that tells "Vancomycin" the drug level from the antibiotic test.
        val printsSpecimen = trailingGroupStart(head) >= 0 || METHOD_CONNECTOR.split(" $tail ", 2).first().isNotBlank()
        var name = "$head $tail".trim()
        if (usual) WORDED_SPECIMENS[language]?.let { name = withoutWordedSpecimen(name, it) }
        if (language == "en" && !usual) name = withSpecimenSpelledOut(name)
        val names = property.isNotEmpty() && (keepsProperty || (!usual && !printsSpecimen))
        return if (names && !name.contains(property, ignoreCase = true)) "$name ($property)" else name
    }

    /** "Proteínas in Orina (Concentración de masa, Tira reactiva)" (es, it, pl, ru, tr, zh-Hans); pt-BR's long labels have no property. */
    private fun fromInTemplate(label: String, usual: Boolean, keepsProperty: Boolean, propertyIsFirst: Boolean): String {
        val group = trailingGroupStart(label)
        val head = if (group >= 0) label.substring(0, group).trimEnd() else label
        val inner = if (group >= 0) splitAtTopLevelCommas(label.substring(group + 1, label.length - 1)) else emptyList()
        val details = when {
            !propertyIsFirst -> inner.filter { it != PORTUGUESE_THRESHOLD }
            keepsProperty -> inner
            else -> inner.drop(1)
        }
        // The " in " is the template's, in English even in Russian; parentheses read right everywhere.
        val at = head.lastIndexOf(" in ")
        val component = if (at >= 0) head.substring(0, at) else head
        val specimen = if (at >= 0 && !usual) listOf(head.substring(at + 4)) else emptyList()
        val parts = specimen + details
        return if (parts.isEmpty()) component else "$component (${parts.joinToString(", ")})"
    }

    /** "Protéines [Masse/Temps] 24H ; Urine ; Numérique ; Bandelette réactive": timing, specimen, scale, method. */
    private fun fromFrench(label: String, usual: Boolean, keepsProperty: Boolean): String {
        val open = label.indexOf('[')
        val close = if (open >= 0) label.indexOf(']', open) else -1
        if (close < 0) return label
        val segments = label.substring(close + 1).split(" ; ").map { it.trim() }
        val scale = segments.indexOfFirst { it in FRENCH_SCALES }.let { if (it < 0) segments.size else it }
        val beforeScale = segments.take(scale).let { if (usual) it.dropLast(1) else it }
        val method = segments.drop(scale + 1)
        val name = (listOf(label.substring(0, open).trim()) + beforeScale).filter { it.isNotEmpty() }.joinToString(" ")
        val property = label.substring(open + 1, close)
        val details = (if (keepsProperty && !name.contains(property, ignoreCase = true)) listOf(property) else emptyList()) + method
        return if (details.isEmpty()) name else "$name (${details.joinToString(", ")})"
    }

    /** LOINC's short names ("Creat SerPl-mCnc", "WBC # Sg Auto"), or the same template as Spanish ("Rivaroxaban in Soro/Plas"). */
    private fun fromPortuguese(label: String, usual: Boolean, keepsProperty: Boolean, property: String): String {
        if (" in " in label) return fromInTemplate(label, usual, keepsProperty, propertyIsFirst = false)
        val suffix = PORTUGUESE_PROPERTY_SUFFIX.find(label)
        val tokens = (suffix?.let { label.removeRange(it.range) } ?: label).split(' ')
            .filterNot { it in PORTUGUESE_PROPERTY_TOKENS }.toMutableList()
        if (usual) {
            // Only the last one is the specimen: the short name ends with it, before scale and method.
            val specimen = tokens.indexOfLast { it in PORTUGUESE_SPECIMEN_TOKENS }
            if (specimen >= 0) tokens.removeAt(specimen)
        }
        val name = tokens.joinToString(" ")
        // The one property these labels can leave unprinted that the count's name would then hide ("RBC Sg Auto", "RBC # Sg Auto").
        if (property == "Morph" && !name.contains(property, ignoreCase = true)) return "$name ($property)"
        return if (keepsProperty && suffix != null) "$name (${suffix.value.drop(1)})" else name
    }

    /** Drops the last " in Serum oder Plasma" (or "von Blut", "- Blut"), but never a bare word: "Plasma cells" is the test. */
    private fun withoutWordedSpecimen(name: String, specimens: Regex): String =
        specimens.findAll(name).lastOrNull()?.let { name.removeRange(it.range) } ?: name

    /** "Protein (U)" → "Protein (urine)". English only: the other catalogs already spell theirs out. */
    private fun withSpecimenSpelledOut(name: String): String {
        val group = trailingGroupStart(name)
        if (group < 0) return name
        val spelled = SPECIMEN_WORD.replace(name.substring(group + 1, name.length - 1)) { ENGLISH_SPECIMEN_WORDS[it.value] ?: it.value }
        return "${name.substring(0, group)}($spelled)"
    }

    /** Index of the "(" matching a final ")", or -1. */
    private fun trailingGroupStart(text: String): Int {
        if (!text.endsWith(')')) return -1
        var depth = 0
        for (index in text.indices.reversed()) {
            when (text[index]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun splitAtTopLevelCommas(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0 && text.getOrNull(index + 1) == ' ') {
                    parts += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        parts += text.substring(start).trim()
        return parts
    }

    /** The syntax that's left in any template: `^` and `--` separators, stray brackets, dot notation, leftover punctuation. */
    private fun tidy(name: String): String {
        val plain = withoutDotNotation(name.replace('^', ' ').replace(" --", ", ").replace("[", "").replace("]", ""))
        var text = WHITESPACE.replace(plain, " ").trim().replace(" ,", ",").replace(" ;", ";").replace("( ", "(").replace(" )", ")")
        text = text.trimStart(',', ';', ':', '/', '.', '-', ' ').trimEnd(',', ';', ':', '/', '-', ' ')
        // A full stop ends a catalog sentence here and there; a short last word is an abbreviation ("Brucella sp.").
        if (text.endsWith('.') && text.substringAfterLast(' ').length > MAX_ABBREVIATION) text = text.dropLast(1)
        if (text.endsWith(')') && text.count { it == ')' } > text.count { it == '(' }) text = text.dropLast(1).trimEnd()
        return text
    }

    /**
     * "Bilirubin.gesamt" → "Bilirubin gesamt". Only after a word of two letters or more,
     * and before a letter or digit: "1.73", "d.m.v." and "S.aureus" mean what they say.
     */
    private fun withoutDotNotation(text: String): String = buildString(text.length) {
        text.forEachIndexed { index, char ->
            val joinsWords = char == '.' && index >= 2 && text[index - 1].isLetter() && text[index - 2].isLetter() &&
                text.getOrNull(index + 1)?.isLetterOrDigit() == true
            append(if (joinsWords) ' ' else char)
        }
    }

    private const val MAX_ABBREVIATION = 5
    private const val PORTUGUESE_THRESHOLD = "Limiar"

    private val WHITESPACE = Regex("\\s+")
    private val METHOD_CONNECTOR = Regex("\\s(?:mittels|d\\.m\\.v\\.|by)\\s")
    private val SPECIMEN_WORD = Regex("[^ /+]+")

    /** Blood, serum, plasma and the either-or of those; arterial, venous, cord or donor blood are different tests. */
    private val USUAL_SYSTEMS = setOf("Bld", "Ser", "Plas", "Ser/Plas", "Ser/Plas/Bld")

    /**
     * LOINC properties that only say how the one thing the name names is reported: mg/dL or
     * mmol/L, per volume, per day or per mass, a share or a ratio, seconds, a titer "1:160", a
     * "negativ", or the word that identifies it ("E. coli", "A Rh+"). The unit or the result beside
     * the value says that. Every other property names what's measured and stays, so a property
     * nobody listed can only make a name longer, never merge two tests: urine colour and
     * osmolality are both "Observación" in Spanish, a morphology finding has the name of the count,
     * pCO2 the name of total CO2. On red cells the mean volume is already named: MCV.
     */
    private val PROPERTIES_THE_RESULT_STATES = setOf(
        "-", "MCnc", "SCnc", "ACnc", "CCnc", "NCnc", "MSCnc", "LnCnc", "LaCnc", "LsCnc", "RelACnc", "RelMCnc", "RelCCnc", "RelSCnc",
        "MCncSq", "SCncSq", "MCnt", "SCnt", "CCnt", "ACnt", "Num", "Naric", "Mass", "Sub", "CAct", "CSub",
        "MRat", "SRat", "ARat", "CRat", "NRat", "VRat", "MSRat", "RelMRat", "RelSRat", "Vol",
        "MFr", "SFr", "NFr", "VFr", "CFr", "AFr", "MFr.DF", "SFr.DF", "NFr.DF", "VFr.DF", "CFr.DF",
        "MRto", "SRto", "NRto", "CRto", "LnRto", "Ratio", "RelRto", "EntSRto", "PrThr", "Titr", "Arb",
        "EntMass", "EntVol", "EntMCnc", "EntSCnc", "EntCat", "EntSub", "EntNum", "ArVRat", "Time", "RelTime", "TRto",
        "Prid", "Type", "ID", "Source", "Pn", "Addr", "Txt", "Bib", "Desc", "Disposition", "Anat", "TmStp", "Date",
    )

    private val IN_TEMPLATE_LANGUAGES = setOf("es", "it", "pl", "ru", "tr", "zh-Hans")

    private val PARENTHESIZED_SPECIMENS = mapOf(
        "en" to setOf("S", "P", "Bld", "S/P", "S/P/Bld"),
        "uk" to setOf(
            "Свтк", "Сир", "Сиров", "Cиров", "Сироватка", "Плаз", "Крв", "Кров", "Крові",
            "Свтк/Плаз", "СвткПлаз", "Свтк/Плаз/Кров", "Свтк/Плаз/Крові", "Сиров/Плаз/Крв",
        ),
    )

    private fun wordedSpecimens(connectors: String, vararg specimens: String) =
        Regex("\\s+(?:$connectors)\\s+(?:${specimens.joinToString("|")})(?=$|[\\s,;)])")

    private val WORDED_SPECIMENS = mapOf(
        "en" to wordedSpecimens("in", "Serum, Plasma or Blood", "Serum or Plasma", "Serum", "Plasma", "Blood"),
        "de" to wordedSpecimens("in|von|für|-", "Serum, Plasma oder Blut", "Serum oder Plasma", "Serum", "Plasma", "Blut"),
        "nl" to wordedSpecimens("in", "serum of plasma of bloed", "serum of plasma", "serum", "plasma", "bloed"),
    )

    private val FRENCH_SCALES = setOf(
        "Numérique", "Qualitatif", "Semi-Quantitatif", "Résultat nominal", "Alphanumérique", "Résultat textuel", "Document", "-",
    )

    private val PORTUGUESE_PROPERTY_SUFFIX = Regex("-(?:(?:m|s|a|c|n|ms|v|Ln)?(?:Cnc|Cnt|Rto|Rate)|Imp|Titr)$")
    private val PORTUGUESE_PROPERTY_TOKENS = setOf("#", "MFr", "NFr", "SFr", "VFr", "Titr")
    private val PORTUGUESE_SPECIMEN_TOKENS = setOf("SerPl", "Ser", "Sg", "SgTotal", "Plas", "Soro", "Soro/Plas", "Soro/Plas/Sg")

    /** The abbreviations LOINC's English display names use for specimens. */
    private val ENGLISH_SPECIMEN_WORDS = mapOf(
        "U" to "urine", "Stl" to "stool", "Sal" to "saliva", "Sem" to "semen", "Sput" to "sputum", "Tiss" to "tissue",
        "S" to "serum", "P" to "plasma", "Bld" to "blood", "BldA" to "arterial blood", "BldV" to "venous blood",
        "BldC" to "capillary blood", "BldCo" to "cord blood", "BldCoA" to "arterial cord blood", "BldCoV" to "venous cord blood",
        "BldMV" to "mixed venous blood", "DBS" to "dried blood spot", "Spec" to "specimen",
        "Amn" to "amniotic", "Syn" to "synovial", "Pleur" to "pleural", "Periton" to "peritoneal", "fld" to "fluid",
    )
}
