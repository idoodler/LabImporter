package dev.idoodler.labimporter.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import dev.idoodler.labimporter.core.model.Dashboard
import dev.idoodler.labimporter.core.model.LabDisplayPreferences
import dev.idoodler.labimporter.core.model.LabReport
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Value shortcuts: names only unless opted in, and a LOINC code — never a reading — in the intent. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class LauncherShortcutsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val metrics = Dashboard.sorted(
        Dashboard.metrics(
            listOf(
                LabReport(
                    "r", LocalDate(2026, 5, 6), "", "",
                    listOf(
                        LabReport.Entry("a", "2160-0", "", "0.82", 0.82, "mg/dl"),
                        LabReport.Entry("b", "4548-4", "", "6.5", 6.5, "%"),
                    ),
                ),
            ),
        ),
        LabDisplayPreferences(pinnedCodes = listOf("4548-4")),
    )

    @Test
    fun eachValueGetsAShortcutWithItsNameOnly() {
        LauncherShortcuts.update(context, metrics, showLatestValue = false)
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        // Dashboard order: the pinned value ranks first.
        assertEquals(listOf("metric-4548-4", "metric-2160-0"), shortcuts.sortedBy { it.rank }.map { it.id })
        val creatinine = shortcuts.single { it.id == "metric-2160-0" }
        assertEquals(metrics.single { it.code == "2160-0" }.name, creatinine.longLabel.toString())
        assertFalse("0.82" in creatinine.longLabel.toString())
        assertEquals(LauncherShortcuts.ACTION_OPEN_METRIC, creatinine.intent.action)
        assertEquals("2160-0", creatinine.intent.getStringExtra(LauncherShortcuts.EXTRA_CODE))
        assertEquals(setOf(LauncherShortcuts.EXTRA_CODE), creatinine.intent.extras?.keySet())
    }

    @Test
    fun theLatestReadingAppearsOnlyWhenOptedIn() {
        LauncherShortcuts.update(context, metrics, showLatestValue = true)
        val creatinine = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == "metric-2160-0" }
        assertTrue(creatinine.longLabel.toString().endsWith("0.82 mg/dl"))
        // Switching it off again removes the reading.
        LauncherShortcuts.update(context, metrics, showLatestValue = false)
        assertFalse("0.82" in ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == "metric-2160-0" }.longLabel.toString())
    }

    @Test
    fun noValuesNoShortcuts() {
        LauncherShortcuts.update(context, metrics, showLatestValue = false)
        LauncherShortcuts.update(context, emptyList(), showLatestValue = false)
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }
}
