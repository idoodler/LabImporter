package dev.idoodler.labimporter.ui

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 13 and older draw a freeform window's caption — back, minimise,
 * maximise, close — inside the app's own window (`DecorCaptionView`), over the
 * content (`Window.isOverlayWithDecorCaptionEnabled` defaults to true there).
 * The platform reports it as the caption-bar inset, which the UI pads for —
 * but on Waydroid (LineageOS 20) it is 0 at first and only arrives a layout
 * pass later, and without this the buttons stayed under the caption's. While
 * a caption visibly covers the content and no caption inset was reported, the
 * content moves below the caption; once the inset arrives, the padding goes
 * and the UI's own (safeDrawing) padding takes over — never both.
 * Nothing changes on Android 14+ (the caption is drawn by the system there and
 * reported), in fullscreen, or wherever the inset arrives.
 */
object LegacyCaption {

    /** Keeps [activity]'s content clear of a legacy caption, following it as the window changes. */
    fun keepContentBelow(activity: Activity) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val reported = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.captionBar())?.top ?: 0
            // The caption host wraps the content root once the window turns freeform.
            val host = (0 until decor.childCount).map(decor::getChildAt).firstOrNull { it.javaClass.name.endsWith(CAPTION_HOST) } as? ViewGroup
            val top = uncoveredCaptionHeight(host, content, reported)
            if (content.paddingTop != top) {
                Log.i(TAG, "Legacy caption covers the content by $top px (reported caption inset: $reported px)")
                content.setPadding(content.paddingLeft, top, content.paddingRight, content.paddingBottom)
            }
        }
    }

    /**
     * How far a legacy caption in [captionHost] covers [content] without the
     * platform having reported it as an inset ([reportedCaptionInset]); 0 when
     * there's no such caption, it's hidden, or the content already starts below it.
     */
    fun uncoveredCaptionHeight(captionHost: ViewGroup?, content: View, reportedCaptionInset: Int): Int {
        if (captionHost == null || reportedCaptionInset > 0) return 0
        // The platform's own class (internal, so recognised by name only — no hidden API is called).
        if (!captionHost.javaClass.name.endsWith(CAPTION_HOST)) return 0
        val caption = (0 until captionHost.childCount).map(captionHost::getChildAt).firstOrNull { !it.contains(content) } ?: return 0
        if (caption.visibility != View.VISIBLE || caption.height == 0) return 0
        val contentRoot = (0 until captionHost.childCount).map(captionHost::getChildAt).firstOrNull { it.contains(content) } ?: return 0
        // Laid out from the top under the caption (overlay) rather than below it.
        return (caption.bottom - contentRoot.top).coerceAtLeast(0)
    }

    private const val TAG = "LegacyCaption"

    /** `com.android.internal.widget.DecorCaptionView`, the platform's caption host before Android 14. */
    private const val CAPTION_HOST = "DecorCaptionView"

    private fun View.contains(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === this) return true
            current = current.parent as? View
        }
        return false
    }
}
