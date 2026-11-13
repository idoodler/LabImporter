package dev.idoodler.labimporter

import android.os.LocaleList

/**
 * The language the app's strings are shown in, for the LOINC catalog: the first of the
 * user's languages the app ships ([BuildConfig.LANGUAGES]), as Android picks its strings,
 * and as iOS takes `Bundle.main.preferredLocalizations`. With German second after a
 * language the app doesn't ship, the strings are German, and so are the test names;
 * the device's first language alone would have named them in English. English otherwise.
 */
internal fun appLanguage(locales: LocaleList, shipped: List<String> = BuildConfig.LANGUAGES.split(',')): String {
    if (locales.isEmpty) return "en"
    val match = locales.getFirstMatch(shipped.toTypedArray()) ?: return "en"
    // getFirstMatch falls back to the first locale when none matches; the strings are English then.
    val base = match.language
    return if (shipped.any { it.substringBefore('-') == base }) match.toLanguageTag() else "en"
}
