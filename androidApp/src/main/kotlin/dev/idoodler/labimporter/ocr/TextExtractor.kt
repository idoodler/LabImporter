package dev.idoodler.labimporter.ocr

import android.content.Context
import androidx.core.graphics.createBitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Turns scans, photos and PDFs into plain text on device with ML Kit Text
 * Recognition (bundled model — no download, no network).
 *
 * Limitation vs. iOS Vision: the bundled recognizer reads Latin script only,
 * so Cyrillic/Greek scans need pasted text for now.
 */
class TextExtractor(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun extract(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val pages = mutableListOf<String>()
        for (uri in uris) {
            val type = context.contentResolver.getType(uri).orEmpty()
            val isPdf = type == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)
            pages += if (isPdf) fromPdf(uri) else fromImage(uri)
        }
        pages.joinToString("\n\n").trim()
    }

    private suspend fun fromImage(uri: Uri): String =
        recognizer.process(InputImage.fromFilePath(context, uri)).await().text

    private suspend fun fromPdf(uri: Uri): String {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        val renderer = PdfRenderer(descriptor)
        val text = StringBuilder()
        try {
            for (index in 0 until minOf(renderer.pageCount, MAX_PAGES)) {
                val page = renderer.openPage(index)
                // ~200 dpi: PDF points are 1/72", so scale by 200/72.
                val scale = 200f / 72f
                val bitmap = createBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }
                try {
                    text.append(recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text).append("\n\n")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            renderer.close()
            descriptor.close()
        }
        return text.toString()
    }

    private companion object {
        const val MAX_PAGES = 10
    }
}
