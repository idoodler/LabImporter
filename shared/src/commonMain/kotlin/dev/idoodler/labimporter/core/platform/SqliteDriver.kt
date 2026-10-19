package dev.idoodler.labimporter.core.platform

import androidx.sqlite.SQLiteConnection

/** Opens a SQLite database read-only with the platform's preferred driver. */
internal expect fun openReadOnlyDatabase(path: String): SQLiteConnection
