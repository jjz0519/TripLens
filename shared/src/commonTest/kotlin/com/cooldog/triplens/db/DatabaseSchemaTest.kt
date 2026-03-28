package com.cooldog.triplens.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseSchemaTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setup() {
        // In-memory SQLite — no Android emulator required
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        db = AppDatabase(driver)
    }

    @Test
    fun tripGroup_insertAndQuery_roundTrip() {
        db.tripGroupQueries.insert("g1", "Wellington", 1_000L, 1_001L)
        val row = db.tripGroupQueries.getById("g1").executeAsOneOrNull()
        assertNotNull(row)
        assertEquals("Wellington", row.name)
        assertEquals(1_000L, row.created_at)
    }

    @Test
    fun session_insertAndQuery_nullEndTime() {
        db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
        db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
        val row = db.sessionQueries.getById("s1").executeAsOneOrNull()
        assertNotNull(row)
        assertNull(row.end_time)
        assertEquals("recording", row.status)
    }

    @Test
    fun session_invalidStatus_throwsException() {
        db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
        // SQLite CHECK constraint on status column
        assertFailsWith<Exception> {
            db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "invalid_status")
        }
    }

    @Test
    fun trackPoint_insertAndQuery_nullableAltitude() {
        db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
        db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
        db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking", 0L)
        val points = db.trackPointQueries.getBySessionId("s1").executeAsList()
        assertEquals(1, points.size)
        assertNull(points[0].altitude)
        assertEquals("walking", points[0].transport_mode)
    }

    @Test
    fun trackPoint_invalidTransportMode_throwsException() {
        db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
        db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
        assertFailsWith<Exception> {
            db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "WALKING", 0L)
        }
    }

    @Test
    fun session_delete_cascadesToTrackPoints() {
        db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
        db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
        db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking", 0L)
        db.noteQueries.insert("n1", "s1", "text", "Hello", null, null, 3_500L, -41.28, 174.77)

        db.sessionQueries.delete("s1")

        assertEquals(0, db.trackPointQueries.getBySessionId("s1").executeAsList().size)
        assertEquals(0, db.noteQueries.getBySessionId("s1").executeAsList().size)
    }

    @Test
    fun mediaReference_insertAndQuery_allNullableFields() {
        db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
        db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
        db.mediaRefQueries.insert(
            "m1", "s1", "photo", "phone_gallery",
            "content://media/1", "IMG.jpg", 3_000L, 0,
            -41.29, 174.78, null, null, "exif", null, null
        )
        val row = db.mediaRefQueries.getBySessionId("s1").executeAsList().first()
        assertEquals("m1", row.id)
        assertNull(row.inferred_lat)
    }

    @Test
    fun integrityCheck_freshDatabase_returnsOk() {
        val result = db.integrityCheck()
        assertTrue(result)
    }
}
