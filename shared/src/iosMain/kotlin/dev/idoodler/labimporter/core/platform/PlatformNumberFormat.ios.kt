package dev.idoodler.labimporter.core.platform

import platform.Foundation.NSString
import platform.Foundation.stringWithFormat

/** Foundation's printf — byte-for-byte what the iOS app always wrote. */
internal actual object PlatformNumberFormat {
    actual fun formatG(value: Double, precision: Int): String =
        NSString.stringWithFormat("%.${precision}g", value)

    actual fun formatF0(value: Double): String = NSString.stringWithFormat("%.0f", value)
}
