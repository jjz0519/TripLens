package com.cooldog.triplens.db

import app.cash.sqldelight.db.SqlDriver

// Wraps the SQLDelight-generated TripLensDatabase interface.
// Enables WAL mode for better concurrent read performance.
// Exposes integrityCheck() for startup validation (TDD Section 14).
//
// Named AppDatabase (not TripLensDatabase) to avoid a name collision with the
// generated interface, which SQLDelight generates in the same package.
class AppDatabase(private val driver: SqlDriver) {

    private val db = TripLensDatabase(driver)

    // SQLDelight 2.x appends "Queries" to the .sq file name to form the property name.
    // Our .sq files end in "Queries" so the generated names are *QueriesQueries.
    // These aliases restore the expected short names used by repositories and tests.
    val tripGroupQueries  get() = db.tripGroupQueriesQueries
    val sessionQueries    get() = db.sessionQueriesQueries
    val trackPointQueries get() = db.trackPointQueriesQueries
    val mediaRefQueries   get() = db.mediaRefQueriesQueries
    val noteQueries       get() = db.noteQueriesQueries

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

    // Expose Schema for test setup (delegates to the generated TripLensDatabase.Schema)
    companion object {
        val Schema get() = TripLensDatabase.Schema
    }
}
