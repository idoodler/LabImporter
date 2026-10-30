package dev.idoodler.labimporter.core.parse

/**
 * Splits a long report into parts the on-device model reads one at a time,
 * and merges what it read. Both apps' models have a small context — Foundation
 * Models 4,096 tokens, Gemini Nano about 4K in and 2K out — that has to hold
 * the instructions, the schema, the report text *and* every entry read from
 * it, so a multi-page report never fits in one go.
 *
 * A report that fits in one part is read exactly as before. Each platform
 * runs the parts through its model in order, splits a part again when the
 * model still says it's too long ([smaller]), and merges the replies
 * ([merge]) before the shared [LabParsePipeline] post-processing.
 */
object LabParseChunks {

    /**
     * Characters of report text per part. With the instructions, the schema
     * and a reply of one entry per printed row, it stays inside both models'
     * limits; a part that still doesn't fit is halved.
     */
    const val MAX_CHARS = 2_000

    /** A part this short that still doesn't fit isn't split again: the user gets the "too long" advice. */
    const val MIN_CHARS = 250

    /**
     * Lines a part repeats from the end of the one before, so a test name
     * separated from its value at the cut is read whole in the next part.
     * The repeated rows are read twice; [merge] drops the second reading.
     */
    const val OVERLAP_LINES = 2

    /** The parts of [text] to read, each at most [MAX_CHARS] long (or [text] itself when it fits). */
    fun parts(text: String): List<String> = split(text, MAX_CHARS)

    /**
     * [part] split in about half for a model that found it too long, or null
     * when it's already too short to split.
     */
    fun smaller(part: String): List<String>? =
        if (part.length < 2 * MIN_CHARS) null else split(part, part.length / 2 + 1).takeIf { it.size > 1 }

    /**
     * Cuts [text] between lines into parts of at most [maxChars]. A line
     * longer than that (a scanned page's text can arrive as one line) is
     * wrapped at word breaks first. Each part after the first starts with
     * the last [OVERLAP_LINES] lines of the one before.
     */
    fun split(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val lines = text.lines().map { it.trimEnd() }.flatMap { wrap(it, maxChars) }
        val parts = mutableListOf<String>()
        var current = mutableListOf<String>()
        var length = 0
        var hasNewLines = false
        for (line in lines) {
            if (line.isBlank() && current.isEmpty()) continue
            if (length + line.length + 1 > maxChars && hasNewLines) {
                parts += current.joinToString("\n").trim()
                current = carriedOver(current, room = maxChars - line.length - 1)
                length = current.sumOf { it.length + 1 }
                hasNewLines = false
            }
            current += line
            length += line.length + 1
            if (line.isNotBlank()) hasNewLines = true
        }
        if (hasNewLines) parts += current.joinToString("\n").trim()
        return parts
    }

    /**
     * The last [OVERLAP_LINES] lines of [part] that fit in [room] — fewer
     * when they don't, so the overlap never crowds out the next new line.
     */
    private fun carriedOver(part: List<String>, room: Int): MutableList<String> {
        val carried = ArrayDeque<String>()
        var length = 0
        for (line in part.asReversed().filter { it.isNotBlank() }) {
            if (carried.size == OVERLAP_LINES || length + line.length + 1 > room) break
            carried.addFirst(line)
            length += line.length + 1
        }
        return carried.toMutableList()
    }

    /** [line] in pieces of at most [maxChars], cut at the last space before the limit where there is one. */
    private fun wrap(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val pieces = mutableListOf<String>()
        var rest = line
        while (rest.length > maxChars) {
            val space = rest.lastIndexOf(' ', maxChars)
            val cut = if (space > 0) space else maxChars
            pieces += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    /**
     * One report from the replies to every part: their entries in order,
     * each only once (the parts overlap, so a row can be read twice), and the
     * first date, patient and lab any part found. The language is the one
     * most parts named — a cover page can be in another language than the results.
     */
    fun merge(reports: List<AiLabReport>): AiLabReport {
        if (reports.size == 1) return reports.single()
        val seen = mutableSetOf<List<String>>()
        val entries = reports.flatMap { it.entries }.filter { entry ->
            seen.add(listOf(entry.code, entry.name, entry.rawValue, entry.unit).map { it.trim().lowercase() })
        }
        fun first(field: (AiLabReport) -> String) = reports.map(field).firstOrNull { it.isNotBlank() }.orEmpty()
        val language = reports.map { it.reportLanguage.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        return AiLabReport(
            entries = entries,
            reportDate = first { it.reportDate },
            patientName = first { it.patientName },
            authorName = first { it.authorName },
            reportLanguage = language,
        )
    }
}
