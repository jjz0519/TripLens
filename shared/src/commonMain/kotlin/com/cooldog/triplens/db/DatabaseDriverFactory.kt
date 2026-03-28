package com.cooldog.triplens.db

import app.cash.sqldelight.db.SqlDriver

// Platform-specific driver creation. Android uses AndroidSqliteDriver;
// JVM tests use JdbcSqliteDriver (in-memory).
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
