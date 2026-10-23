package dev.idoodler.labimporter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.idoodler.labimporter.ai.gemma.GemmaModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI

/**
 * The app holds INTERNET for one thing: downloading the pinned Gemma model
 * the user asked for. This fails the build when anything else could use the
 * network — app or shared code reaching for networking APIs, a different
 * download address, or a library's telemetry backend coming back.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class NetworkGuardrailTest {

    /** Every way Kotlin code could open a connection, as it would be written in source. */
    private val networkApis = listOf(
        "java.net.", "javax.net.", "URLConnection", "openConnection(", "openStream(", "Socket", "okhttp", "io.ktor", "retrofit",
        "com.android.volley", "Cronet", "android.net.http", "WebView", "HttpClient", "DownloadManager",
    )

    /** The only file allowed to: the system download of [GemmaModel.URL]. */
    private val downloader = "androidApp/src/main/kotlin/dev/idoodler/labimporter/ai/gemma/ModelDownloads.kt"

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun onlyTheModelDownloaderUsesTheNetwork() {
        val sources = listOf("androidApp/src/main", "androidApp/src/debug", "androidApp/src/release", "shared/src")
            .map { File(root, it) }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "Test" !in it.path }.toList() }
        assertTrue("no sources found under $root", sources.size > 50)
        val offenders = sources.flatMap { file ->
            val path = file.relativeTo(root).path
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null // KDoc
                networkApis.firstOrNull { it in code }?.takeIf { path != downloader }?.let { "$path:${index + 1}: $it" }
            }
        }
        assertEquals("Network access outside $downloader", emptyList<String>(), offenders)
    }

    @Test
    fun theDownloaderFetchesOnlyThePinnedFile() {
        val url = URI(GemmaModel.URL)
        assertEquals("https", url.scheme)
        assertEquals("huggingface.co", url.host)
        assertEquals("/${GemmaModel.REPOSITORY}/resolve/${GemmaModel.REVISION}/${GemmaModel.FILE_NAME}", url.path)
        assertTrue("pinned to a commit, not a branch", GemmaModel.REVISION.matches(Regex("[0-9a-f]{40}")))
        assertTrue(GemmaModel.SHA256.matches(Regex("[0-9a-f]{64}")))
        // The download manager is only ever given that one address.
        val downloads = File(root, downloader).readText()
        assertTrue(downloads.contains("DownloadManager.Request(GemmaModel.URL.toUri())"))
        assertEquals(1, Regex("DownloadManager\\.Request\\(").findAll(downloads).count())
    }

    @Test
    fun theManifestDeclaresItAndNoTelemetryBackendIsLeft() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(Manifest.permission.INTERNET in info.requestedPermissions.orEmpty())
        // ML Kit's Clearcut logging would send usage statistics; its backend is removed in the manifest.
        val discovery = ComponentName(context, "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery")
        val backends = runCatching { context.packageManager.getServiceInfo(discovery, PackageManager.GET_META_DATA).metaData }
            .getOrNull()?.keySet().orEmpty().filter { it.startsWith("backend:") }
        assertEquals(emptyList<String>(), backends)
    }
}
