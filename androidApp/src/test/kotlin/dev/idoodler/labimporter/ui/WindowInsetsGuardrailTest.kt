package dev.idoodler.labimporter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * In a freeform window (Waydroid multi-window, ChromeOS, Samsung DeX, tablet
 * desktop mode) Android draws a caption bar — back, minimise, maximise,
 * close — over the top of the app and reports it as the caption bar inset,
 * not as the status bar. Screens must pad their top with `safeDrawing` or
 * `systemBars` (both include the caption bar; Material's Scaffold and top
 * bars do by default), never with the status bar alone, or buttons end up
 * under the caption's.
 */
class WindowInsetsGuardrailTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    /** Ways of padding for the status bar only, as they would be written in source. */
    private val statusBarOnly = listOf("statusBarsPadding(", "WindowInsets.statusBars", "WindowInsetsCompat.Type.statusBars()")

    @Test
    fun noScreenPadsItsTopForTheStatusBarAlone() {
        val sources = listOf("androidApp/src/main", "androidApp/src/debug", "androidApp/src/release")
            .map { File(root, it) }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("no sources found under $root", sources.size > 20)
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                statusBarOnly.firstOrNull { it in code }?.let { "${file.relativeTo(root).path}:${index + 1}: $it" }
            }
        }
        assertEquals("Use safeDrawing or systemBars so a freeform caption bar is respected", emptyList<String>(), offenders)
    }
}
