package dev.idoodler.labimporter.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.format.DateFormat
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withTranslation
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.data.PatientDetails
import dev.idoodler.labimporter.data.PatientSex
import dev.idoodler.labimporter.core.export.PdfLayout
import dev.idoodler.labimporter.core.export.PdfMetric
import dev.idoodler.labimporter.core.export.PdfPageFormat
import dev.idoodler.labimporter.core.export.PdfReport
import dev.idoodler.labimporter.core.export.PdfReportData
import dev.idoodler.labimporter.core.export.PdfTimeRange
import dev.idoodler.labimporter.core.model.ChartAxis
import dev.idoodler.labimporter.core.model.LabCategory
import dev.idoodler.labimporter.core.model.RangeStatus
import dev.idoodler.labimporter.core.platform.NumberFormatting
import dev.idoodler.labimporter.ui.components.labelRes
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How the PDF is coloured — monochrome prints legibly in grayscale and saves toner. */
enum class PdfColorMode { COLOR, MONOCHROME }

/** Everything that describes one export (the iOS `PDFExportOptions`). */
data class PdfOptions(
    val selectedCodes: Set<String>,
    val timeRange: PdfTimeRange = PdfTimeRange.YEAR_1,
    val colorMode: PdfColorMode = PdfColorMode.COLOR,
    val pageFormat: PdfPageFormat = PdfPageFormat.A4,
    val includeSummary: Boolean = true,
    val includeLatestResults: Boolean = true,
    val includeTrends: Boolean = true,
)

/**
 * Draws the export's pages — cover, latest results, trend charts — with
 * [PdfDocument], the Android counterpart of the iOS SwiftUI page views. What
 * goes on which page is decided by the shared core (`PdfReport`), so both
 * apps produce the same document.
 */
class PdfReportRenderer(private val context: Context) {

    /** The pages [options] produce, laid out by the shared core. */
    fun pages(data: PdfReportData, options: PdfOptions): List<PdfReport.Page> =
        PdfReport.pages(data, options.pageFormat, options.includeSummary, options.includeLatestResults, options.includeTrends)

    /** @throws IllegalArgumentException when nothing is selected. */
    fun render(data: PdfReportData, options: PdfOptions, patient: PatientDetails, generatedAt: LocalDateTime, out: OutputStream) {
        val pages = pages(data, options)
        require(data.metrics.isNotEmpty() && pages.isNotEmpty()) { "Nothing selected" }
        val format = options.pageFormat
        val document = PdfDocument()
        try {
            pages.indices.forEach { index ->
                val info = PdfDocument.PageInfo.Builder(format.width.toInt(), format.height.toInt(), index + 1).create()
                val pdfPage = document.startPage(info)
                drawPage(pdfPage.canvas, pages, index, data, options, patient, generatedAt)
                document.finishPage(pdfPage)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /**
     * Draws page [index] of [pages] onto [canvas] (units are points) and
     * returns where its content ends — above [footerTop], so nothing clips.
     */
    fun drawPage(
        canvas: Canvas,
        pages: List<PdfReport.Page>,
        index: Int,
        data: PdfReportData,
        options: PdfOptions,
        patient: PatientDetails,
        generatedAt: LocalDateTime,
    ): Float = PageCanvas(canvas, options.pageFormat, PdfTheme(options.colorMode == PdfColorMode.COLOR)).run {
        when (val page = pages[index]) {
            PdfReport.Page.Cover -> cover(data, patient, generatedAt)
            is PdfReport.Page.Latest -> latest(page, data.patientName)
            is PdfReport.Page.Trends -> trends(page, data.timeRange, data.patientName)
        }
        val end = y
        footer(index + 1, pages.size)
        end
    }

    /** Where the footer's hairline sits. */
    fun footerTop(format: PdfPageFormat): Float = format.height.toFloat() - PdfLayout.MARGIN.toFloat() - FOOTER_HEIGHT

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun timeRangeLabel(range: PdfTimeRange, long: Boolean): String = string(
        when (range) {
            PdfTimeRange.MONTHS_3 -> if (long) R.string.pdf_last_3_months else R.string.window_3m
            PdfTimeRange.MONTHS_6 -> if (long) R.string.pdf_last_6_months else R.string.window_6m
            PdfTimeRange.YEAR_1 -> if (long) R.string.pdf_last_12_months else R.string.window_1y
            PdfTimeRange.YEAR_2 -> if (long) R.string.pdf_last_2_years else R.string.window_2y
            PdfTimeRange.ALL -> if (long) R.string.pdf_all_time else R.string.window_all
        },
    )

    private fun value(v: Double) = NumberFormatting.cdaValue(v)

    private fun date(d: LocalDate, style: FormatStyle) = d.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(style))

    /** Drawing helpers for one page; units are PostScript points. */
    private inner class PageCanvas(val canvas: Canvas, val format: PdfPageFormat, val theme: PdfTheme) {
        val margin = PdfLayout.MARGIN.toFloat()
        val left = margin
        val right = format.width.toFloat() - margin
        val width = right - left
        val bottom = format.height.toFloat() - margin
        var y = margin

        fun text(size: Float, color: Int, bold: Boolean = false, tabular: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (tabular) fontFeatureSettings = "tnum"
        }

        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

        fun stroke(color: Int, width: Float = 0.5f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = width
        }

        /** Draws [value] left-aligned in a box of [boxWidth], up to [maxLines]; returns its height. */
        fun block(value: String, paint: TextPaint, x: Float, top: Float, boxWidth: Float, maxLines: Int = Int.MAX_VALUE, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, boxWidth.toInt().coerceAtLeast(1))
                .setAlignment(align).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.withTranslation(x, top) { layout.draw(this) }
            return layout.height.toFloat()
        }

        /** One line at a baseline, right-aligned at [endX]. */
        fun lineRight(value: String, paint: TextPaint, endX: Float, baseline: Float) {
            canvas.drawText(value, endX - paint.measureText(value), baseline, paint)
        }

        fun hairline(atY: Float) = canvas.drawLine(left, atY, right, atY, stroke(theme.hairline))

        fun runningHeader(patientName: String) {
            val title = text(11f, theme.textSecondary, bold = true)
            canvas.drawText(string(R.string.lab_report), left, y + 11f, title)
            if (patientName.isNotEmpty()) lineRight(patientName, text(11f, theme.textTertiary), right, y + 11f)
            y += 19f
            hairline(y)
            y += 16f
        }

        fun sectionHeader(title: String, subtitle: String?) {
            y += block(title, text(18f, theme.textPrimary, bold = true), left, y, width)
            if (subtitle != null) y += 2f + block(subtitle, text(11f, theme.textSecondary), left, y + 2f, width)
            y += 14f
        }

        fun footer(page: Int, total: Int) {
            val top = bottom - FOOTER_HEIGHT
            hairline(top)
            val paint = text(9f, theme.textTertiary)
            canvas.drawText("LabImporter", left, top + 14f, paint.apply { typeface = Typeface.DEFAULT_BOLD })
            lineRight(string(R.string.pdf_page_of, page, total), text(9f, theme.textTertiary), right, top + 14f)
        }

        fun card(rect: RectF, radius: Float) {
            canvas.drawRoundRect(rect, radius, radius, fill(theme.cardBackground))
            canvas.drawRoundRect(rect, radius, radius, stroke(theme.cardStroke))
        }

        // region Cover

        fun cover(data: PdfReportData, patient: PatientDetails, generatedAt: LocalDateTime) {
            banner(data, generatedAt)
            y += 22f
            patientCard(data, patient, generatedAt.toLocalDate())
            y += 22f
            stats(data)
            y += 22f
            if (data.categories.isNotEmpty()) legend(data.categories)
            // The disclaimer sits at the bottom, above the footer.
            val disclaimer = string(R.string.pdf_disclaimer)
            val paint = text(9f, theme.textTertiary)
            val height = StaticLayout.Builder.obtain(disclaimer, 0, disclaimer.length, paint, width.toInt()).build().height
            block(disclaimer, paint, left, bottom - 30f - height, width)
        }

        private fun banner(data: PdfReportData, generatedAt: LocalDateTime) {
            val height = if (theme.color) 112f else 84f
            val rect = RectF(left, y, right, y + height)
            val foreground = if (theme.color) 0xFFFFFFFF.toInt() else theme.textPrimary
            val secondary = if (theme.color) 0xD9FFFFFF.toInt() else theme.textSecondary
            val inset = if (theme.color) 24f else 0f
            if (theme.color) {
                val colors = theme.headerGradient(data.dominantCategories)
                val paint = fill(colors.first()).apply {
                    shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, colors.toIntArray(), null, Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(rect, 20f, 20f, paint)
                canvas.drawCircle(rect.left + inset + 32f, rect.centerY(), 32f, fill(0x38FFFFFF))
            } else {
                canvas.drawLine(left, rect.bottom, right, rect.bottom, stroke(theme.hairline, 1f))
            }
            // A heart-and-card glyph drawn with paths (no icon fonts in print).
            val cx = rect.left + inset + 32f
            val cy = if (theme.color) rect.centerY() else rect.top + 36f
            canvas.drawRoundRect(RectF(cx - 14f, cy - 16f, cx + 14f, cy + 16f), 5f, 5f, stroke(foreground, 2.2f))
            heart(cx, cy + 2f, 9f, fill(foreground))
            val textLeft = rect.left + inset + 80f
            val titleTop = (if (theme.color) rect.centerY() - 24f else rect.top + 14f)
            canvas.drawText(string(R.string.lab_report), textLeft, titleTop + 26f, text(30f, foreground, bold = true))
            val generated = generatedAt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT))
            canvas.drawText(string(R.string.pdf_generated, generated), textLeft, titleTop + 46f, text(12f, secondary))
            y += height
        }

        private fun heart(cx: Float, cy: Float, r: Float, paint: Paint) {
            val path = Path().apply {
                moveTo(cx, cy + r)
                cubicTo(cx - r * 1.6f, cy - r * 0.2f, cx - r * 0.6f, cy - r * 1.4f, cx, cy - r * 0.45f)
                cubicTo(cx + r * 0.6f, cy - r * 1.4f, cx + r * 1.6f, cy - r * 0.2f, cx, cy + r)
                close()
            }
            canvas.drawPath(path, paint)
        }

        private fun patientCard(data: PdfReportData, patient: PatientDetails, today: java.time.LocalDate) {
            val items = buildList {
                if (data.patientName.isNotEmpty()) add(string(R.string.patient) to data.patientName)
                patient.dateOfBirth?.let { dob ->
                    val years = Period.between(dob.toJavaLocalDate(), today).years
                    val age = if (years >= 0) " (${string(R.string.pdf_age, years)})" else ""
                    add(string(R.string.date_of_birth) to date(dob, FormatStyle.LONG) + age)
                }
                patient.sex?.let { sex ->
                    add(string(R.string.sex) to string(when (sex) { PatientSex.FEMALE -> R.string.sex_female; PatientSex.MALE -> R.string.sex_male; PatientSex.OTHER -> R.string.sex_other }))
                }
                if (data.authorName.isNotEmpty()) add(string(R.string.pdf_author) to data.authorName)
                val earliest = data.earliestDate
                val latest = data.latestDate
                if (earliest != null && latest != null) {
                    val from = date(earliest, FormatStyle.MEDIUM)
                    val to = date(latest, FormatStyle.MEDIUM)
                    add(string(R.string.pdf_period_covered) to if (from == to) from else "$from – $to")
                }
            }
            if (items.isEmpty()) return
            val rowHeight = 24f
            val rect = RectF(left, y, right, y + 18f * 2 + rowHeight * items.size - 12f)
            card(rect, 16f)
            var row = rect.top + 18f + 12f
            for ((label, value) in items) {
                canvas.drawCircle(left + 18f + 4f, row - 4f, 3f, fill(theme.textTertiary))
                canvas.drawText(label, left + 18f + 18f, row, text(12f, theme.textSecondary))
                lineRight(value, text(12f, theme.textPrimary, bold = true), right - 18f, row)
                row += rowHeight
            }
            y = rect.bottom
        }

        private fun stats(data: PdfReportData) {
            val tiles = listOf(
                Triple("${data.reportCount}", string(R.string.stat_reports), false),
                Triple("${data.metrics.size}", string(R.string.pdf_metrics), false),
                Triple("${data.outOfRangeCount}", string(R.string.pdf_out_of_range), data.outOfRangeCount > 0),
                Triple(timeRangeLabel(data.timeRange, long = false), string(R.string.pdf_trend_range), false),
            )
            val gap = 12f
            val tileWidth = (width - gap * (tiles.size - 1)) / tiles.size
            val height = 72f
            tiles.forEachIndexed { i, (number, label, emphasised) ->
                val x = left + i * (tileWidth + gap)
                card(RectF(x, y, x + tileWidth, y + height), 14f)
                val valuePaint = text(24f, if (emphasised) theme.valueColor(RangeStatus.HIGH) else theme.textPrimary, bold = true, tabular = true)
                canvas.drawText(number, x + (tileWidth - valuePaint.measureText(number)) / 2, y + 36f, valuePaint)
                block(label, text(10f, theme.textSecondary), x + 4f, y + 44f, tileWidth - 8f, 2, Layout.Alignment.ALIGN_CENTER)
            }
            y += height
        }

        private fun legend(categories: List<LabCategory>) {
            canvas.drawText(string(R.string.pdf_included_categories), left, y + 11f, text(11f, theme.textSecondary, bold = true))
            y += 21f
            val chipPaint = text(11f, theme.textPrimary)
            var x = left
            val height = 22f
            for (category in categories) {
                val label = string(category.labelRes)
                val chipWidth = 10f + 8f + 6f + chipPaint.measureText(label) + 10f
                if (x + chipWidth > right) {
                    x = left
                    y += height + 8f
                }
                val rect = RectF(x, y, x + chipWidth, y + height)
                if (theme.color) canvas.drawRoundRect(rect, 11f, 11f, fill(withAlpha(category.argb.toInt(), 0.1f)))
                canvas.drawRoundRect(rect, 11f, 11f, stroke(theme.cardStroke))
                canvas.drawCircle(x + 14f, rect.centerY(), 4f, fill(theme.categoryColor(category)))
                canvas.drawText(label, x + 24f, rect.centerY() + 4f, chipPaint)
                x += chipWidth + 8f
            }
            y += height
        }

        // endregion

        // region Latest results

        private val resultWidth = 96f
        private val referenceWidth = 92f
        private val dateWidth = 66f
        private val gap = 8f

        fun latest(page: PdfReport.Page.Latest, patientName: String) {
            runningHeader(patientName)
            if (page.showTitle) sectionHeader(string(R.string.pdf_latest_results), string(R.string.pdf_latest_subtitle))
            tableHeader()
            for (row in page.rows) {
                when (row) {
                    is PdfReport.LatestRow.Heading -> categoryRow(row.category, row.count)
                    is PdfReport.LatestRow.Value -> metricRow(row.metric, zebra = row.striped)
                }
            }
        }

        private fun columnEnds(): Triple<Float, Float, Float> {
            val dateEnd = right - 8f
            val referenceEnd = dateEnd - dateWidth - gap
            val resultEnd = referenceEnd - referenceWidth - gap
            return Triple(resultEnd, referenceEnd, dateEnd)
        }

        private fun tableHeader() {
            val paint = text(9f, theme.textTertiary, bold = true)
            val (resultEnd, referenceEnd, dateEnd) = columnEnds()
            val baseline = y + 9f
            canvas.drawText(string(R.string.pdf_column_test).uppercase(), left + 8f, baseline, paint)
            lineRight(string(R.string.pdf_column_result).uppercase(), paint, resultEnd, baseline)
            lineRight(string(R.string.pdf_column_reference).uppercase(), paint, referenceEnd, baseline)
            lineRight(string(R.string.pdf_column_date).uppercase(), paint, dateEnd, baseline)
            y += 13f
            hairline(y)
        }

        private fun categoryRow(category: LabCategory, count: Int) {
            y += 12f
            canvas.drawCircle(left + 8f + 4.5f, y + 6f, 4.5f, fill(theme.categoryColor(category)))
            canvas.drawText(string(category.labelRes), left + 25f, y + 10f, text(11f, theme.textPrimary, bold = true))
            lineRight("$count", text(10f, theme.textTertiary, tabular = true), right - 8f, y + 10f)
            y += 19f
        }

        private fun metricRow(metric: PdfMetric, zebra: Boolean) {
            val (resultEnd, referenceEnd, dateEnd) = columnEnds()
            val nameWidth = resultEnd - resultWidth - gap - (left + 8f)
            val namePaint = text(11f, theme.textPrimary)
            val nameLayout = StaticLayout.Builder.obtain(metric.name, 0, metric.name.length, namePaint, nameWidth.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            val height = 6f + nameLayout.height + 1f + 10f + 6f
            if (zebra && theme.color) canvas.drawRect(left, y, right, y + height, fill(theme.rowAlternate))
            canvas.withTranslation(left + 8f, y + 6f) { nameLayout.draw(this) }
            canvas.drawText(metric.code.uppercase(), left + 8f, y + 6f + nameLayout.height + 9f, text(8f, theme.textTertiary))
            val baseline = y + 6f + 11f
            val status = metric.latestStatus
            val result = metric.latest?.let { latest -> value(latest.value).let { if (metric.unit.isEmpty()) it else "$it ${metric.unit}" } } ?: "—"
            val resultPaint = text(11f, theme.valueColor(status), bold = true, tabular = true)
            lineRight(result, resultPaint, resultEnd, baseline)
            if (status != null && status.isOutOfRange) badge(status, resultEnd - resultPaint.measureText(result) - 4f, baseline)
            lineRight(metric.referenceRange?.formatted(number = ::value) ?: "—", text(9f, theme.textSecondary, tabular = true), referenceEnd, baseline)
            lineRight(metric.latest?.date?.let { date(it, FormatStyle.SHORT) } ?: "—", text(9f, theme.textSecondary, tabular = true), dateEnd, baseline)
            y += height
        }

        /** A High/Low capsule ending at [endX]: tinted in colour, outlined in monochrome. */
        private fun badge(status: RangeStatus, endX: Float, baseline: Float) {
            val label = (if (status == RangeStatus.HIGH) "↑ " else "↓ ") + string(if (status == RangeStatus.HIGH) R.string.range_high else R.string.range_low)
            val paint = text(8f, theme.statusColor(status), bold = true)
            val w = paint.measureText(label) + 10f
            val rect = RectF(endX - w, baseline - 9f, endX, baseline + 3f)
            if (theme.color) canvas.drawRoundRect(rect, 6f, 6f, fill(withAlpha(theme.statusColor(status), 0.14f)))
            else canvas.drawRoundRect(rect, 6f, 6f, stroke(theme.statusColor(status)))
            canvas.drawText(label, rect.left + 5f, baseline, paint)
        }

        // endregion

        // region Trends

        fun trends(page: PdfReport.Page.Trends, range: PdfTimeRange, patientName: String) {
            runningHeader(patientName)
            if (page.showTitle) sectionHeader(string(R.string.pdf_trends), timeRangeLabel(range, long = true))
            // Cards share the page evenly, so even two-line names never push one into the footer.
            val gap = 14f
            val count = page.metrics.size
            val space = (bottom - FOOTER_HEIGHT - 8f - y - gap * (count - 1)) / count
            page.metrics.forEachIndexed { i, metric ->
                trendCard(metric, space)
                if (i < count - 1) y += gap
            }
        }

        private fun trendCard(metric: PdfMetric, maxHeight: Float) {
            val accent = theme.categoryColor(metric.category)
            val namePaint = text(12f, theme.textPrimary, bold = true)
            val headerTextWidth = width - 28f - 17f - 130f
            val nameLayout = StaticLayout.Builder.obtain(metric.name, 0, metric.name.length, namePaint, headerTextWidth.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            val headerHeight = nameLayout.height + 11f
            val statsHeight = 26f
            val chartHeight = (maxHeight - (14f + headerHeight + 8f + 8f + statsHeight + 14f)).coerceIn(60f, 118f)
            val rect = RectF(left, y, right, y + 14f + headerHeight + 8f + chartHeight + 8f + statsHeight + 14f)
            card(rect, 14f)
            val inner = RectF(rect.left + 14f, rect.top + 14f, rect.right - 14f, rect.bottom - 14f)

            // Header: dot, name + code, status, latest value.
            canvas.drawCircle(inner.left + 4.5f, inner.top + 7f, 4.5f, fill(accent))
            canvas.withTranslation(inner.left + 17f, inner.top) { nameLayout.draw(this) }
            canvas.drawText(metric.code.uppercase(), inner.left + 17f, inner.top + nameLayout.height + 9f, text(8f, theme.textTertiary))
            metric.latest?.let { latest ->
                val unitPaint = text(9f, theme.textSecondary)
                val valuePaint = text(14f, theme.valueColor(metric.latestStatus), bold = true, tabular = true)
                val unitWidth = if (metric.unit.isEmpty()) 0f else unitPaint.measureText(metric.unit) + 3f
                val number = value(latest.value)
                val baseline = inner.top + 13f
                if (metric.unit.isNotEmpty()) lineRight(metric.unit, unitPaint, inner.right, baseline)
                lineRight(number, valuePaint, inner.right - unitWidth, baseline)
                metric.latestStatus?.takeIf { it.isOutOfRange }?.let { badge(it, inner.right - unitWidth - valuePaint.measureText(number) - 6f, baseline) }
            }

            chart(metric, RectF(inner.left, inner.top + headerHeight + 8f, inner.right, inner.top + headerHeight + 8f + chartHeight), accent)
            statsRow(metric, RectF(inner.left, inner.bottom - statsHeight, inner.right, inner.bottom))
            y = rect.bottom
        }

        private fun chart(metric: PdfMetric, area: RectF, accent: Int) {
            val points = metric.windowPoints
            val range = metric.referenceRange
            val values = points.map { it.value } + listOfNotNull(range?.low, range?.high)
            // Rounded ticks like Swift Charts' automatic axis (shared, so both apps agree).
            val ticks = ChartAxis.ticks(values.min(), values.max())
            val min = ticks.first()
            val max = ticks.last()
            val labelPaint = text(7f, theme.textSecondary, tabular = true)
            val axisWidth = ticks.maxOf { labelPaint.measureText(value(it)) } + 6f
            val plot = RectF(area.left + axisWidth, area.top + 4f, area.right, area.bottom - 12f)
            val firstDay = points.first().date.toEpochDays().toDouble()
            val lastDay = points.last().date.toEpochDays().toDouble().let { if (it - firstDay < 1) firstDay + 1 else it }
            fun x(d: LocalDate) = plot.left + ((d.toEpochDays() - firstDay) / (lastDay - firstDay)).toFloat() * plot.width()
            fun yOf(v: Double) = plot.bottom - ((v - min) / (max - min)).toFloat() * plot.height()

            // Grid and labels: a row per tick, four dates.
            val grid = stroke(theme.chartGrid)
            for (tick in ticks) {
                val gy = yOf(tick)
                canvas.drawLine(plot.left, gy, plot.right, gy, grid)
                lineRight(value(tick), labelPaint, plot.left - 4f, gy + 2.5f)
            }
            val locale = context.resources.configuration.locales[0]
            val month = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMyy"), locale)
            for (i in 0..3) {
                val day = firstDay + (lastDay - firstDay) * i / 3
                val d = LocalDate.fromEpochDays(day.toLong())
                val gx = plot.left + plot.width() * i / 3
                canvas.drawLine(gx, plot.top, gx, plot.bottom, grid)
                val label = d.toJavaLocalDate().format(month)
                val w = labelPaint.measureText(label)
                canvas.drawText(label, (gx - w / 2).coerceIn(plot.left, plot.right - w), area.bottom - 2f, labelPaint)
            }

            // Reference range guides.
            val dash = DashPathEffect(floatArrayOf(4f, 3f), 0f)
            range?.low?.let { low -> canvas.drawLine(plot.left, yOf(low), plot.right, yOf(low), stroke(withAlpha(theme.statusColor(RangeStatus.LOW), 0.5f), 0.75f).apply { pathEffect = dash }) }
            range?.high?.let { high -> canvas.drawLine(plot.left, yOf(high), plot.right, yOf(high), stroke(withAlpha(theme.statusColor(RangeStatus.HIGH), 0.5f), 0.75f).apply { pathEffect = dash }) }

            val line = Path()
            points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.date), yOf(p.value)) else line.lineTo(x(p.date), yOf(p.value)) }
            if (theme.color) {
                val fillPath = Path(line).apply {
                    lineTo(x(points.last().date), plot.bottom)
                    lineTo(x(points.first().date), plot.bottom)
                    close()
                }
                canvas.drawPath(fillPath, fill(accent).apply {
                    shader = LinearGradient(0f, plot.top, 0f, plot.bottom, withAlpha(accent, 0.22f), withAlpha(accent, 0.03f), Shader.TileMode.CLAMP)
                })
            }
            canvas.drawPath(line, stroke(accent, 1.5f))
            for (p in points) {
                val status = range?.status(p.value)
                val color = if (status != null && status.isOutOfRange) theme.statusColor(status) else accent
                canvas.drawCircle(x(p.date), yOf(p.value), 2.2f, fill(color))
            }
        }

        private fun statsRow(metric: PdfMetric, area: RectF) {
            val change = metric.change
            val cells = listOf(
                (metric.minValue?.let(::value) ?: "—") to string(R.string.stat_min),
                (metric.maxValue?.let(::value) ?: "—") to string(R.string.stat_max),
                (metric.averageValue?.let(::value) ?: "—") to string(R.string.stat_avg),
                (change?.let { (if (it > 0) "↑ +" else if (it < 0) "↓ " else "") + value(it) } ?: "—") to string(R.string.pdf_change),
            )
            val cellWidth = area.width() / cells.size
            cells.forEachIndexed { i, (number, label) ->
                val cx = area.left + cellWidth * i + cellWidth / 2
                val valuePaint = text(11f, theme.textPrimary, bold = true, tabular = true)
                canvas.drawText(number, cx - valuePaint.measureText(number) / 2, area.top + 11f, valuePaint)
                val labelPaint = text(8f, theme.textTertiary)
                canvas.drawText(label, cx - labelPaint.measureText(label) / 2, area.top + 22f, labelPaint)
                if (i > 0) canvas.drawLine(area.left + cellWidth * i, area.top + 2f, area.left + cellWidth * i, area.top + 24f, stroke(theme.hairline))
            }
        }

        // endregion
    }
}

private const val FOOTER_HEIGHT = 18f

private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

/** Print colours, the iOS `PDFTheme`: solid fills, graphite in monochrome. */
private class PdfTheme(val color: Boolean) {
    val textPrimary = gray(0.12f)
    val textSecondary = gray(0.45f)
    val textTertiary = gray(0.6f)
    val hairline = gray(0.86f)
    val cardBackground = if (color) gray(0.975f) else 0xFFFFFFFF.toInt()
    val cardStroke = gray(0.82f)
    val rowAlternate = gray(0.965f)
    val chartGrid = gray(0.9f)

    fun categoryColor(category: LabCategory): Int = if (color) category.argb.toInt() else gray(0.32f)

    fun statusColor(status: RangeStatus): Int = when {
        !color -> gray(0.2f)
        status == RangeStatus.HIGH -> HIGH
        status == RangeStatus.LOW -> LOW
        else -> textSecondary
    }

    fun valueColor(status: RangeStatus?): Int = when {
        status == null || !status.isOutOfRange -> textPrimary
        color -> statusColor(status)
        else -> 0xFF000000.toInt()
    }

    fun headerGradient(categories: List<LabCategory>): List<Int> {
        if (!color) return listOf(gray(0.22f), gray(0.40f))
        val colors = categories.take(3).map { it.argb.toInt() }
        return when (colors.size) {
            0 -> listOf(ACCENT, withAlpha(ACCENT, 0.7f))
            1 -> listOf(colors[0], withAlpha(colors[0], 0.6f))
            else -> colors
        }
    }

    private companion object {
        const val HIGH = 0xFFFF9500.toInt() // iOS system orange
        const val LOW = 0xFF007AFF.toInt() // iOS system blue
        const val ACCENT = 0xFF5856D6.toInt()

        fun gray(white: Float): Int {
            val v = (white * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
