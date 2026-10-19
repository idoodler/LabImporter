package dev.idoodler.labimporter.core.platform

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import sqlite3.SQLITE_OPEN_READONLY

@OptIn(ExperimentalForeignApi::class)
internal actual fun openReadOnlyDatabase(path: String): SQLiteConnection =
    NativeSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
