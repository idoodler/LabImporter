package dev.idoodler.labimporter

import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApplication::class)
class AppLanguageTest {
    private val shipped = BuildConfig.LANGUAGES.split(',')

    @Test
    fun theCatalogSpeaksTheLanguageOfTheStrings() {
        assertEquals("de-DE", appLanguage(LocaleList.forLanguageTags("de-DE"), shipped))
        // Korean isn't shipped: Android shows the German strings, and the test names follow them.
        assertEquals("de-AT", appLanguage(LocaleList.forLanguageTags("ko-KR,de-AT"), shipped))
        assertEquals("en", appLanguage(LocaleList.forLanguageTags("ko-KR"), shipped))
        assertEquals("en", appLanguage(LocaleList.getEmptyLocaleList(), shipped))
    }

    @Test
    fun everyShippedLanguageIsListed() {
        assertEquals(25, shipped.size)
        listOf("en", "de", "pt-BR", "pt-PT", "zh-Hans").forEach { assert(it in shipped) { "$it in $shipped" } }
    }
}
