package com.cooldog.triplens.db

import android.content.Context
import android.util.Log
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.IOException

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(TripLensDatabase.Schema, context, DB_NAME)

    /**
     * Renames the corrupt database file to `triplens.db.corrupt` so a fresh database can be
     * created on the next [createDriver] call. Any previously existing `.corrupt` file is
     * removed first to avoid accumulating stale corrupt copies.
     *
     * Called from [com.cooldog.triplens.di.AndroidModule] after [AppDatabase.integrityCheck]
     * returns false and the driver has been closed. Not declared in the `expect` class because
     * corrupt recovery is an Android-only file-system concern.
     */
    fun renameCorruptDb() {
        val dbFile      = context.getDatabasePath(DB_NAME)
        val corruptFile = context.getDatabasePath("$DB_NAME.corrupt")
        if (corruptFile.exists()) corruptFile.delete()
        val renamed = dbFile.renameTo(corruptFile)
        if (!renamed) {
            // Log before throwing so logcat captures the paths for diagnostics.
            Log.e(TAG, "Failed to rename corrupt DB '$dbFile' → '$corruptFile'")
            throw IOException("Could not rename corrupt database file '$dbFile'")
        }
        Log.i(TAG, "Corrupt DB renamed to '$corruptFile'")
    }

    private companion object {
        const val DB_NAME = "triplens.db"
        const val TAG     = "TripLens/DBFactory"
    }
}
