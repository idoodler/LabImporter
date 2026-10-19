package dev.idoodler.labimporter.core.platform

/**
 * A minimal mutual-exclusion lock. The LOINC catalog keeps a single SQLite
 * connection whose prepared statements must never be stepped concurrently, yet
 * its lookups stay synchronous (they're microseconds) so they can be called
 * from UI code and background work alike — mirroring the `NSLock` the Swift
 * `LoincDirectory` used.
 */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
