package com.cooldog.triplens.db

import app.cash.sqldelight.db.SqlDriver
import com.cooldog.triplens.db.TripLensDatabase as GeneratedDb

// Wraps the SQLDelight-generated database.
// Enables WAL mode for better concurrent read performance.
// Exposes integrityCheck() for startup validation (TDD Section 14).
class TripLensDatabase(driver: SqlDriver) {

    private val db = GeneratedDb(driver)

    val tripGroupQueries  get() = db.tripGroupQueries
    val sessionQueries    get() = db.sessionQueries
    val trackPointQueries get() = db.trackPointQueries
    val mediaRefQueries   get() = db.mediaRefQueries
    val noteQueries       get() = db.noteQueries

    init {
        // WAL mode improves read concurrency during active GPS recording
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        // Foreign key support is off by default in SQLite
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    }

    /** Returns true if the database passes SQLite's built-in integrity check. */
    fun integrityCheck(): Boolean {
        val result = driver.executeQuery(
            null, "PRAGMA integrity_check", { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
            }, 0
        )
        return result.value == "ok"
    }

    // Expose Schema for test setup
    companion object {
        val Schema get() = GeneratedDb.Schema
    }
}
