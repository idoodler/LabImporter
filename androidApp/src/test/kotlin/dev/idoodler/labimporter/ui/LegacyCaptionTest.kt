package dev.idoodler.labimporter.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Stands in for the platform's `com.android.internal.widget.DecorCaptionView` (recognised by name). */
class DecorCaptionView(context: Context) : FrameLayout(context)

/** The window structure Android 13 builds for a freeform window, as Waydroid's `dumpsys activity top` shows it. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class LegacyCaptionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Window(val host: FrameLayout, val caption: View, val contentRoot: LinearLayout, val content: FrameLayout)

    private fun window(host: FrameLayout = DecorCaptionView(context), contentTop: Int = 0): Window {
        val caption = View(context)
        val content = FrameLayout(context).apply { id = android.R.id.content }
        val contentRoot = LinearLayout(context).apply { addView(content) }
        host.addView(caption)
        host.addView(contentRoot)
        host.layout(0, 0, 1060, 1480)
        caption.layout(0, 0, 1060, 90)
        contentRoot.layout(0, contentTop, 1060, 1480)
        content.layout(0, 0, 1060, 1480 - contentTop)
        return Window(host, caption, contentRoot, content)
    }

    @Test
    fun aCaptionDrawnOverTheContentIsMeasured() {
        val w = window()
        assertEquals(90, LegacyCaption.uncoveredCaptionHeight(w.host, w.content, reportedCaptionInset = 0))
    }

    @Test
    fun nothingMovesWhereThePlatformReportsTheCaption() {
        val w = window()
        assertEquals(0, LegacyCaption.uncoveredCaptionHeight(w.host, w.content, reportedCaptionInset = 90))
    }

    @Test
    fun nothingMovesWhenTheContentAlreadyStartsBelowTheCaption() {
        val w = window(contentTop = 90)
        assertEquals(0, LegacyCaption.uncoveredCaptionHeight(w.host, w.content, reportedCaptionInset = 0))
    }

    @Test
    fun nothingMovesWhileTheCaptionIsHidden() {
        val w = window()
        w.caption.visibility = View.GONE
        assertEquals(0, LegacyCaption.uncoveredCaptionHeight(w.host, w.content, reportedCaptionInset = 0))
    }

    @Test
    fun fullscreenWindowsHaveNoCaptionHost() {
        // Without a caption the decor's first child is the content root itself (an action-mode bar
        // can sit above the content there — it must never be taken for a caption).
        val w = window(host = FrameLayout(context))
        assertEquals(0, LegacyCaption.uncoveredCaptionHeight(w.host, w.content, reportedCaptionInset = 0))
        assertEquals(0, LegacyCaption.uncoveredCaptionHeight(null, w.content, reportedCaptionInset = 0))
    }
}
