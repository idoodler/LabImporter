package dev.idoodler.labimporter.core.loinc

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.idoodler.labimporter.core.platform.PlatformLock
import dev.idoodler.labimporter.core.platform.openReadOnlyDatabase
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import dev.idoodler.labimporter.core.platform.withLock

/** A common laboratory LOINC term resolved for the active app language. */
data class LoincTerm(
    val code: String,
    /** Localized display name (English fallback). */
    val name: String,
    /** English name, used for the stable export display. */
    val englishName: String,
    /** Localized description, if distinct from the name. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("details")
    val description: String?,
    /** LOINC's example UCUM units (may be empty). */
    val ucum: String,
    /** COMMON_TEST_RANK — lower is more commonly ordered. */
    val rank: Int,
)

/** The full structured attributes of a LOINC term (loinc.org details page). */
data class LoincDetail(
    val code: String,
    val name: String,
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("details")
    val description: String?,
    val component: String,
    val property: String,
    val timing: String,
    val system: String,
    val scale: String,
    val method: String,
    val loincClass: String,
    val status: String,
    val longName: String,
    val shortName: String,
    val ucum: String,
)

/**
 * Read-only index over the bundled LOINC catalog (`loinc.db`, produced by
 * `tools/build_loinc_resource.py` — the same file both apps ship).
 *
 * Android/JVM use the *bundled* SQLite driver so FTS5 is guaranteed on every
 * OS version; iOS uses the system SQLite (see `openReadOnlyDatabase`). A single connection is shared and serialized with a lock
 * (queries take microseconds), which keeps lookups synchronous and callable
 * from any thread.
 *
 * @param databasePath absolute path to `loinc.db` on disk.
 * @param appLanguage the app's UI language tag (e.g. `de-DE`); resolved to a
 *   language the catalog carries labels for, English otherwise.
 */
class LoincDirectory(databasePath: String, appLanguage: String) : AutoCloseable {

    private val lock = PlatformLock()
    private val connection: SQLiteConnection? = runCatching { openReadOnlyDatabase(databasePath) }.getOrNull()

    val version: String
    val count: Int
    val license: String

    /** The app-language keys the catalog carries labels for (e.g. `en`, `pt-BR`). */
    val languages: List<String>

    /** The catalog language used for display (resolved from the app language). */
    val language: String

    private val termStatement = prepare(
        """
        SELECT (SELECT name FROM label WHERE code = ?1 AND lang = ?2),
               (SELECT descr FROM label WHERE code = ?1 AND lang = ?2),
               t.english, t.ucum, t.rank
        FROM term t WHERE t.code = ?1
        """,
    )
    private val searchStatement = prepare(
        """
        SELECT l.code,
               (SELECT name FROM label WHERE code = l.code AND lang = ?2),
               (SELECT descr FROM label WHERE code = l.code AND lang = ?2),
               t.english, t.ucum, t.rank
        FROM label_fts f
        JOIN label l ON l.rowid = f.rowid
        JOIN term t ON t.code = l.code
        WHERE label_fts MATCH ?1 AND l.lang IN (?2, 'en')
        GROUP BY l.code
        ORDER BY t.rank
        LIMIT ?3 OFFSET ?4
        """,
    )
    /** Every matching label with its term's attributes, for [bestMatch] to rank (one row per label). */
    private val candidateStatement = prepare(
        """
        SELECT l.code, l.name, t.rank, t.property, t.scale, t.system
        FROM label_fts f
        JOIN label l ON l.rowid = f.rowid
        JOIN term t ON t.code = l.code
        WHERE label_fts MATCH ?1 AND l.lang IN (?2, 'en')
        ORDER BY t.rank
        LIMIT ?3
        """,
    )
    /** [candidateStatement] across every catalog language (the import resolver's fallback). */
    private val anyLanguageCandidateStatement = prepare(
        """
        SELECT l.code, l.name, t.rank, t.property, t.scale, t.system
        FROM label_fts f
        JOIN label l ON l.rowid = f.rowid
        JOIN term t ON t.code = l.code
        WHERE label_fts MATCH ?1
        ORDER BY t.rank
        LIMIT ?3
        """,
    )
    private val topStatement = prepare(
        """
        SELECT t.code,
               (SELECT name FROM label WHERE code = t.code AND lang = ?1),
               (SELECT descr FROM label WHERE code = t.code AND lang = ?1),
               t.english, t.ucum, t.rank
        FROM term t ORDER BY t.rank LIMIT ?2 OFFSET ?3
        """,
    )
    private val detailStatement = prepare(
        """
        SELECT (SELECT name FROM label WHERE code = ?1 AND lang = ?2),
               (SELECT descr FROM label WHERE code = ?1 AND lang = ?2),
               t.english, t.ucum, t.component, t.property, t.timing, t.system,
               t.scale, t.method, t.loinc_class, t.status, t.long_name, t.short_name
        FROM term t WHERE t.code = ?1
        """,
    )
    private val classificationStatement = prepare("SELECT loinc_class, component FROM term WHERE code = ?1")

    init {
        version = scalar("SELECT value FROM meta WHERE key = 'version'").orEmpty()
        count = scalar("SELECT count(*) FROM term")?.toIntOrNull() ?: 0
        license = scalar("SELECT value FROM meta WHERE key = 'license'").orEmpty()
        languages = scalar("SELECT value FROM meta WHERE key = 'languages'").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        language = matchLanguage(appLanguage) ?: "en"
    }

    val isAvailable: Boolean get() = connection != null && count > 0

    /**
     * Maps a free-form/BCP-47 tag (e.g. the parser's detected report language)
     * to a catalog language, falling back to the app language. English is
     * always searched alongside, so an unmatched tag still resolves via `en`.
     */
    fun resolvedLanguage(tag: String): String = matchLanguage(tag) ?: language

    private fun matchLanguage(tag: String): String? {
        val lower = tag.lowercase().replace('_', '-')
        if (lower.isEmpty()) return null
        languages.firstOrNull { it.lowercase() == lower }?.let { return it }
        // Script/region variants the catalog keys differently.
        if (lower.startsWith("zh")) languages.firstOrNull { it.lowercase().startsWith("zh") }?.let { return it }
        val base = lower.substringBefore('-')
        return languages.firstOrNull { it.lowercase().substringBefore('-') == base }
    }

    // region Lookups

    fun term(code: String): LoincTerm? {
        val trimmed = code.trim()
        val statement = termStatement ?: return null
        return lock.withLock {
            statement.use(trimmed, language) {
                if (!statement.step()) null else makeTerm(statement, base = 0, code = trimmed)
            }
        }
    }

    fun isKnownLoinc(code: String): Boolean = term(code) != null

    /** LOINC class + component for a code, used to derive a display category. */
    fun classification(code: String): Pair<String, String>? {
        val statement = classificationStatement ?: return null
        return lock.withLock {
            statement.use(code.trim()) {
                if (!statement.step()) null else (statement.text(0).orEmpty() to statement.text(1).orEmpty())
            }
        }
    }

    fun detail(code: String): LoincDetail? {
        val trimmed = code.trim()
        val statement = detailStatement ?: return null
        return lock.withLock {
            statement.use(trimmed, language) {
                if (!statement.step()) return@use null
                val english = statement.text(2) ?: trimmed
                val name = statement.text(0) ?: english
                val descr = statement.text(1)?.takeIf { it.isNotEmpty() && it != name }
                LoincDetail(
                    code = trimmed, name = name, description = descr,
                    component = statement.text(4).orEmpty(), property = statement.text(5).orEmpty(),
                    timing = statement.text(6).orEmpty(), system = statement.text(7).orEmpty(),
                    scale = statement.text(8).orEmpty(), method = statement.text(9).orEmpty(),
                    loincClass = statement.text(10).orEmpty(), status = statement.text(11).orEmpty(),
                    longName = statement.text(12).orEmpty(), shortName = statement.text(13).orEmpty(),
                    ucum = statement.text(3).orEmpty(),
                )
            }
        }
    }

    /**
     * Searches the catalog in [language] (plus English). An empty query lists
     * the most commonly ordered terms instead.
     */
    fun search(query: String, language: String = this.language, limit: Int = 80, offset: Int = 0): List<LoincTerm> {
        val match = ftsQuery(query)
        return lock.withLock {
            if (match != null) {
                val statement = searchStatement ?: return@withLock emptyList()
                try {
                    statement.bindText(1, match)
                    statement.bindText(2, language)
                    statement.bindLong(3, limit.toLong())
                    statement.bindLong(4, offset.toLong())
                    collect(statement)
                } finally {
                    statement.reset(); statement.clearBindings()
                }
            } else {
                val statement = topStatement ?: return@withLock emptyList()
                try {
                    statement.bindText(1, language)
                    statement.bindLong(2, limit.toLong())
                    statement.bindLong(3, offset.toLong())
                    collect(statement)
                } finally {
                    statement.reset(); statement.clearBindings()
                }
            }
        }
    }

    /**
     * The term whose label fits [query] best (see [LoincRanking]) — its
     * words, or word prefixes when [prefix] — in [language] plus English, or
     * in every catalog language when [anyLanguage]. A term that can't hold a
     * value in [unit] never fits. Null when nothing does.
     */
    fun bestMatch(
        query: String,
        language: String,
        prefix: Boolean = true,
        anyLanguage: Boolean = false,
        unit: String = "",
    ): LoincMatch? {
        val match = ftsQuery(query, prefix) ?: return null
        val candidates = lock.withLock {
            val statement = (if (anyLanguage) anyLanguageCandidateStatement else candidateStatement) ?: return@withLock emptyList()
            try {
                statement.bindText(1, match)
                if (!anyLanguage) statement.bindText(2, language)
                statement.bindLong(3, CANDIDATE_LABELS)
                val byCode = linkedMapOf<String, MutableList<String>>()
                val terms = mutableMapOf<String, LoincCandidate>()
                while (statement.step()) {
                    val code = statement.text(0).orEmpty()
                    byCode.getOrPut(code) { mutableListOf() } += statement.text(1).orEmpty()
                    if (code !in terms) {
                        terms[code] = LoincCandidate(
                            code, statement.getLong(2).toInt(), statement.text(3).orEmpty(),
                            statement.text(4).orEmpty(), statement.text(5).orEmpty(), emptyList(),
                        )
                    }
                }
                byCode.map { (code, labels) -> terms.getValue(code).copy(labels = labels) }
            } finally {
                statement.reset(); statement.clearBindings()
            }
        }
        return LoincRanking.best(query, candidates, unit)
    }

    // endregion

    override fun close() {
        lock.withLock {
            listOf(
                termStatement, searchStatement, candidateStatement, anyLanguageCandidateStatement, topStatement, detailStatement,
                classificationStatement,
            )
                .forEach { it?.close() }
            connection?.close()
        }
    }

    // region Helpers

    private fun prepare(sql: String): SQLiteStatement? =
        connection?.let { runCatching { it.prepare(sql.trimIndent()) }.getOrNull() }

    private fun scalar(sql: String): String? {
        val conn = connection ?: return null
        return runCatching {
            conn.prepare(sql).let { statement ->
                try {
                    if (statement.step()) statement.text(0) else null
                } finally {
                    statement.close()
                }
            }
        }.getOrNull()
    }

    private inline fun <T> SQLiteStatement.use(vararg args: String, block: () -> T): T {
        try {
            args.forEachIndexed { index, value -> bindText(index + 1, value) }
            return block()
        } finally {
            reset(); clearBindings()
        }
    }

    private fun collect(statement: SQLiteStatement): List<LoincTerm> {
        val results = mutableListOf<LoincTerm>()
        while (statement.step()) {
            val code = statement.text(0).orEmpty()
            results += makeTerm(statement, base = 1, code = code)
        }
        return results
    }

    private fun makeTerm(statement: SQLiteStatement, base: Int, code: String): LoincTerm {
        val localized = statement.text(base)
        val localizedDescr = statement.text(base + 1)
        val english = statement.text(base + 2) ?: code
        val name = localized ?: english
        return LoincTerm(
            code = code,
            name = name,
            englishName = english,
            description = localizedDescr?.takeIf { it.isNotEmpty() && it != name },
            ucum = statement.text(base + 3).orEmpty(),
            rank = if (statement.isNull(base + 4)) 0 else statement.getLong(base + 4).toInt(),
        )
    }

    private fun SQLiteStatement.text(index: Int): String? = if (isNull(index)) null else getText(index)

    // endregion

    companion object {
        /**
         * Turns free text into an FTS5 query of all its words, as prefixes by
         * default: `creat ser` → `"creat"* "ser"*`. Null when there are no
         * usable tokens.
         */
        fun ftsQuery(query: String, prefix: Boolean = true): String? {
            val tokens = tokens(query)
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { if (prefix) "\"$it\"*" else "\"$it\"" }
        }

        /**
         * [name]'s words (lowercased) without the little words of the shipped
         * languages ("Albumin im Urin" → "albumin urin"), which LOINC labels rarely share with a
         * printed report. Single letters and element symbols stay (Vitamin A,
         * Na, Al). Null
         * when nothing was dropped or nothing would remain.
         */
        fun withoutStopWords(name: String): String? {
            val all = tokens(name)
            val kept = all.filterNot { it in STOP_WORDS }
            return kept.takeIf { it.isNotEmpty() && it.size < all.size }?.joinToString(" ")
        }

        /**
         * Queries for spelling variants across languages: [name] with its
         * longest word cut back to ever shorter stems — "Cholesterin" →
         * "cholester…" finds "Cholesterol", "Triglyzeride" → "trigly…" finds
         * "Triglyceride". Words under 8 letters aren't cut, and a stem keeps
         * at least 6 letters and half the word. Most specific first.
         */
        fun stemmedQueries(name: String): List<String> {
            val words = tokens(name).filterNot { it in STOP_WORDS }
            val longest = words.maxByOrNull { it.length }?.takeIf { it.length >= 8 } ?: return emptyList()
            val shortest = maxOf(6, (longest.length + 1) / 2)
            return (longest.length - 1 downTo shortest).map { length ->
                words.joinToString(" ") { if (it === longest) longest.take(length) else it }
            }
        }

        /**
         * [name] without the word for "total" ("Cholesterin gesamt",
         * "Colesterolo totale"): LOINC calls total cholesterol plain
         * "Cholesterol" and has "total" only in ratios like Cholesterol.total/
         * HDL. Null when there's no such word or nothing else.
         */
        fun withoutTotal(name: String): String? {
            val all = tokens(name).filterNot { it in STOP_WORDS }
            val kept = all.filterNot { it in TOTAL_WORDS }
            return kept.takeIf { it.isNotEmpty() && it.size < all.size }?.joinToString(" ")
        }

        /** Catalog rows read per lookup — every label of the few hundred most common matching terms. */
        private const val CANDIDATE_LABELS = 400L

        private fun tokens(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        private val TOTAL_WORDS = setOf(
            "total", "totale", "totaal", "totalt", "gesamt", "całkowity", "całkowita", "całkowite", "celkový", "celková",
            "celkové", "ukupni", "ukupna", "ukupno", "összes", "totală", "toplam", "ολική", "ολικό", "общий", "загальний", "общ",
        )

        internal val STOP_WORDS = setOf(
            // de, en
            "der", "die", "das", "des", "dem", "den", "im", "in", "am", "an", "von", "vom", "mit", "und", "auf", "bei", "zum", "zur",
            "of", "the", "and", "for", "at", "by", "with", "on",
            // fr, es, ca, pt, it, ro
            "de", "du", "la", "le", "les", "dans", "et", "en", "au", "aux", "del", "el", "los", "las", "da", "do", "dos",
            "em", "di", "della", "nel", "nella", "dels", "din", "și", "si", "în",
            // nl, da, sv, fi, hu, tr
            "van", "het", "op", "og", "av", "på", "och", "af", "ja", "az", "és", "ve",
            // pl, cs, sk (not "na": sodium)
            "we", "ze",
            // el, ru, uk, bg
            "σε", "στο", "στη", "του", "της", "και", "во", "на", "из", "від", "със",
        )
    }
}
