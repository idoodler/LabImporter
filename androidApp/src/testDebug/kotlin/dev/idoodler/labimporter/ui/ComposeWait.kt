package dev.idoodler.labimporter.ui

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waits up to 10 s for [condition], letting background work (IO dispatchers,
 * the catalog) finish between checks — `waitUntil` alone doesn't advance it
 * under Robolectric.
 */
fun ComposeTestRule.waitFor(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Timed out waiting" }
        waitForIdle()
        Thread.sleep(20)
    }
    waitForIdle()
}
