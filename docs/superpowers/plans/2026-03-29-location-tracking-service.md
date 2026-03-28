# LocationTrackingService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Android ForegroundService that owns GPS recording, including auto-pause after 3h stationary, START_STICKY recovery via SharedPreferences, and a 10-point write buffer — plus the `LocationProvider` expect/actual abstraction for future iOS.

**Architecture:** Schema change in `shared` (add `is_auto_paused`), platform abstractions in `shared/commonMain` + `shared/androidMain`, service in `composeApp/androidMain`. No Koin yet (Task 9) — dependencies are instantiated directly in `onCreate`. Tests: JVM unit tests for `AccuracyProfile`, Android instrumented tests for the service via a `LocalBinder`.

**Tech Stack:** Kotlin 2.1, KMP, FusedLocationProviderClient (Play Services Location 21.x), SQLDelight 2.0.x, Kotlinx Coroutines 1.9.x, AndroidX Test (ServiceTestRule), JUnit 4

---

## File Map

| File | Action |
|---|---|
| `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/schema.sq` | Modify — add `is_auto_paused` column |
| `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/TrackPointQueries.sq` | Modify — add param to insert, update SELECT |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/model/TrackPoint.kt` | Modify — add `isAutoPaused: Boolean` |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointInsert.kt` | Modify — add `isAutoPaused: Boolean = false` |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointRepository.kt` | Modify — pass + map `is_auto_paused` |
| `shared/src/commonTest/kotlin/com/cooldog/triplens/db/DatabaseSchemaTest.kt` | Modify — update raw `insert()` calls to 9 params |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationData.kt` | Create |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationPriority.kt` | Create |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/AccuracyProfile.kt` | Create |
| `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt` | Create (expect) |
| `shared/src/androidMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt` | Create (actual) |
| `shared/build.gradle.kts` | Modify — add `play-services-location` to `androidMain` |
| `gradle/libs.versions.toml` | Modify — add test deps |
| `shared/src/commonTest/kotlin/com/cooldog/triplens/platform/AccuracyProfileTest.kt` | Create |
| `composeApp/build.gradle.kts` | Modify — add instrumented test deps + `testInstrumentationRunner` |
| `composeApp/src/androidMain/kotlin/com/cooldog/triplens/service/LocationTrackingService.kt` | Create |
| `composeApp/src/androidMain/AndroidManifest.xml` | Modify — add `<service>` declaration |
| `composeApp/src/androidTest/kotlin/com/cooldog/triplens/service/LocationTrackingServiceTest.kt` | Create |

---

## Task 1: Add `is_auto_paused` to Schema and Update All Dependent Code

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/schema.sq`
- Modify: `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/TrackPointQueries.sq`
- Modify: `shared/src/commonMain/kotlin/com/cooldog/triplens/model/TrackPoint.kt`
- Modify: `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointInsert.kt`
- Modify: `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/cooldog/triplens/db/DatabaseSchemaTest.kt`

- [ ] **Step 1: Add two new tests to `DatabaseSchemaTest` for `is_auto_paused`**

  Add these two tests at the end of `shared/src/commonTest/kotlin/com/cooldog/triplens/db/DatabaseSchemaTest.kt` (before the closing `}`):

  ```kotlin
  @Test
  fun trackPoint_isAutoPaused_defaultsFalse() {
      db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
      db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
      // 9 params: session_id, timestamp, lat, lng, alt, acc, speed, mode, is_auto_paused
      db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking", 0L)
      val point = db.trackPointQueries.getBySessionId("s1").executeAsList().first()
      assertEquals(0L, point.is_auto_paused)
  }

  @Test
  fun trackPoint_isAutoPaused_canBeSetTrue() {
      db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
      db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
      db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking", 1L)
      val point = db.trackPointQueries.getBySessionId("s1").executeAsList().first()
      assertEquals(1L, point.is_auto_paused)
  }
  ```

- [ ] **Step 2: Run existing tests — confirm they compile but new tests fail**

  ```
  ./gradlew :shared:testDebugUnitTest
  ```

  Expected: existing tests pass, 2 new tests fail with `IllegalArgumentException` or similar because the column doesn't exist yet.

- [ ] **Step 3: Update `schema.sq` — add `is_auto_paused` column to `track_point`**

  In `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/schema.sq`, replace the `track_point` table definition:

  ```sql
  CREATE TABLE track_point (
      id             INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id     TEXT    NOT NULL REFERENCES session(id) ON DELETE CASCADE,
      timestamp      INTEGER NOT NULL,
      latitude       REAL    NOT NULL,
      longitude      REAL    NOT NULL,
      altitude       REAL,
      accuracy       REAL    NOT NULL,
      speed          REAL,
      transport_mode TEXT    NOT NULL DEFAULT 'stationary'
          CHECK (transport_mode IN ('stationary','walking','cycling','driving','fast_transit')),
      -- 0 = active tracking, 1 = recorded during auto-pause (hidden from timeline, excluded from distance)
      is_auto_paused INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX idx_tp_session_time ON track_point(session_id, timestamp);
  ```

- [ ] **Step 4: Update `TrackPointQueries.sq` — add `is_auto_paused` to insert**

  Replace the entire contents of `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/TrackPointQueries.sq`:

  ```sql
  insert:
  INSERT INTO track_point(session_id, timestamp, latitude, longitude, altitude, accuracy, speed, transport_mode, is_auto_paused)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

  getBySessionId:
  SELECT * FROM track_point WHERE session_id = ? ORDER BY timestamp ASC;

  getBySessionIdAndTimeRange:
  SELECT * FROM track_point
  WHERE session_id = ? AND timestamp >= ? AND timestamp <= ?
  ORDER BY timestamp ASC;

  countBySession:
  SELECT COUNT(*) FROM track_point WHERE session_id = ?;
  ```

- [ ] **Step 5: Update `TrackPoint.kt` — add `isAutoPaused` field**

  Replace the entire file at `shared/src/commonMain/kotlin/com/cooldog/triplens/model/TrackPoint.kt`:

  ```kotlin
  package com.cooldog.triplens.model

  import kotlinx.serialization.Serializable

  @Serializable
  data class TrackPoint(
      val id: Long,
      val sessionId: String,
      val timestamp: Long,         // epoch millis UTC
      val latitude: Double,
      val longitude: Double,
      val altitude: Double?,       // null if GPS has no altitude fix
      val accuracy: Float,         // metres, horizontal accuracy radius
      val speed: Float?,           // m/s, null if not provided by GPS
      val transportMode: TransportMode,
      val isAutoPaused: Boolean = false  // true during auto-pause; excluded from timeline and distance
  )
  ```

- [ ] **Step 6: Update `TrackPointInsert.kt` — add `isAutoPaused` field**

  Replace the entire file at `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointInsert.kt`:

  ```kotlin
  package com.cooldog.triplens.repository

  import com.cooldog.triplens.model.TransportMode

  // Lightweight insert payload — avoids using the generated DB type in business logic.
  data class TrackPointInsert(
      val sessionId: String,
      val timestamp: Long,
      val latitude: Double,
      val longitude: Double,
      val altitude: Double?,
      val accuracy: Float,
      val speed: Float?,
      val transportMode: TransportMode,
      val isAutoPaused: Boolean = false
  )
  ```

- [ ] **Step 7: Update `TrackPointRepository.kt` — pass and map `is_auto_paused`**

  Replace the entire file at `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointRepository.kt`:

  ```kotlin
  package com.cooldog.triplens.repository

  import com.cooldog.triplens.db.AppDatabase
  import com.cooldog.triplens.model.TrackPoint
  import com.cooldog.triplens.model.TransportMode

  class TrackPointRepository(private val db: AppDatabase) {

      fun insert(point: TrackPointInsert) {
          db.trackPointQueries.insert(
              point.sessionId, point.timestamp,
              point.latitude, point.longitude, point.altitude,
              point.accuracy.toDouble(), point.speed?.toDouble(),
              point.transportMode.name.lowercase(),
              if (point.isAutoPaused) 1L else 0L
          )
      }

      /** Inserts all points in a single DB transaction (max 10 for buffer flush). */
      fun insertBatch(points: List<TrackPointInsert>) {
          db.trackPointQueries.transaction {
              points.forEach { insert(it) }
          }
      }

      fun getBySession(sessionId: String): List<TrackPoint> =
          db.trackPointQueries.getBySessionId(sessionId).executeAsList().map { it.toModel() }

      fun getInRange(sessionId: String, fromMs: Long, toMs: Long): List<TrackPoint> =
          db.trackPointQueries.getBySessionIdAndTimeRange(sessionId, fromMs, toMs)
              .executeAsList().map { it.toModel() }

      private fun com.cooldog.triplens.db.Track_point.toModel() = TrackPoint(
          id = id,
          sessionId = session_id,
          timestamp = timestamp,
          latitude = latitude,
          longitude = longitude,
          altitude = altitude,
          accuracy = accuracy.toFloat(),
          speed = speed?.toFloat(),
          transportMode = TransportMode.valueOf(transport_mode.uppercase()),
          isAutoPaused = is_auto_paused != 0L
      )
  }
  ```

- [ ] **Step 8: Fix `DatabaseSchemaTest` — update existing raw insert calls to include `is_auto_paused`**

  In `shared/src/commonTest/kotlin/com/cooldog/triplens/db/DatabaseSchemaTest.kt`, update the three `trackPointQueries.insert(...)` calls that now need a 9th argument. Find and update each:

  In `trackPoint_insertAndQuery_nullableAltitude` (line 58):
  ```kotlin
  db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking", 0L)
  ```

  In `trackPoint_invalidTransportMode_throwsException` (line 70):
  ```kotlin
  db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "WALKING", 0L)
  ```

  In `session_delete_cascadesToTrackPoints` (line 78):
  ```kotlin
  db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking", 0L)
  ```

- [ ] **Step 9: Run all shared tests — confirm everything passes**

  ```
  ./gradlew :shared:testDebugUnitTest
  ```

  Expected output: `BUILD SUCCESSFUL` — all tests pass including the 2 new `is_auto_paused` tests.

- [ ] **Step 10: Commit**

  ```bash
  git add shared/src/commonMain/sqldelight/com/cooldog/triplens/db/schema.sq
  git add shared/src/commonMain/sqldelight/com/cooldog/triplens/db/TrackPointQueries.sq
  git add shared/src/commonMain/kotlin/com/cooldog/triplens/model/TrackPoint.kt
  git add shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointInsert.kt
  git add shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TrackPointRepository.kt
  git add shared/src/commonTest/kotlin/com/cooldog/triplens/db/DatabaseSchemaTest.kt
  git commit -m "feat(data): add is_auto_paused to track_point schema and domain model"
  ```

---

## Task 2: Add Platform Types (`LocationData`, `LocationPriority`, `AccuracyProfile`) with Unit Tests

**Files:**
- Create: `shared/src/commonTest/kotlin/com/cooldog/triplens/platform/AccuracyProfileTest.kt`
- Create: `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationPriority.kt`
- Create: `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationData.kt`
- Create: `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/AccuracyProfile.kt`

- [ ] **Step 1: Write the failing `AccuracyProfileTest`**

  Create `shared/src/commonTest/kotlin/com/cooldog/triplens/platform/AccuracyProfileTest.kt`:

  ```kotlin
  package com.cooldog.triplens.platform

  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertNotEquals

  class AccuracyProfileTest {

      @Test
      fun standard_hasCorrectValues() {
          assertEquals(8_000L, AccuracyProfile.STANDARD.movingIntervalMs)
          assertEquals(60_000L, AccuracyProfile.STANDARD.stationaryIntervalMs)
          assertEquals(LocationPriority.HIGH_ACCURACY, AccuracyProfile.STANDARD.priority)
      }

      @Test
      fun high_hasCorrectValues() {
          assertEquals(4_000L, AccuracyProfile.HIGH.movingIntervalMs)
          assertEquals(4_000L, AccuracyProfile.HIGH.stationaryIntervalMs)
          assertEquals(LocationPriority.HIGH_ACCURACY, AccuracyProfile.HIGH.priority)
      }

      @Test
      fun batterySaver_hasCorrectValues() {
          assertEquals(45_000L, AccuracyProfile.BATTERY_SAVER.movingIntervalMs)
          assertEquals(60_000L, AccuracyProfile.BATTERY_SAVER.stationaryIntervalMs)
          assertEquals(LocationPriority.BALANCED, AccuracyProfile.BATTERY_SAVER.priority)
      }

      @Test
      fun batterySaver_priorityIsNotHighAccuracy() {
          // BATTERY_SAVER must use a lower-accuracy priority than STANDARD and HIGH
          assertNotEquals(LocationPriority.HIGH_ACCURACY, AccuracyProfile.BATTERY_SAVER.priority)
      }
  }
  ```

- [ ] **Step 2: Run test — confirm it fails to compile**

  ```
  ./gradlew :shared:testDebugUnitTest
  ```

  Expected: compilation error — `AccuracyProfile`, `LocationPriority` not found.

- [ ] **Step 3: Create `LocationPriority.kt`**

  Create `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationPriority.kt`:

  ```kotlin
  package com.cooldog.triplens.platform

  // Platform-agnostic location accuracy priority.
  // Android actual maps these to com.google.android.gms.location.Priority constants.
  // iOS actual will map to CLLocationAccuracy values.
  enum class LocationPriority {
      HIGH_ACCURACY,  // GPS-level precision
      BALANCED,       // Cell/WiFi assisted, lower power
      LOW_POWER       // Coarse only (not used by TripLens, reserved for future)
  }
  ```

- [ ] **Step 4: Create `LocationData.kt`**

  Create `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationData.kt`:

  ```kotlin
  package com.cooldog.triplens.platform

  // Platform-agnostic location fix. Populated by LocationProvider actual implementations.
  // speedMs is null when the platform has not computed a speed for this fix (common on first fix).
  data class LocationData(
      val timestampMs: Long,       // epoch millis UTC (from GPS hardware clock)
      val latitude: Double,
      val longitude: Double,
      val altitude: Double?,       // metres above WGS84 ellipsoid; null if unavailable
      val accuracyMeters: Float,   // horizontal accuracy radius in metres (1-sigma)
      val speedMs: Float?          // m/s; null if platform did not compute speed for this fix
  )
  ```

- [ ] **Step 5: Create `AccuracyProfile.kt`**

  Create `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/AccuracyProfile.kt`:

  ```kotlin
  package com.cooldog.triplens.platform

  // GPS accuracy profiles from TDD §4.2.
  // movingIntervalMs / stationaryIntervalMs: passed to LocationProvider.startUpdates().
  // The service switches between the two based on whether speed > 1 km/h.
  enum class AccuracyProfile(
      val movingIntervalMs: Long,
      val stationaryIntervalMs: Long,
      val priority: LocationPriority
  ) {
      // ~8s when moving, 60s when stationary. Default.
      STANDARD(
          movingIntervalMs = 8_000L,
          stationaryIntervalMs = 60_000L,
          priority = LocationPriority.HIGH_ACCURACY
      ),
      // ~4s always (for hiking / high-detail recording). Higher battery use.
      HIGH(
          movingIntervalMs = 4_000L,
          stationaryIntervalMs = 4_000L,
          priority = LocationPriority.HIGH_ACCURACY
      ),
      // ~45s moving, 60s stationary. Minimal GPS use for low-battery situations.
      BATTERY_SAVER(
          movingIntervalMs = 45_000L,
          stationaryIntervalMs = 60_000L,
          priority = LocationPriority.BALANCED
      )
  }
  ```

- [ ] **Step 6: Run tests — confirm all 4 AccuracyProfile tests pass**

  ```
  ./gradlew :shared:testDebugUnitTest
  ```

  Expected: `BUILD SUCCESSFUL` — all tests including the 4 new ones pass.

- [ ] **Step 7: Commit**

  ```bash
  git add shared/src/commonMain/kotlin/com/cooldog/triplens/platform/
  git add shared/src/commonTest/kotlin/com/cooldog/triplens/platform/AccuracyProfileTest.kt
  git commit -m "feat(platform): add LocationData, LocationPriority, AccuracyProfile with unit tests"
  ```

---

## Task 3: `LocationProvider` expect/actual + Build Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt`
- Create: `shared/src/androidMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt`

- [ ] **Step 1: Add `play-services-location` to `shared/androidMain` dependencies**

  `play-services-location` is already declared in `libs.versions.toml` (used by `composeApp`). Just add it to `shared/build.gradle.kts` in the `androidMain.dependencies` block:

  ```kotlin
  androidMain.dependencies {
      implementation(libs.sqldelight.android.driver)
      implementation(libs.kotlinx.coroutines.android)
      implementation(libs.play.services.location)  // FusedLocationProviderClient for LocationProvider actual
  }
  ```

- [ ] **Step 2: Create `LocationProvider.kt` in `commonMain` (expect)**

  Create `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt`:

  ```kotlin
  package com.cooldog.triplens.platform

  // Platform-agnostic location provider.
  // Android actual: wraps FusedLocationProviderClient (Google Play Services).
  // iOS actual (future): will wrap CLLocationManager.
  //
  // Constructor parameters are platform-specific (added only in actual declarations),
  // following the same pattern as DatabaseDriverFactory.
  expect class LocationProvider {
      // Start receiving location updates at the given interval and priority.
      // onLocation is called on the platform's main/location thread.
      // Call startUpdates again to change interval (e.g., on auto-pause transition).
      fun startUpdates(intervalMs: Long, priority: LocationPriority, onLocation: (LocationData) -> Unit)

      // Stop all location updates and release the underlying platform callback.
      fun stopUpdates()
  }
  ```

- [ ] **Step 3: Create `LocationProvider.kt` in `androidMain` (actual)**

  Create `shared/src/androidMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt`:

  ```kotlin
  package com.cooldog.triplens.platform

  import android.content.Context
  import android.os.Looper
  import com.google.android.gms.location.FusedLocationProviderClient
  import com.google.android.gms.location.LocationCallback
  import com.google.android.gms.location.LocationRequest
  import com.google.android.gms.location.LocationResult
  import com.google.android.gms.location.LocationServices
  import com.google.android.gms.location.Priority

  // Android actual for LocationProvider.
  // context: used to obtain FusedLocationProviderClient from LocationServices.
  actual class LocationProvider(private val context: Context) {

      private val fusedClient: FusedLocationProviderClient =
          LocationServices.getFusedLocationProviderClient(context)

      // Retained so stopUpdates() can deregister the exact same callback instance.
      private var activeCallback: LocationCallback? = null

      actual fun startUpdates(
          intervalMs: Long,
          priority: LocationPriority,
          onLocation: (LocationData) -> Unit
      ) {
          // Remove any previous callback before registering new one (handles interval changes).
          stopUpdates()

          val androidPriority = when (priority) {
              LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
              LocationPriority.BALANCED     -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
              LocationPriority.LOW_POWER    -> Priority.PRIORITY_LOW_POWER
          }

          val request = LocationRequest.Builder(androidPriority, intervalMs)
              .setMinUpdateIntervalMillis(intervalMs / 2)
              .build()

          val callback = object : LocationCallback() {
              override fun onLocationResult(result: LocationResult) {
                  result.lastLocation?.let { loc ->
                      onLocation(LocationData(
                          timestampMs    = loc.time,
                          latitude       = loc.latitude,
                          longitude      = loc.longitude,
                          altitude       = if (loc.hasAltitude()) loc.altitude else null,
                          accuracyMeters = loc.accuracy,
                          speedMs        = if (loc.hasSpeed()) loc.speed else null
                      ))
                  }
              }
          }

          activeCallback = callback
          @Suppress("MissingPermission")  // Caller (LocationTrackingService) holds the permission
          fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
      }

      actual fun stopUpdates() {
          activeCallback?.let { fusedClient.removeLocationUpdates(it) }
          activeCallback = null
      }
  }
  ```

- [ ] **Step 4: Sync Gradle and verify compilation**

  In Android Studio: **File → Sync Project with Gradle Files**

  Then verify the shared module compiles:
  ```
  ./gradlew :shared:assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL` — no unresolved references.

- [ ] **Step 5: Commit**

  ```bash
  git add shared/build.gradle.kts
  git add shared/src/commonMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt
  git add shared/src/androidMain/kotlin/com/cooldog/triplens/platform/LocationProvider.kt
  git commit -m "feat(platform): add LocationProvider expect/actual wrapping FusedLocationProviderClient"
  ```

---

## Task 4: `LocationTrackingService` + Android Instrumented Tests

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`
- Create: `composeApp/src/androidTest/kotlin/com/cooldog/triplens/service/LocationTrackingServiceTest.kt`
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/service/LocationTrackingService.kt`
- Modify: `composeApp/src/androidMain/AndroidManifest.xml`

- [ ] **Step 1: Add instrumented test dependencies to `libs.versions.toml`**

  Add two new entries to the `[libraries]` section of `gradle/libs.versions.toml`:

  ```toml
  androidx-test-rules  = { module = "androidx.test:rules",  version = "1.6.1" }
  androidx-test-runner = { module = "androidx.test:runner", version = "1.6.2" }
  ```

- [ ] **Step 2: Update `composeApp/build.gradle.kts` — add test runner and test dependencies**

  Add `testInstrumentationRunner` to the `defaultConfig` block, and add the three test dependencies in the `dependencies {}` block at the end of `composeApp/build.gradle.kts`:

  In the `android { defaultConfig { ... } }` block, add:
  ```kotlin
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ```

  In the top-level `dependencies { ... }` block (after the `debugImplementation` line), add:
  ```kotlin
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.runner)
  ```

  The full final `composeApp/build.gradle.kts`:

  ```kotlin
  import org.jetbrains.kotlin.gradle.dsl.JvmTarget

  plugins {
      alias(libs.plugins.android.application)
      alias(libs.plugins.kotlin.multiplatform)
      alias(libs.plugins.compose.multiplatform)
      alias(libs.plugins.compose.compiler)
  }

  kotlin {
      androidTarget {
          compilerOptions {
              jvmTarget.set(JvmTarget.JVM_11)
          }
      }
      sourceSets {
          androidMain.dependencies {
              implementation(project(":shared"))
              implementation(libs.androidx.core.ktx)
              implementation(libs.androidx.lifecycle.viewmodel)
              implementation(libs.androidx.activity.compose)
              implementation(libs.navigation.compose)
              implementation(libs.play.services.location)
              implementation(libs.koin.android)
              implementation(libs.coil.compose)
              implementation(libs.kotlinx.coroutines.android)
          }
          commonMain.dependencies {
              implementation(compose.runtime)
              implementation(compose.foundation)
              implementation(compose.material3)
              implementation(compose.ui)
              implementation(compose.components.resources)
              implementation(compose.components.uiToolingPreview)
              implementation(libs.kotlinx.coroutines.core)
          }
          commonTest.dependencies {
              implementation(kotlin("test"))
          }
      }
  }

  // compose.uiTooling must be debugImplementation via the Android dependency block,
  // not a KMP source set — KMP has no androidDebug source set concept.
  dependencies {
      debugImplementation(compose.uiTooling)
      androidTestImplementation(libs.androidx.test.junit)
      androidTestImplementation(libs.androidx.test.rules)
      androidTestImplementation(libs.androidx.test.runner)
  }

  android {
      namespace = "com.cooldog.triplens"
      compileSdk = 36
      defaultConfig {
          applicationId = "com.cooldog.triplens"
          minSdk = 26
          targetSdk = 36
          versionCode = 1
          versionName = "1.0"
          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
      packaging {
          resources {
              excludes += "/META-INF/{AL2.0,LGPL2.1}"
          }
      }
      buildTypes {
          getByName("release") {
              isMinifyEnabled = false
          }
      }
      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_11
          targetCompatibility = JavaVersion.VERSION_11
      }
  }
  ```

- [ ] **Step 3: Write the failing `LocationTrackingServiceTest`**

  Create `composeApp/src/androidTest/kotlin/com/cooldog/triplens/service/LocationTrackingServiceTest.kt`:

  ```kotlin
  package com.cooldog.triplens.service

  import android.content.Context
  import android.content.Intent
  import androidx.test.core.app.ApplicationProvider
  import androidx.test.ext.junit.runners.AndroidJUnit4
  import androidx.test.rule.ServiceTestRule
  import com.cooldog.triplens.platform.LocationData
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Rule
  import org.junit.Test
  import org.junit.runner.RunWith

  @RunWith(AndroidJUnit4::class)
  class LocationTrackingServiceTest {

      @get:Rule
      val serviceRule = ServiceTestRule()

      private val context: Context = ApplicationProvider.getApplicationContext()

      private val prefs by lazy {
          context.getSharedPreferences("triplens_service", Context.MODE_PRIVATE)
      }

      @Before
      fun clearPrefs() {
          prefs.edit().clear().commit()
      }

      @After
      fun stopAnyRunningService() {
          try {
              val stopIntent = Intent(context, LocationTrackingService::class.java).apply {
                  action = LocationTrackingService.ACTION_STOP
              }
              context.startService(stopIntent)
          } catch (_: Exception) { }
      }

      // Helper: build a minimal LocationData fix
      private fun fix(speedMs: Float = 0f, timestampMs: Long = System.currentTimeMillis()) =
          LocationData(
              timestampMs    = timestampMs,
              latitude       = -41.28,
              longitude      = 174.77,
              altitude       = null,
              accuracyMeters = 8.0f,
              speedMs        = speedMs
          )

      // Helper: bind to the service and get the instance
      private fun bindService(): LocationTrackingService {
          val intent = Intent(context, LocationTrackingService::class.java)
          val binder = serviceRule.bindService(intent)
          return (binder as LocationTrackingService.LocalBinder).getService()
      }

      @Test
      fun lifecycle_startService_persistsSessionToPrefs() {
          val intent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_START
              putExtra(LocationTrackingService.EXTRA_SESSION_ID, "session-abc")
              putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, "STANDARD")
          }
          serviceRule.startService(intent)

          assertEquals("session-abc", prefs.getString("active_session_id", null))
          assertEquals("STANDARD", prefs.getString("accuracy_profile", null))
      }

      @Test
      fun lifecycle_stopService_clearsPrefs() {
          // Start
          val startIntent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_START
              putExtra(LocationTrackingService.EXTRA_SESSION_ID, "session-xyz")
              putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, "STANDARD")
          }
          serviceRule.startService(startIntent)

          // Stop
          val stopIntent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_STOP
          }
          context.startService(stopIntent)
          Thread.sleep(500)  // Allow service coroutine + stop to complete

          assertNull(prefs.getString("active_session_id", null))
      }

      @Test
      fun recovery_prePopulatedPrefs_serviceReadsSessionWithoutCrashing() {
          prefs.edit()
              .putString("active_session_id", "recovered-session")
              .putString("accuracy_profile", "STANDARD")
              .commit()

          // Start service with no ACTION — simulates START_STICKY null-intent restart
          val intent = Intent(context, LocationTrackingService::class.java)
          serviceRule.startService(intent)

          // If we reach here without crash, recovery succeeded.
          // Prefs still present because the service is still running.
          assertEquals("recovered-session", prefs.getString("active_session_id", null))
      }

      @Test
      fun autoPause_stationaryFor3h_setsIsAutoPausedTrue() {
          val startIntent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_START
              putExtra(LocationTrackingService.EXTRA_SESSION_ID, "s1")
              putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, "STANDARD")
          }
          serviceRule.startService(startIntent)
          val service = bindService()

          val baseTime = 1_000_000L
          val threeHoursPlus = 3 * 60 * 60 * 1_000L + 1_000L

          // Inject first stationary fix, then one > 3h later
          service.onLocationReceived(fix(speedMs = 0f, timestampMs = baseTime))
          service.onLocationReceived(fix(speedMs = 0f, timestampMs = baseTime + threeHoursPlus))

          assertTrue("Service should be in auto-pause after 3h stationary", service.isAutoPaused)
      }

      @Test
      fun autoPause_movingFixWhilePaused_resetsIsAutoPausedFalse() {
          val startIntent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_START
              putExtra(LocationTrackingService.EXTRA_SESSION_ID, "s1")
              putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, "STANDARD")
          }
          serviceRule.startService(startIntent)
          val service = bindService()

          // Enter auto-pause
          val baseTime = 1_000_000L
          service.onLocationReceived(fix(speedMs = 0f, timestampMs = baseTime))
          service.onLocationReceived(fix(speedMs = 0f, timestampMs = baseTime + 3 * 60 * 60 * 1_000L + 1_000L))
          assertTrue(service.isAutoPaused)

          // Moving fix should exit auto-pause (5 km/h = 1.39 m/s)
          service.onLocationReceived(fix(speedMs = 1.39f, timestampMs = baseTime + 3 * 60 * 60 * 1_000L + 10_000L))

          assertFalse("Service should exit auto-pause on movement", service.isAutoPaused)
      }

      @Test
      fun bufferFlush_stopWithUnderThresholdBuffer_allPointsPersisted() {
          val startIntent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_START
              putExtra(LocationTrackingService.EXTRA_SESSION_ID, "s1")
              putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, "STANDARD")
          }
          serviceRule.startService(startIntent)
          val service = bindService()

          // Inject 5 fixes — below the 10-point flush threshold
          repeat(5) { i ->
              service.onLocationReceived(fix(speedMs = 1.5f, timestampMs = 1_000L * (i + 1)))
          }
          assertEquals(5, service.buffer.size)

          // Stop — buffer should be flushed before stopSelf()
          val stopIntent = Intent(context, LocationTrackingService::class.java).apply {
              action = LocationTrackingService.ACTION_STOP
          }
          context.startService(stopIntent)
          Thread.sleep(500)

          // After stop, buffer must be empty (points were persisted)
          assertEquals(0, service.buffer.size)
      }
  }
  ```

- [ ] **Step 4: Sync Gradle — confirm test file compiles with errors (service class missing)**

  ```
  ./gradlew :composeApp:assembleDebugAndroidTest
  ```

  Expected: compilation errors — `LocationTrackingService` not found.

- [ ] **Step 5: Create `LocationTrackingService.kt`**

  Create `composeApp/src/androidMain/kotlin/com/cooldog/triplens/service/LocationTrackingService.kt`:

  ```kotlin
  package com.cooldog.triplens.service

  import android.app.NotificationChannel
  import android.app.NotificationManager
  import android.app.Service
  import android.content.Context
  import android.content.Intent
  import android.content.SharedPreferences
  import android.os.Binder
  import android.os.IBinder
  import android.util.Log
  import androidx.core.app.NotificationCompat
  import com.cooldog.triplens.db.AppDatabase
  import com.cooldog.triplens.db.DatabaseDriverFactory
  import com.cooldog.triplens.domain.TransportClassifier
  import com.cooldog.triplens.platform.AccuracyProfile
  import com.cooldog.triplens.platform.LocationData
  import com.cooldog.triplens.platform.LocationProvider
  import com.cooldog.triplens.repository.TrackPointInsert
  import com.cooldog.triplens.repository.TrackPointRepository
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.launch

  class LocationTrackingService : Service() {

      // --- Public API surface (intent actions + extras) ---

      companion object {
          const val ACTION_START = "com.cooldog.triplens.ACTION_START"
          const val ACTION_STOP  = "com.cooldog.triplens.ACTION_STOP"
          const val EXTRA_SESSION_ID        = "session_id"
          const val EXTRA_ACCURACY_PROFILE  = "accuracy_profile"

          private const val TAG = "TripLens/LocationService"
          private const val CHANNEL_ID      = "triplens_recording"
          private const val NOTIFICATION_ID = 1001
          private const val PREFS_NAME      = "triplens_service"
          private const val PREFS_KEY_SESSION_ID       = "active_session_id"
          private const val PREFS_KEY_ACCURACY_PROFILE = "accuracy_profile"

          private const val BUFFER_SIZE             = 10
          private const val AUTO_PAUSE_THRESHOLD_MS = 3 * 60 * 60 * 1_000L  // 3 hours
          private const val AUTO_PAUSE_INTERVAL_MS  = 5 * 60 * 1_000L        // 5 minutes
      }

      // --- Dependencies (direct instantiation until Koin is wired in Task 9) ---

      private lateinit var locationProvider: LocationProvider
      private lateinit var trackPointRepo: TrackPointRepository
      private lateinit var prefs: SharedPreferences

      // Coroutine scope for DB writes. SupervisorJob prevents one failed write from
      // cancelling all pending writes.
      private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

      // --- Mutable service state ---

      internal var currentSessionId: String? = null
          private set
      private var currentProfile: AccuracyProfile = AccuracyProfile.STANDARD

      // In-memory write buffer. Flushed to DB when full (size == BUFFER_SIZE) or on stop.
      internal val buffer = ArrayDeque<TrackPointInsert>()

      // Auto-pause state (TDD §13: Battery Optimisation)
      internal var isAutoPaused = false
          private set
      private var stationaryStartMs: Long? = null  // timestamp of first stationary fix in current run

      // --- Binder (for test access and future local binding) ---

      inner class LocalBinder : Binder() {
          fun getService(): LocationTrackingService = this@LocationTrackingService
      }

      private val binder = LocalBinder()

      override fun onBind(intent: Intent?): IBinder = binder

      // --- Lifecycle ---

      override fun onCreate() {
          super.onCreate()
          locationProvider = LocationProvider(this)
          val db = AppDatabase(DatabaseDriverFactory(this).createDriver())
          trackPointRepo = TrackPointRepository(db)
          prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          createNotificationChannel()
          Log.d(TAG, "Service created")
      }

      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
          return when (intent?.action) {
              ACTION_STOP -> {
                  Log.i(TAG, "ACTION_STOP received")
                  stopTracking()
                  stopSelf()
                  START_NOT_STICKY
              }
              ACTION_START -> {
                  val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
                      Log.e(TAG, "ACTION_START missing $EXTRA_SESSION_ID — stopping")
                      stopSelf()
                      return START_NOT_STICKY
                  }
                  val profileName = intent.getStringExtra(EXTRA_ACCURACY_PROFILE)
                      ?: AccuracyProfile.STANDARD.name
                  startTracking(sessionId, profileName)
                  START_STICKY
              }
              null -> {
                  // System restarted the service after killing it (START_STICKY recovery).
                  // The intent is null on restart; read session from SharedPreferences.
                  val sessionId = prefs.getString(PREFS_KEY_SESSION_ID, null) ?: run {
                      Log.w(TAG, "System restart but no persisted $PREFS_KEY_SESSION_ID — stopping")
                      return START_NOT_STICKY
                  }
                  val profileName = prefs.getString(PREFS_KEY_ACCURACY_PROFILE, AccuracyProfile.STANDARD.name)!!
                  Log.i(TAG, "System restart — resuming session=$sessionId profile=$profileName")
                  startTracking(sessionId, profileName)
                  START_STICKY
              }
              else -> {
                  Log.w(TAG, "Unknown action: ${intent?.action}")
                  START_NOT_STICKY
              }
          }
      }

      override fun onDestroy() {
          locationProvider.stopUpdates()
          serviceScope.coroutineContext[SupervisorJob()]?.cancel()
          Log.d(TAG, "Service destroyed")
          super.onDestroy()
      }

      // --- Tracking control ---

      private fun startTracking(sessionId: String, profileName: String) {
          currentSessionId = sessionId
          currentProfile = runCatching { AccuracyProfile.valueOf(profileName) }.getOrElse {
              Log.w(TAG, "Unknown AccuracyProfile '$profileName' — using STANDARD")
              AccuracyProfile.STANDARD
          }

          // Persist for START_STICKY recovery before doing anything else
          prefs.edit()
              .putString(PREFS_KEY_SESSION_ID, sessionId)
              .putString(PREFS_KEY_ACCURACY_PROFILE, currentProfile.name)
              .apply()

          startForeground(NOTIFICATION_ID, buildNotification())

          locationProvider.startUpdates(
              intervalMs = currentProfile.movingIntervalMs,
              priority   = currentProfile.priority,
              onLocation = ::onLocationReceived
          )

          Log.i(TAG, "Started tracking session=$sessionId profile=$currentProfile")
      }

      private fun stopTracking() {
          locationProvider.stopUpdates()
          flushBuffer()  // Must flush before stopping — buffer may have unsaved points
          prefs.edit()
              .remove(PREFS_KEY_SESSION_ID)
              .remove(PREFS_KEY_ACCURACY_PROFILE)
              .apply()
          Log.i(TAG, "Stopped tracking session=$currentSessionId")
          currentSessionId = null
      }

      // --- Location callback ---

      // Internal so LocationTrackingServiceTest can inject fake fixes directly.
      internal fun onLocationReceived(data: LocationData) {
          val sessionId = currentSessionId ?: return
          val speedKmh = (data.speedMs ?: 0f) * 3.6f
          val mode = TransportClassifier.classify(speedKmh.toDouble())

          // Auto-pause logic: timestamp-based (not point-count-based) for accuracy
          // across varying GPS intervals and profile changes.
          if (speedKmh < 1.0f) {
              if (stationaryStartMs == null) stationaryStartMs = data.timestampMs
              val stationaryMs = data.timestampMs - stationaryStartMs!!
              if (stationaryMs >= AUTO_PAUSE_THRESHOLD_MS && !isAutoPaused) {
                  enterAutoPause()
              }
          } else {
              stationaryStartMs = null
              if (isAutoPaused) exitAutoPause()
          }

          val point = TrackPointInsert(
              sessionId     = sessionId,
              timestamp     = data.timestampMs,
              latitude      = data.latitude,
              longitude     = data.longitude,
              altitude      = data.altitude,
              accuracy      = data.accuracyMeters,
              speed         = data.speedMs,
              transportMode = mode,
              isAutoPaused  = isAutoPaused
          )

          buffer.addLast(point)
          Log.d(TAG, "Buffered: lat=${data.latitude} speed=${speedKmh}km/h mode=$mode " +
                     "autoPaused=$isAutoPaused buffer=${buffer.size}")

          if (buffer.size >= BUFFER_SIZE) flushBuffer()
      }

      // --- Auto-pause transitions ---

      private fun enterAutoPause() {
          isAutoPaused = true
          // Reduce to one fix per 5 minutes while stationary to detect movement resumption
          locationProvider.startUpdates(
              intervalMs = AUTO_PAUSE_INTERVAL_MS,
              priority   = currentProfile.priority,
              onLocation = ::onLocationReceived
          )
          Log.i(TAG, "Auto-pause entered: interval→${AUTO_PAUSE_INTERVAL_MS / 1000}s")
      }

      private fun exitAutoPause() {
          isAutoPaused = false
          stationaryStartMs = null
          // Restore normal profile interval on movement
          locationProvider.startUpdates(
              intervalMs = currentProfile.movingIntervalMs,
              priority   = currentProfile.priority,
              onLocation = ::onLocationReceived
          )
          Log.i(TAG, "Auto-pause exited: interval restored to ${currentProfile.movingIntervalMs}ms")
      }

      // --- Buffer management ---

      private fun flushBuffer() {
          if (buffer.isEmpty()) return
          val toFlush = buffer.toList()
          buffer.clear()
          serviceScope.launch {
              try {
                  trackPointRepo.insertBatch(toFlush)
                  Log.d(TAG, "Flushed ${toFlush.size} points to DB")
              } catch (e: Exception) {
                  Log.e(TAG, "Failed to flush ${toFlush.size} points — points lost", e)
              }
          }
      }

      // --- Notification ---

      private fun createNotificationChannel() {
          // IMPORTANCE_LOW = silent (no sound/vibration). We don't want to disturb users
          // while they are recording a trip.
          // Note: this will move to Application.onCreate in Task 9 (Koin DI setup).
          val channel = NotificationChannel(
              CHANNEL_ID,
              "TripLens Recording",
              NotificationManager.IMPORTANCE_LOW
          ).apply {
              description = "Shown while TripLens is actively recording a trip"
          }
          getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
      }

      private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
          .setContentTitle("TripLens is recording")
          .setContentText("GPS tracking is active")
          .setSmallIcon(android.R.drawable.ic_menu_mylocation)
          .setOngoing(true)  // Cannot be dismissed by swiping
          .build()
  }
  ```

- [ ] **Step 6: Add `<service>` declaration to `AndroidManifest.xml`**

  In `composeApp/src/androidMain/AndroidManifest.xml`, add the service declaration inside `<application>` after the `<activity>` block:

  ```xml
  <service
      android:name=".service.LocationTrackingService"
      android:foregroundServiceType="location"
      android:exported="false" />
  ```

  Full updated manifest:

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <manifest xmlns:android="http://schemas.android.com/apk/res/android">

      <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
      <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
      <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
      <uses-permission android:name="android.permission.RECORD_AUDIO" />
      <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
      <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
      <uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />

      <application
          android:allowBackup="true"
          android:icon="@mipmap/ic_launcher"
          android:label="@string/app_name"
          android:roundIcon="@mipmap/ic_launcher_round"
          android:supportsRtl="true"
          android:theme="@android:style/Theme.Material.Light.NoActionBar">

          <activity
              android:exported="true"
              android:name=".MainActivity">
              <intent-filter>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
          </activity>

          <!-- foregroundServiceType="location" required for Android 10+ background location -->
          <service
              android:name=".service.LocationTrackingService"
              android:foregroundServiceType="location"
              android:exported="false" />

      </application>

  </manifest>
  ```

- [ ] **Step 7: Sync Gradle and verify compilation**

  ```
  ./gradlew :composeApp:assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL` — service compiles cleanly.

- [ ] **Step 8: Run JVM unit tests — confirm no regressions**

  ```
  ./gradlew :shared:testDebugUnitTest
  ```

  Expected: `BUILD SUCCESSFUL` — all shared tests still pass.

- [ ] **Step 9: Run Android instrumented tests on a connected device or emulator**

  > **Prerequisite:** An Android emulator (API 26+) must be running or a device connected via ADB.

  ```
  ./gradlew :composeApp:connectedAndroidTest
  ```

  Expected: All 6 `LocationTrackingServiceTest` tests pass.

  > **Note on `lifecycle_startService_persistsSessionToPrefs`**: The FusedLocationProviderClient requires Google Play Services. On emulators without Play Services (e.g., AOSP images), this test will fail. Use an emulator with Play Services (e.g., "Google APIs" system image) or a physical device.

- [ ] **Step 10: Commit**

  ```bash
  git add gradle/libs.versions.toml
  git add composeApp/build.gradle.kts
  git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/service/LocationTrackingService.kt
  git add composeApp/src/androidMain/AndroidManifest.xml
  git add composeApp/src/androidTest/kotlin/com/cooldog/triplens/service/LocationTrackingServiceTest.kt
  git commit -m "feat(service): implement LocationTrackingService with auto-pause and buffer"
  ```
