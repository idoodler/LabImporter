package dev.idoodler.labimporter.core.platform

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY

internal actual fun openReadOnlyDatabase(path: String): SQLiteConnection =
    BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
