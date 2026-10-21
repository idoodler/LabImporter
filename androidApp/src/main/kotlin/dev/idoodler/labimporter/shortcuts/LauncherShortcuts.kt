package dev.idoodler.labimporter.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import dev.idoodler.labimporter.MainActivity
import dev.idoodler.labimporter.R
import dev.idoodler.labimporter.core.model.Metric
import dev.idoodler.labimporter.ui.components.formatNumber

/**
 * The app's launcher shortcuts — Android's counterpart of the iOS Spotlight
 * index and App Shortcuts. "Scan a Lab Report" is static (`res/xml/shortcuts.xml`);
 * every tracked value gets a dynamic shortcut opening its trend, which the
 * launcher also offers in its search. Like iOS, a shortcut carries the value's
 * *name* only; its latest reading is added solely when the user opts in
 * (Settings › Show Latest Value in Search). The intent carries a LOINC code,
 * never a reading.
 */
object LauncherShortcuts {
    const val ACTION_SCAN = "dev.idoodler.labimporter.action.SCAN"
    const val ACTION_OPEN_METRIC = "dev.idoodler.labimporter.action.OPEN_METRIC"
    const val EXTRA_CODE = "dev.idoodler.labimporter.extra.CODE"
    private const val METRIC_PREFIX = "metric-"

    /** Scan, from `shortcuts.xml`. */
    private const val STATIC_COUNT = 1

    /** The shortcut id of a value's trend. */
    fun id(code: String) = METRIC_PREFIX + code

    /**
     * Publishes one shortcut per value in dashboard order (pinned first), as many
     * as the launcher allows next to the static Scan shortcut.
     */
    fun update(context: Context, metrics: List<Metric>, showLatestValue: Boolean) {
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC_COUNT).coerceAtLeast(0)
        val shortcuts = metrics.take(room).mapIndexed { rank, metric -> shortcut(context, metric, rank, showLatestValue) }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** What a value's shortcut says: its name, and its latest reading only when opted in. */
    fun label(metric: Metric, showLatestValue: Boolean): String {
        if (!showLatestValue) return metric.name
        val reading = listOf(formatNumber(metric.value), metric.entry.unit).filter { it.isNotBlank() }.joinToString(" ")
        return "${metric.name} · $reading"
    }

    private fun shortcut(context: Context, metric: Metric, rank: Int, showLatestValue: Boolean): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id(metric.code))
            .setShortLabel(metric.name)
            .setLongLabel(label(metric, showLatestValue))
            .setIcon(icon(context, metric))
            .setRank(rank)
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_OPEN_METRIC)
                    .putExtra(EXTRA_CODE, metric.code)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()

    /** The value's category colour with a white test tube, as an adaptive icon. */
    private fun icon(context: Context, metric: Metric): IconCompat {
        val size = 216 // 108dp at 2x; the launcher masks and scales it.
        val bitmap: Bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(metric.category.argb.toInt())
        ContextCompat.getDrawable(context, R.drawable.ic_shortcut_value)?.let { glyph ->
            val glyphSize = size * 48 / 108 // Inside the adaptive icon's safe zone.
            val inset = (size - glyphSize) / 2
            glyph.setBounds(inset, inset, inset + glyphSize, inset + glyphSize)
            glyph.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
