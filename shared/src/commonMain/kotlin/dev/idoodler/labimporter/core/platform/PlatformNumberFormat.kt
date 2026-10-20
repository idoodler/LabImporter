package dev.idoodler.labimporter.core.platform

/** C `printf` number formats, correctly rounded (half-even on the exact binary value). */
internal expect object PlatformNumberFormat {
    /** `%.<precision>g`. */
    fun formatG(value: Double, precision: Int): String

    /** `%.0f`. */
    fun formatF0(value: Double): String
}
