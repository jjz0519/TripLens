# TripLens Phase 0 + Phase 1: KMP Scaffold & Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Create a compiling KMP project and implement the full data layer — models, SQLDelight schema, repositories, transport classifier, and segment smoother — all with passing unit tests.

**Architecture:** KMP shared module (`commonMain`) holds all domain logic and DB access; `androidMain` holds the Android SQLite driver. Phase 1 code is 100% pure JVM-testable (no emulator needed).

**Tech Stack:** Kotlin 2.1.x, KMP, SQLDelight 2.0.x, Kotlinx Serialization 1.7.x, Kotlinx Coroutines 1.9.x, JUnit 4, Koin (wired in Task 9, not here)

---

## Package Convention

All source files use package prefix `com.triplens`. Paths below use `…` for `src/commonMain/kotlin/com/triplens` and `…android` for `src/androidMain/kotlin/com/triplens`.

---

## Task 1: KMP Project Scaffold (Human-assisted in Android Studio)

> **This task requires Android Studio actions. Claude cannot create a Gradle project from scratch.**

**Files created:**
- `build.gradle.kts` (root)
- `gradle/libs.versions.toml`
- `shared/build.gradle.kts`
- `composeApp/build.gradle.kts`
- `composeApp/src/main/AndroidManifest.xml`
- `composeApp/src/main/java/com/triplens/android/MainActivity.kt`
- `settings.gradle.kts`

- [x] **Step 1: Generate project via KMP Wizard**

  In Android Studio: **File → New → New Project → Kotlin Multiplatform App**.
  - Application name: `TripLens`
  - Package name: `com.triplens`
  - Project location: `E:\TripLens` (your existing repo)
  - Uncheck iOS (add later)
  - Android minimum SDK: 26 (Android 8.0)

  If the wizard isn't available, use [kmp.jetbrains.com](https://kmp.jetbrains.com) to generate, download, and unzip into the repo directory.

- [x] **Step 2: Replace `gradle/libs.versions.toml` with the pinned version catalog**

  Replace the entire file with:

  ```toml
  [versions]
  kotlin = "2.1.0"
  compose-multiplatform = "1.7.0"
  sqldelight = "2.0.2"
  koin = "4.0.0"
  kotlinx-serialization = "1.7.3"
  kotlinx-coroutines = "1.9.0"
  coil = "3.0.4"
  navigation-compose = "2.8.5"
  play-services-location = "21.3.0"
  androidx-core = "1.15.0"
  androidx-lifecycle = "2.8.7"
  androidx-activity-compose = "1.10.0"
  junit = "4.13.2"
  androidx-test-junit = "1.2.1"

  [libraries]
  kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
  kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
  kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinx-coroutines" }
  kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }

  sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
  sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
  sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
  sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }  # JVM tests

  koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
  koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
  koin-test = { module = "io.insert-koin:koin-test", version.ref = "koin" }

  coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
  navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation-compose" }
  play-services-location = { module = "com.google.android.gms:play-services-location", version.ref = "play-services-location" }
  androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidx-core" }
  androidx-lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "androidx-lifecycle" }
  androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity-compose" }

  junit = { module = "junit:junit", version.ref = "junit" }
  androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidx-test-junit" }

  [plugins]
  kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
  kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
  compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
  compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
  sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
  android-application = { id = "com.android.application", version = "8.7.3" }
  android-library = { id = "com.android.library", version = "8.7.3" }
  ```

- [x] **Step 3: Replace root `build.gradle.kts`**

  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.multiplatform) apply false
      alias(libs.plugins.kotlin.serialization) apply false
      alias(libs.plugins.compose.multiplatform) apply false
      alias(libs.plugins.compose.compiler) apply false
      alias(libs.plugins.sqldelight) apply false
      alias(libs.plugins.android.application) apply false
      alias(libs.plugins.android.library) apply false
  }
  ```

- [x] **Step 4: Replace `shared/build.gradle.kts`**

  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.multiplatform)
      alias(libs.plugins.kotlin.serialization)
      alias(libs.plugins.android.library)
      alias(libs.plugins.sqldelight)
  }

  kotlin {
      androidTarget {
          compilations.all { kotlinOptions { jvmTarget = "11" } }
      }

      sourceSets {
          commonMain.dependencies {
              implementation(libs.kotlinx.serialization.json)
              implementation(libs.kotlinx.coroutines.core)
              implementation(libs.sqldelight.runtime)
              implementation(libs.sqldelight.coroutines)
              implementation(libs.koin.core)
          }
          commonTest.dependencies {
              implementation(kotlin("test"))
              implementation(libs.kotlinx.coroutines.test)
              implementation(libs.sqldelight.sqlite.driver)
          }
          androidMain.dependencies {
              implementation(libs.sqldelight.android.driver)
              implementation(libs.kotlinx.coroutines.android)
          }
      }
  }

  android {
      namespace = "com.triplens.shared"
      compileSdk = 35
      defaultConfig { minSdk = 26 }
      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_11
          targetCompatibility = JavaVersion.VERSION_11
      }
  }

  sqldelight {
      databases {
          create("TripLensDatabase") {
              packageName.set("com.triplens.db")
              srcDirs("src/commonMain/sqldelight")
          }
      }
  }
  ```

- [x] **Step 5: Replace `composeApp/build.gradle.kts`**

  ```kotlin
  plugins {
      alias(libs.plugins.android.application)
      alias(libs.plugins.kotlin.multiplatform)
      alias(libs.plugins.compose.multiplatform)
      alias(libs.plugins.compose.compiler)
  }

  kotlin {
      androidTarget {
          compilations.all { kotlinOptions { jvmTarget = "11" } }
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
      }
  }

  android {
      namespace = "com.triplens.android"
      compileSdk = 35
      defaultConfig {
          applicationId = "com.triplens.android"
          minSdk = 26
          targetSdk = 35
          versionCode = 1
          versionName = "1.0"
      }
      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_11
          targetCompatibility = JavaVersion.VERSION_11
      }
  }
  ```

- [x] **Step 6: Update `AndroidManifest.xml` with all permissions**

  Replace `<manifest>` contents (inside the existing manifest, before `<application>`):

  ```xml
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
  <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
  <uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />
  ```

- [x] **Step 7: Gradle sync and verify**

  In Android Studio: **File → Sync Project with Gradle Files**
  Expected: BUILD SUCCESSFUL, no red underlines.

  Run empty app on emulator. Expected: blank screen, no crash.

- [x] **Step 8: Commit**

  ```bash
  git add -A
  git commit -m "feat: initialize KMP project scaffold with all dependencies"
  ```

---

## Task 2: Core Data Models

**Files:**
- Create: `shared/src/commonMain/kotlin/com/triplens/model/TransportMode.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/SessionStatus.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/MediaType.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/MediaSource.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/LocationSource.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/TripGroup.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/Session.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/TrackPoint.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/MediaReference.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/model/Note.kt`
- Create: `shared/src/commonTest/kotlin/com/triplens/model/ModelSerializationTest.kt`

### Step-by-step

- [x] **Step 1: Write the serialization test first**

  Create `shared/src/commonTest/kotlin/com/triplens/model/ModelSerializationTest.kt`:

  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.encodeToString
  import kotlinx.serialization.json.Json
  import kotlin.test.Test
  import kotlin.test.assertEquals

  class ModelSerializationTest {

      private val json = Json { encodeDefaults = true }

      @Test
      fun tripGroup_roundTrip() {
          val group = TripGroup(
              id = "abc",
              name = "Wellington",
              createdAt = 1_700_000_000_000L,
              updatedAt = 1_700_000_001_000L
          )
          val encoded = json.encodeToString(group)
          assertEquals(group, json.decodeFromString(encoded))
      }

      @Test
      fun session_roundTrip_withNullEndTime() {
          val session = Session(
              id = "s1",
              groupId = "g1",
              name = "Day 1",
              startTime = 1_700_000_000_000L,
              endTime = null,
              status = SessionStatus.RECORDING
          )
          val encoded = json.encodeToString(session)
          val decoded: Session = json.decodeFromString(encoded)
          assertEquals(session, decoded)
          assertEquals(null, decoded.endTime)
      }

      @Test
      fun trackPoint_roundTrip_withNullOptionals() {
          val point = TrackPoint(
              id = 1L,
              sessionId = "s1",
              timestamp = 1_700_000_000_000L,
              latitude = -41.2865,
              longitude = 174.7762,
              altitude = null,
              accuracy = 8.5f,
              speed = null,
              transportMode = TransportMode.WALKING
          )
          val encoded = json.encodeToString(point)
          assertEquals(point, json.decodeFromString(encoded))
      }

      @Test
      fun mediaReference_roundTrip() {
          val ref = MediaReference(
              id = "m1",
              sessionId = "s1",
              type = MediaType.PHOTO,
              source = MediaSource.PHONE_GALLERY,
              contentUri = "content://media/external/images/1234",
              originalFilename = "IMG_001.jpg",
              capturedAt = 1_700_000_000_000L,
              timestampOffset = 0,
              originalLat = -41.29,
              originalLng = 174.78,
              inferredLat = null,
              inferredLng = null,
              locationSource = LocationSource.EXIF,
              matchedSessionId = null,
              matchedTrackpointId = null
          )
          val encoded = json.encodeToString(ref)
          assertEquals(ref, json.decodeFromString(encoded))
      }

      @Test
      fun note_roundTrip_voiceNote() {
          val note = Note(
              id = "n1",
              sessionId = "s1",
              type = NoteType.VOICE,
              content = null,
              audioFilename = "note_n1.m4a",
              durationSeconds = 45,
              createdAt = 1_700_000_000_000L,
              latitude = -41.2865,
              longitude = 174.7762
          )
          val encoded = json.encodeToString(note)
          assertEquals(note, json.decodeFromString(encoded))
      }

      @Test
      fun transportMode_serializedValues_areLowercase() {
          // DB CHECK constraints use lowercase: 'walking' not 'WALKING'
          assertEquals("\"walking\"", json.encodeToString(TransportMode.WALKING))
          assertEquals("\"fast_transit\"", json.encodeToString(TransportMode.FAST_TRANSIT))
          assertEquals("\"stationary\"", json.encodeToString(TransportMode.STATIONARY))
      }

      @Test
      fun sessionStatus_serializedValues_areLowercase() {
          assertEquals("\"recording\"", json.encodeToString(SessionStatus.RECORDING))
          assertEquals("\"completed\"", json.encodeToString(SessionStatus.COMPLETED))
          assertEquals("\"interrupted\"", json.encodeToString(SessionStatus.INTERRUPTED))
      }
  }
  ```

- [x] **Step 2: Run test to confirm it fails (models don't exist yet)**

  In Android Studio: right-click `ModelSerializationTest.kt` → Run. Expected: compilation errors about missing types.

- [x] **Step 3: Create enum files**

  `shared/src/commonMain/kotlin/com/triplens/model/TransportMode.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  // Speed-based transport mode. Lowercase serial names must match DB CHECK constraints
  // and index.json compact format (TDD Section 3.1 and 7.2).
  @Serializable
  enum class TransportMode {
      @SerialName("stationary") STATIONARY,
      @SerialName("walking")    WALKING,
      @SerialName("cycling")    CYCLING,
      @SerialName("driving")    DRIVING,
      @SerialName("fast_transit") FAST_TRANSIT
  }
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/SessionStatus.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  @Serializable
  enum class SessionStatus {
      @SerialName("recording")    RECORDING,
      @SerialName("completed")    COMPLETED,
      @SerialName("interrupted")  INTERRUPTED
  }
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/MediaType.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  @Serializable
  enum class MediaType {
      @SerialName("photo") PHOTO,
      @SerialName("video") VIDEO
  }
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/MediaSource.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  @Serializable
  enum class MediaSource {
      @SerialName("phone_gallery")    PHONE_GALLERY,
      @SerialName("external_camera")  EXTERNAL_CAMERA
  }
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/LocationSource.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  @Serializable
  enum class LocationSource {
      @SerialName("exif")       EXIF,
      @SerialName("trajectory") TRAJECTORY
  }
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/NoteType.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  @Serializable
  enum class NoteType {
      @SerialName("text")  TEXT,
      @SerialName("voice") VOICE
  }
  ```

- [x] **Step 4: Create data class files**

  `shared/src/commonMain/kotlin/com/triplens/model/TripGroup.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.Serializable

  @Serializable
  data class TripGroup(
      val id: String,
      val name: String,
      val createdAt: Long,   // epoch millis UTC
      val updatedAt: Long    // epoch millis UTC
  )
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/Session.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.Serializable

  @Serializable
  data class Session(
      val id: String,
      val groupId: String,
      val name: String,
      val startTime: Long,         // epoch millis UTC
      val endTime: Long?,          // null while recording
      val status: SessionStatus
  )
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/TrackPoint.kt`:
  ```kotlin
  package com.triplens.model

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
      val transportMode: TransportMode
  )
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/MediaReference.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.Serializable

  @Serializable
  data class MediaReference(
      val id: String,
      val sessionId: String,
      val type: MediaType,
      val source: MediaSource,
      val contentUri: String?,
      val originalFilename: String?,
      val capturedAt: Long,              // epoch millis UTC
      val timestampOffset: Int,          // seconds; camera clock drift vs phone time
      val originalLat: Double?,          // from EXIF, null if not present
      val originalLng: Double?,
      val inferredLat: Double?,          // from trajectory matching, null until computed
      val inferredLng: Double?,
      val locationSource: LocationSource?,
      val matchedSessionId: String?,
      val matchedTrackpointId: Long?
  )
  ```

  `shared/src/commonMain/kotlin/com/triplens/model/Note.kt`:
  ```kotlin
  package com.triplens.model

  import kotlinx.serialization.Serializable

  @Serializable
  data class Note(
      val id: String,
      val sessionId: String,
      val type: NoteType,
      val content: String?,           // null for voice notes
      val audioFilename: String?,     // null for text notes; e.g. "note_{uuid}.m4a"
      val durationSeconds: Int?,      // null for text notes
      val createdAt: Long,            // epoch millis UTC
      val latitude: Double,
      val longitude: Double
  )
  ```

- [x] **Step 5: Run tests — confirm all pass**

  In Android Studio: right-click `ModelSerializationTest.kt` → Run. Expected: 7 tests pass.

- [x] **Step 6: Commit**

  ```bash
  git add shared/src/commonMain/kotlin/com/triplens/model/ \
          shared/src/commonTest/kotlin/com/triplens/model/
  git commit -m "feat(data): add core domain models and enums with serialization"
  ```

---

## Task 3: SQLDelight Database Schema

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/triplens/db/schema.sq`
- Create: `shared/src/commonMain/sqldelight/com/triplens/db/TripGroupQueries.sq`
- Create: `shared/src/commonMain/sqldelight/com/triplens/db/SessionQueries.sq`
- Create: `shared/src/commonMain/sqldelight/com/triplens/db/TrackPointQueries.sq`
- Create: `shared/src/commonMain/sqldelight/com/triplens/db/MediaRefQueries.sq`
- Create: `shared/src/commonMain/sqldelight/com/triplens/db/NoteQueries.sq`
- Create: `shared/src/commonMain/kotlin/com/triplens/db/DatabaseDriverFactory.kt` (expect)
- Create: `shared/src/androidMain/kotlin/com/triplens/db/DatabaseDriverFactory.kt` (actual)
- Create: `shared/src/commonMain/kotlin/com/triplens/db/TripLensDatabase.kt`
- Create: `shared/src/commonTest/kotlin/com/triplens/db/DatabaseSchemaTest.kt`

### Step-by-step

- [x] **Step 1: Write failing schema tests**

  Create `shared/src/commonTest/kotlin/com/triplens/db/DatabaseSchemaTest.kt`:

  ```kotlin
  package com.triplens.db

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
      private lateinit var db: TripLensDatabase

      @BeforeTest
      fun setup() {
          // In-memory SQLite — no Android emulator required
          driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
          TripLensDatabase.Schema.create(driver)
          db = TripLensDatabase(driver)
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
          db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking")
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
              db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "WALKING")
          }
      }

      @Test
      fun session_delete_cascadesToTrackPoints() {
          db.tripGroupQueries.insert("g1", "Trip", 1_000L, 1_000L)
          db.sessionQueries.insert("s1", "g1", "Day 1", 2_000L, null, "recording")
          db.trackPointQueries.insert("s1", 3_000L, -41.28, 174.77, null, 8.5, null, "walking")
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
  ```

- [x] **Step 2: Run test — confirm compilation errors (no DB files yet)**

- [x] **Step 3: Create SQLDelight `.sq` schema file**

  Create `shared/src/commonMain/sqldelight/com/triplens/db/schema.sq`:

  ```sql
  -- TripLens SQLite schema. All timestamps are epoch milliseconds UTC.
  -- Lowercase CHECK values must match TransportMode and SessionStatus serial names.

  CREATE TABLE trip_group (
      id         TEXT    NOT NULL PRIMARY KEY,
      name       TEXT    NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
  );

  CREATE TABLE session (
      id         TEXT    NOT NULL PRIMARY KEY,
      group_id   TEXT    NOT NULL REFERENCES trip_group(id) ON DELETE CASCADE,
      name       TEXT    NOT NULL,
      start_time INTEGER NOT NULL,
      end_time   INTEGER,
      status     TEXT    NOT NULL DEFAULT 'recording'
          CHECK (status IN ('recording', 'completed', 'interrupted'))
  );

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
          CHECK (transport_mode IN ('stationary','walking','cycling','driving','fast_transit'))
  );
  CREATE INDEX idx_tp_session_time ON track_point(session_id, timestamp);

  CREATE TABLE media_reference (
      id                   TEXT    NOT NULL PRIMARY KEY,
      session_id           TEXT    NOT NULL REFERENCES session(id) ON DELETE CASCADE,
      type                 TEXT    NOT NULL CHECK (type IN ('photo','video')),
      source               TEXT    NOT NULL CHECK (source IN ('phone_gallery','external_camera')),
      content_uri          TEXT,
      original_filename    TEXT,
      captured_at          INTEGER NOT NULL,
      timestamp_offset     INTEGER NOT NULL DEFAULT 0,
      original_lat         REAL,
      original_lng         REAL,
      inferred_lat         REAL,
      inferred_lng         REAL,
      location_source      TEXT    CHECK (location_source IN ('exif','trajectory')),
      matched_session_id   TEXT,
      matched_trackpoint_id INTEGER
  );
  CREATE INDEX idx_mr_session   ON media_reference(session_id);
  CREATE INDEX idx_mr_captured  ON media_reference(captured_at);

  CREATE TABLE note (
      id               TEXT    NOT NULL PRIMARY KEY,
      session_id       TEXT    NOT NULL REFERENCES session(id) ON DELETE CASCADE,
      type             TEXT    NOT NULL CHECK (type IN ('text','voice')),
      content          TEXT,
      audio_filename   TEXT,
      duration_seconds INTEGER,
      created_at       INTEGER NOT NULL,
      latitude         REAL    NOT NULL,
      longitude        REAL    NOT NULL
  );
  CREATE INDEX idx_note_session ON note(session_id);
  ```

- [x] **Step 4: Create query files**

  Create `shared/src/commonMain/sqldelight/com/triplens/db/TripGroupQueries.sq`:
  ```sql
  insert:
  INSERT INTO trip_group(id, name, created_at, updated_at)
  VALUES (?, ?, ?, ?);

  getById:
  SELECT * FROM trip_group WHERE id = ?;

  getAll:
  SELECT * FROM trip_group ORDER BY created_at DESC;

  updateName:
  UPDATE trip_group SET name = ?, updated_at = ? WHERE id = ?;

  delete:
  DELETE FROM trip_group WHERE id = ?;
  ```

  Create `shared/src/commonMain/sqldelight/com/triplens/db/SessionQueries.sq`:
  ```sql
  insert:
  INSERT INTO session(id, group_id, name, start_time, end_time, status)
  VALUES (?, ?, ?, ?, ?, ?);

  getById:
  SELECT * FROM session WHERE id = ?;

  getByGroupId:
  SELECT * FROM session WHERE group_id = ? ORDER BY start_time ASC;

  updateStatus:
  UPDATE session SET status = ? WHERE id = ?;

  setEndTime:
  UPDATE session SET end_time = ?, status = 'completed' WHERE id = ?;

  getActiveSession:
  SELECT * FROM session WHERE status = 'recording' LIMIT 1;

  delete:
  DELETE FROM session WHERE id = ?;
  ```

  Create `shared/src/commonMain/sqldelight/com/triplens/db/TrackPointQueries.sq`:
  ```sql
  insert:
  INSERT INTO track_point(session_id, timestamp, latitude, longitude, altitude, accuracy, speed, transport_mode)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?);

  getBySessionId:
  SELECT * FROM track_point WHERE session_id = ? ORDER BY timestamp ASC;

  getBySessionIdAndTimeRange:
  SELECT * FROM track_point
  WHERE session_id = ? AND timestamp >= ? AND timestamp <= ?
  ORDER BY timestamp ASC;

  countBySession:
  SELECT COUNT(*) FROM track_point WHERE session_id = ?;
  ```

  Create `shared/src/commonMain/sqldelight/com/triplens/db/MediaRefQueries.sq`:
  ```sql
  insert:
  INSERT INTO media_reference(
      id, session_id, type, source, content_uri, original_filename,
      captured_at, timestamp_offset, original_lat, original_lng,
      inferred_lat, inferred_lng, location_source, matched_session_id, matched_trackpoint_id
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

  getBySessionId:
  SELECT * FROM media_reference WHERE session_id = ? ORDER BY captured_at ASC;

  getByContentUri:
  SELECT * FROM media_reference WHERE content_uri = ? LIMIT 1;

  updateInferredLocation:
  UPDATE media_reference
  SET inferred_lat = ?, inferred_lng = ?, location_source = 'trajectory'
  WHERE id = ?;
  ```

  Create `shared/src/commonMain/sqldelight/com/triplens/db/NoteQueries.sq`:
  ```sql
  insert:
  INSERT INTO note(id, session_id, type, content, audio_filename, duration_seconds, created_at, latitude, longitude)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

  getBySessionId:
  SELECT * FROM note WHERE session_id = ? ORDER BY created_at ASC;

  getVoiceNotesBySession:
  SELECT * FROM note WHERE session_id = ? AND type = 'voice' ORDER BY created_at ASC;

  delete:
  DELETE FROM note WHERE id = ?;
  ```

- [x] **Step 5: Create `expect` DatabaseDriverFactory**

  Create `shared/src/commonMain/kotlin/com/triplens/db/DatabaseDriverFactory.kt`:
  ```kotlin
  package com.triplens.db

  import app.cash.sqldelight.db.SqlDriver

  // Platform-specific driver creation. Android uses AndroidSqliteDriver;
  // JVM tests use JdbcSqliteDriver (in-memory).
  expect class DatabaseDriverFactory {
      fun createDriver(): SqlDriver
  }
  ```

- [x] **Step 6: Create Android `actual` DatabaseDriverFactory**

  Create `shared/src/androidMain/kotlin/com/triplens/db/DatabaseDriverFactory.kt`:
  ```kotlin
  package com.triplens.db

  import android.content.Context
  import app.cash.sqldelight.db.SqlDriver
  import app.cash.sqldelight.driver.android.AndroidSqliteDriver

  actual class DatabaseDriverFactory(private val context: Context) {
      actual fun createDriver(): SqlDriver =
          AndroidSqliteDriver(TripLensDatabase.Schema, context, "triplens.db")
  }
  ```

- [x] **Step 7: Create `TripLensDatabase` wrapper**

  Create `shared/src/commonMain/kotlin/com/triplens/db/TripLensDatabase.kt`:
  ```kotlin
  package com.triplens.db

  import app.cash.sqldelight.db.SqlDriver
  import com.triplens.db.TripLensDatabase as GeneratedDb

  // Wraps the SQLDelight-generated database.
  // Enables WAL mode for better concurrent read performance.
  // Exposes integrityCheck() for startup validation (TDD Section 14).
  class TripLensDatabase(driver: SqlDriver) {

      private val db = GeneratedDb(driver)

      val tripGroupQueries get() = db.tripGroupQueries
      val sessionQueries   get() = db.sessionQueries
      val trackPointQueries get() = db.trackPointQueries
      val mediaRefQueries  get() = db.mediaRefQueries
      val noteQueries      get() = db.noteQueries

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
  ```

  > Note: the `TripLensDatabase` wrapper and the SQLDelight-generated `TripLensDatabase` share a name. The import alias `as GeneratedDb` resolves this.

- [x] **Step 8: Gradle sync, then run `DatabaseSchemaTest`**

  Expected: all 8 tests pass.

- [x] **Step 9: Commit**

  ```bash
  git add shared/src/commonMain/sqldelight/ \
          shared/src/commonMain/kotlin/com/triplens/db/ \
          shared/src/androidMain/kotlin/com/triplens/db/ \
          shared/src/commonTest/kotlin/com/triplens/db/
  git commit -m "feat(data): add SQLDelight schema, queries, and DB wrapper"
  ```

---

## Task 4: Repository Layer

**Files:**
- Create: `shared/src/commonMain/kotlin/com/triplens/repository/TripRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/repository/SessionRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/repository/TrackPointRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/repository/MediaRefRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/repository/NoteRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/triplens/repository/RepositoryTest.kt`

### Step-by-step

- [x] **Step 1: Write failing repository tests**

  Create `shared/src/commonTest/kotlin/com/triplens/repository/RepositoryTest.kt`:

  ```kotlin
  package com.triplens.repository

  import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
  import com.triplens.db.TripLensDatabase
  import com.triplens.model.NoteType
  import com.triplens.model.SessionStatus
  import com.triplens.model.TransportMode
  import kotlinx.coroutines.test.runTest
  import kotlin.test.BeforeTest
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertNotNull
  import kotlin.test.assertNull
  import kotlin.test.assertTrue

  class RepositoryTest {

      private lateinit var db: TripLensDatabase
      private lateinit var tripRepo: TripRepository
      private lateinit var sessionRepo: SessionRepository
      private lateinit var trackPointRepo: TrackPointRepository
      private lateinit var mediaRefRepo: MediaRefRepository
      private lateinit var noteRepo: NoteRepository

      @BeforeTest
      fun setup() {
          val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
          TripLensDatabase.Schema.create(driver)
          db = TripLensDatabase(driver)
          tripRepo = TripRepository(db)
          sessionRepo = SessionRepository(db)
          trackPointRepo = TrackPointRepository(db)
          mediaRefRepo = MediaRefRepository(db)
          noteRepo = NoteRepository(db)
      }

      // --- TripRepository ---

      @Test
      fun tripRepository_createAndGetAll() = runTest {
          tripRepo.createGroup("id1", "Wellington", 1_000L)
          tripRepo.createGroup("id2", "Tokyo", 2_000L)
          val all = tripRepo.getAllGroups()
          assertEquals(2, all.size)
      }

      @Test
      fun tripRepository_rename() = runTest {
          tripRepo.createGroup("id1", "Old Name", 1_000L)
          tripRepo.renameGroup("id1", "New Name", 2_000L)
          val group = tripRepo.getGroupById("id1")
          assertNotNull(group)
          assertEquals("New Name", group.name)
      }

      @Test
      fun tripRepository_delete_cascadesToSessions() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
          tripRepo.deleteGroup("g1")
          val sessions = sessionRepo.getSessionsByGroup("g1")
          assertTrue(sessions.isEmpty())
      }

      // --- SessionRepository ---

      @Test
      fun sessionRepository_getActiveSession_nullWhenNoneRecording() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
          sessionRepo.completeSession("s1", 3_000L)
          assertNull(sessionRepo.getActiveSession())
      }

      @Test
      fun sessionRepository_getActiveSession_returnsRecordingSession() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
          val active = sessionRepo.getActiveSession()
          assertNotNull(active)
          assertEquals("s1", active.id)
          assertEquals(SessionStatus.RECORDING, active.status)
      }

      @Test
      fun sessionRepository_markInterrupted() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
          sessionRepo.markInterrupted("s1")
          val session = sessionRepo.getSessionById("s1")
          assertEquals(SessionStatus.INTERRUPTED, session?.status)
      }

      // --- TrackPointRepository ---

      @Test
      fun trackPointRepository_insertBatch_allPersistedInSingleTransaction() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)

          // Build 10 points
          val points = (0 until 10).map { i ->
              TrackPointInsert(
                  sessionId = "s1",
                  timestamp = 3_000L + i * 1_000L,
                  latitude = -41.28 + i * 0.001,
                  longitude = 174.77,
                  altitude = null,
                  accuracy = 8.5f,
                  speed = 1.5f,
                  transportMode = TransportMode.WALKING
              )
          }

          trackPointRepo.insertBatch(points)

          val stored = trackPointRepo.getBySession("s1")
          assertEquals(10, stored.size)
      }

      // --- MediaRefRepository ---

      @Test
      fun mediaRefRepository_insertIfNotExists_deduplicatesByUri() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)

          mediaRefRepo.insertIfNotExists(
              "m1", "s1", "photo", "phone_gallery",
              "content://media/1", "IMG.jpg", 3_000L
          )
          mediaRefRepo.insertIfNotExists(
              "m2", "s1", "photo", "phone_gallery",
              "content://media/1", "IMG.jpg", 3_000L  // same URI
          )

          val refs = mediaRefRepo.getBySession("s1")
          assertEquals(1, refs.size)
          assertEquals("m1", refs[0].id)  // First insert wins
      }

      // --- NoteRepository ---

      @Test
      fun noteRepository_createTextAndVoice_queryBySession() = runTest {
          tripRepo.createGroup("g1", "Trip", 1_000L)
          sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)

          noteRepo.createTextNote("n1", "s1", "Great view!", 3_000L, -41.28, 174.77)
          noteRepo.createVoiceNote("n2", "s1", "note_n2.m4a", 45, 4_000L, -41.29, 174.78)

          val notes = noteRepo.getBySession("s1")
          assertEquals(2, notes.size)
          assertEquals(NoteType.TEXT, notes[0].type)
          assertEquals(NoteType.VOICE, notes[1].type)
          assertEquals("note_n2.m4a", notes[1].audioFilename)
      }
  }
  ```

- [x] **Step 2: Run test — confirm compilation errors (no repos yet)**

- [x] **Step 3: Create `TrackPointInsert` value class**

  Create `shared/src/commonMain/kotlin/com/triplens/repository/TrackPointInsert.kt`:
  ```kotlin
  package com.triplens.repository

  import com.triplens.model.TransportMode

  // Lightweight insert payload — avoids using the generated DB type in business logic.
  data class TrackPointInsert(
      val sessionId: String,
      val timestamp: Long,
      val latitude: Double,
      val longitude: Double,
      val altitude: Double?,
      val accuracy: Float,
      val speed: Float?,
      val transportMode: TransportMode
  )
  ```

- [x] **Step 4: Create repository files**

  Create `shared/src/commonMain/kotlin/com/triplens/repository/TripRepository.kt`:
  ```kotlin
  package com.triplens.repository

  import com.triplens.db.TripLensDatabase
  import com.triplens.model.TripGroup

  class TripRepository(private val db: TripLensDatabase) {

      fun createGroup(id: String, name: String, now: Long) {
          db.tripGroupQueries.insert(id, name, now, now)
      }

      fun renameGroup(id: String, newName: String, now: Long) {
          db.tripGroupQueries.updateName(newName, now, id)
      }

      fun deleteGroup(id: String) {
          db.tripGroupQueries.delete(id)
      }

      fun getAllGroups(): List<TripGroup> =
          db.tripGroupQueries.getAll().executeAsList().map { it.toModel() }

      fun getGroupById(id: String): TripGroup? =
          db.tripGroupQueries.getById(id).executeAsOneOrNull()?.toModel()

      private fun com.triplens.db.Trip_group.toModel() = TripGroup(
          id = id,
          name = name,
          createdAt = created_at,
          updatedAt = updated_at
      )
  }
  ```

  Create `shared/src/commonMain/kotlin/com/triplens/repository/SessionRepository.kt`:
  ```kotlin
  package com.triplens.repository

  import com.triplens.db.TripLensDatabase
  import com.triplens.model.Session
  import com.triplens.model.SessionStatus

  class SessionRepository(private val db: TripLensDatabase) {

      fun createSession(id: String, groupId: String, name: String, startTime: Long) {
          db.sessionQueries.insert(id, groupId, name, startTime, null, "recording")
      }

      fun completeSession(id: String, endTime: Long) {
          db.sessionQueries.setEndTime(endTime, id)
      }

      fun markInterrupted(id: String) {
          db.sessionQueries.updateStatus("interrupted", id)
      }

      fun getActiveSession(): Session? =
          db.sessionQueries.getActiveSession().executeAsOneOrNull()?.toModel()

      fun getSessionById(id: String): Session? =
          db.sessionQueries.getById(id).executeAsOneOrNull()?.toModel()

      fun getSessionsByGroup(groupId: String): List<Session> =
          db.sessionQueries.getByGroupId(groupId).executeAsList().map { it.toModel() }

      private fun com.triplens.db.Session.toModel() = Session(
          id = id,
          groupId = group_id,
          name = name,
          startTime = start_time,
          endTime = end_time,
          status = SessionStatus.valueOf(status.uppercase())
      )
  }
  ```

  Create `shared/src/commonMain/kotlin/com/triplens/repository/TrackPointRepository.kt`:
  ```kotlin
  package com.triplens.repository

  import com.triplens.db.TripLensDatabase
  import com.triplens.model.TrackPoint
  import com.triplens.model.TransportMode

  class TrackPointRepository(private val db: TripLensDatabase) {

      fun insert(point: TrackPointInsert) {
          db.trackPointQueries.insert(
              point.sessionId, point.timestamp,
              point.latitude, point.longitude, point.altitude,
              point.accuracy.toDouble(), point.speed?.toDouble(),
              point.transportMode.name.lowercase()
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

      private fun com.triplens.db.Track_point.toModel() = TrackPoint(
          id = id,
          sessionId = session_id,
          timestamp = timestamp,
          latitude = latitude,
          longitude = longitude,
          altitude = altitude,
          accuracy = accuracy.toFloat(),
          speed = speed?.toFloat(),
          transportMode = TransportMode.valueOf(transport_mode.uppercase())
      )
  }
  ```

  Create `shared/src/commonMain/kotlin/com/triplens/repository/MediaRefRepository.kt`:
  ```kotlin
  package com.triplens.repository

  import com.triplens.db.TripLensDatabase
  import com.triplens.model.LocationSource
  import com.triplens.model.MediaReference
  import com.triplens.model.MediaSource
  import com.triplens.model.MediaType

  class MediaRefRepository(private val db: TripLensDatabase) {

      /**
       * Inserts a media reference only if no row with the same content_uri already exists.
       * Deduplication prevents double-counting when the gallery scanner runs repeatedly.
       */
      fun insertIfNotExists(
          id: String, sessionId: String, type: String, source: String,
          contentUri: String, filename: String, capturedAt: Long
      ) {
          val existing = db.mediaRefQueries.getByContentUri(contentUri).executeAsOneOrNull()
          if (existing == null) {
              db.mediaRefQueries.insert(
                  id, sessionId, type, source, contentUri, filename,
                  capturedAt, 0, null, null, null, null, null, null, null
              )
          }
      }

      fun getBySession(sessionId: String): List<MediaReference> =
          db.mediaRefQueries.getBySessionId(sessionId).executeAsList().map { it.toModel() }

      fun updateInferredLocation(id: String, lat: Double, lng: Double) {
          db.mediaRefQueries.updateInferredLocation(lat, lng, id)
      }

      private fun com.triplens.db.Media_reference.toModel() = MediaReference(
          id = id,
          sessionId = session_id,
          type = MediaType.valueOf(type.uppercase()),
          source = MediaSource.valueOf(source.uppercase()),
          contentUri = content_uri,
          originalFilename = original_filename,
          capturedAt = captured_at,
          timestampOffset = timestamp_offset.toInt(),
          originalLat = original_lat,
          originalLng = original_lng,
          inferredLat = inferred_lat,
          inferredLng = inferred_lng,
          locationSource = location_source?.let { LocationSource.valueOf(it.uppercase()) },
          matchedSessionId = matched_session_id,
          matchedTrackpointId = matched_trackpoint_id
      )
  }
  ```

  Create `shared/src/commonMain/kotlin/com/triplens/repository/NoteRepository.kt`:
  ```kotlin
  package com.triplens.repository

  import com.triplens.db.TripLensDatabase
  import com.triplens.model.Note
  import com.triplens.model.NoteType

  class NoteRepository(private val db: TripLensDatabase) {

      fun createTextNote(
          id: String, sessionId: String, content: String,
          createdAt: Long, latitude: Double, longitude: Double
      ) {
          db.noteQueries.insert(id, sessionId, "text", content, null, null, createdAt, latitude, longitude)
      }

      fun createVoiceNote(
          id: String, sessionId: String, audioFilename: String,
          durationSeconds: Int, createdAt: Long, latitude: Double, longitude: Double
      ) {
          db.noteQueries.insert(
              id, sessionId, "voice", null, audioFilename,
              durationSeconds.toLong(), createdAt, latitude, longitude
          )
      }

      fun getBySession(sessionId: String): List<Note> =
          db.noteQueries.getBySessionId(sessionId).executeAsList().map { it.toModel() }

      fun delete(id: String) {
          db.noteQueries.delete(id)
      }

      private fun com.triplens.db.Note.toModel() = Note(
          id = id,
          sessionId = session_id,
          type = NoteType.valueOf(type.uppercase()),
          content = content,
          audioFilename = audio_filename,
          durationSeconds = duration_seconds?.toInt(),
          createdAt = created_at,
          latitude = latitude,
          longitude = longitude
      )
  }
  ```

- [x] **Step 5: Run `RepositoryTest` — confirm all pass**

  Expected: 8 tests pass.

- [x] **Step 6: Commit**

  ```bash
  git add shared/src/commonMain/kotlin/com/triplens/repository/ \
          shared/src/commonTest/kotlin/com/triplens/repository/
  git commit -m "feat(data): add repository layer with in-memory SQLite tests"
  ```

---

## Task 5: TransportClassifier + SegmentSmoother

**Files:**
- Create: `shared/src/commonMain/kotlin/com/triplens/domain/TransportClassifier.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/domain/Segment.kt`
- Create: `shared/src/commonMain/kotlin/com/triplens/domain/SegmentSmoother.kt`
- Create: `shared/src/commonTest/kotlin/com/triplens/domain/TransportClassifierTest.kt`
- Create: `shared/src/commonTest/kotlin/com/triplens/domain/SegmentSmootherTest.kt`

### Step-by-step

- [x] **Step 1: Write failing `TransportClassifierTest`**

  Create `shared/src/commonTest/kotlin/com/triplens/domain/TransportClassifierTest.kt`:

  ```kotlin
  package com.triplens.domain

  import com.triplens.model.TransportMode
  import kotlin.test.Test
  import kotlin.test.assertEquals

  class TransportClassifierTest {

      // Speed thresholds from TDD Section 6.1:
      // stationary: < 1.0 km/h
      // walking:    1.0–5.9
      // cycling:    6.0–19.9
      // driving:    20.0–119.9
      // fast_transit: >= 120.0

      @Test fun below1_isStationary()     = check(0.0,   TransportMode.STATIONARY)
      @Test fun exactly0_isStationary()   = check(0.0,   TransportMode.STATIONARY)
      @Test fun justBelow1_isStationary() = check(0.9,   TransportMode.STATIONARY)
      @Test fun exactly1_isWalking()      = check(1.0,   TransportMode.WALKING)
      @Test fun justAbove1_isWalking()    = check(1.1,   TransportMode.WALKING)
      @Test fun justBelow6_isWalking()    = check(5.9,   TransportMode.WALKING)
      @Test fun exactly6_isCycling()      = check(6.0,   TransportMode.CYCLING)
      @Test fun justAbove6_isCycling()    = check(6.1,   TransportMode.CYCLING)
      @Test fun justBelow20_isCycling()   = check(19.9,  TransportMode.CYCLING)
      @Test fun exactly20_isDriving()     = check(20.0,  TransportMode.DRIVING)
      @Test fun justAbove20_isDriving()   = check(20.1,  TransportMode.DRIVING)
      @Test fun justBelow120_isDriving()  = check(119.9, TransportMode.DRIVING)
      @Test fun exactly120_isFastTransit() = check(120.0, TransportMode.FAST_TRANSIT)
      @Test fun above120_isFastTransit()  = check(120.1, TransportMode.FAST_TRANSIT)
      @Test fun veryHigh_isFastTransit()  = check(999.0, TransportMode.FAST_TRANSIT)

      @Test
      fun negative_speed_treatedAsStationary() {
          // GPS occasionally returns negative speed values; treat as zero
          assertEquals(TransportMode.STATIONARY, TransportClassifier.classify(-5.0))
      }

      private fun check(speedKmh: Double, expected: TransportMode) =
          assertEquals(expected, TransportClassifier.classify(speedKmh))
  }
  ```

- [x] **Step 2: Run test — confirm compilation errors**

- [x] **Step 3: Implement `TransportClassifier`**

  Create `shared/src/commonMain/kotlin/com/triplens/domain/TransportClassifier.kt`:

  ```kotlin
  package com.triplens.domain

  import com.triplens.model.TransportMode

  /**
   * Classifies a GPS speed reading into a transport mode.
   *
   * Algorithm: simple threshold comparison on km/h. Thresholds from TDD Section 6.1.
   * - < 1.0  → STATIONARY  (person standing still, waiting)
   * - 1–6    → WALKING      (brisk walk up to ~5.9 km/h)
   * - 6–20   → CYCLING      (slow bike to ~19.9 km/h)
   * - 20–120 → DRIVING      (car, bus, slow train)
   * - ≥ 120  → FAST_TRANSIT (high-speed rail, plane)
   *
   * Negative speed (GPS artifact) is treated as stationary.
   */
  object TransportClassifier {

      private const val WALKING_MIN    = 1.0
      private const val CYCLING_MIN    = 6.0
      private const val DRIVING_MIN    = 20.0
      private const val FAST_TRANSIT_MIN = 120.0

      fun classify(speedKmh: Double): TransportMode = when {
          speedKmh < WALKING_MIN      -> TransportMode.STATIONARY
          speedKmh < CYCLING_MIN      -> TransportMode.WALKING
          speedKmh < DRIVING_MIN      -> TransportMode.CYCLING
          speedKmh < FAST_TRANSIT_MIN -> TransportMode.DRIVING
          else                        -> TransportMode.FAST_TRANSIT
      }
  }
  ```

- [x] **Step 4: Run `TransportClassifierTest` — all 16 tests pass**

- [x] **Step 5: Write failing `SegmentSmootherTest`**

  Create `shared/src/commonTest/kotlin/com/triplens/domain/SegmentSmootherTest.kt`:

  ```kotlin
  package com.triplens.domain

  import com.triplens.model.TrackPoint
  import com.triplens.model.TransportMode
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class SegmentSmootherTest {

      // Helper: build a minimal TrackPoint. IDs are sequential, timestamps 1s apart.
      private fun points(vararg modes: TransportMode): List<TrackPoint> =
          modes.mapIndexed { i, mode ->
              TrackPoint(
                  id = i.toLong(),
                  sessionId = "s1",
                  timestamp = i * 1_000L,
                  latitude = -41.28 + i * 0.0001,
                  longitude = 174.77,
                  altitude = null,
                  accuracy = 8f,
                  speed = null,
                  transportMode = mode
              )
          }

      @Test
      fun emptyList_returnsEmpty() {
          assertTrue(SegmentSmoother.smooth(emptyList(), minSegmentPoints = 3).isEmpty())
      }

      @Test
      fun singlePoint_returnsOneSegment() {
          val result = SegmentSmoother.smooth(points(TransportMode.WALKING), minSegmentPoints = 3)
          assertEquals(1, result.size)
          assertEquals(TransportMode.WALKING, result[0].mode)
      }

      @Test
      fun allSameMode_returnsOneSegment() {
          val input = points(
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.DRIVING, TransportMode.DRIVING
          )
          val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
          assertEquals(1, result.size)
          assertEquals(TransportMode.DRIVING, result[0].mode)
          assertEquals(5, result[0].pointCount)
      }

      @Test
      fun shortNoise_betweenSameMode_isAbsorbed() {
          // 5 driving, 1 cycling (noise), 5 driving → should produce 1 driving segment
          val input = points(
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.CYCLING,  // 1 point noise — less than minSegmentPoints=3
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.DRIVING, TransportMode.DRIVING
          )
          val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
          assertEquals(1, result.size)
          assertEquals(TransportMode.DRIVING, result[0].mode)
      }

      @Test
      fun shortNoise_atStart_isKept() {
          // 1 cycling at start, then 5 driving — no same-mode neighbor on left, keep as-is
          val input = points(
              TransportMode.CYCLING,
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.DRIVING, TransportMode.DRIVING
          )
          val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
          assertEquals(2, result.size)
          assertEquals(TransportMode.CYCLING, result[0].mode)
          assertEquals(TransportMode.DRIVING, result[1].mode)
      }

      @Test
      fun shortNoise_atEnd_isKept() {
          // 5 driving then 1 cycling at end — no same-mode neighbor on right, keep as-is
          val input = points(
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.CYCLING
          )
          val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
          assertEquals(2, result.size)
          assertEquals(TransportMode.DRIVING, result[0].mode)
          assertEquals(TransportMode.CYCLING, result[1].mode)
      }

      @Test
      fun alternatingLongSegments_noSmoothing() {
          // 4 walking, 4 driving, 4 walking — all exceed minSegmentPoints, no absorption
          val input = points(
              TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING,
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING,
              TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING, TransportMode.WALKING
          )
          val result = SegmentSmoother.smooth(input, minSegmentPoints = 3)
          assertEquals(3, result.size)
          assertEquals(TransportMode.WALKING, result[0].mode)
          assertEquals(TransportMode.DRIVING, result[1].mode)
          assertEquals(TransportMode.WALKING, result[2].mode)
      }

      @Test
      fun segment_pointCount_matchesExpected() {
          val input = points(
              TransportMode.WALKING, TransportMode.WALKING,
              TransportMode.DRIVING, TransportMode.DRIVING, TransportMode.DRIVING
          )
          val result = SegmentSmoother.smooth(input, minSegmentPoints = 1)
          assertEquals(2, result[0].pointCount)
          assertEquals(3, result[1].pointCount)
      }
  }
  ```

- [x] **Step 6: Run test — confirm compilation errors (no Segment/SegmentSmoother yet)**

- [x] **Step 7: Create `Segment` data class**

  Create `shared/src/commonMain/kotlin/com/triplens/domain/Segment.kt`:
  ```kotlin
  package com.triplens.domain

  import com.triplens.model.TransportMode

  /**
   * A contiguous run of TrackPoints sharing the same transport mode, after noise smoothing.
   * Used for map rendering (colored polylines) and the session timeline.
   */
  data class Segment(
      val mode: TransportMode,
      val startTimestamp: Long,
      val endTimestamp: Long,
      val startLat: Double,
      val startLng: Double,
      val endLat: Double,
      val endLng: Double,
      val pointCount: Int,
      val distanceMeters: Double,  // haversine sum of consecutive point pairs
      val durationSeconds: Long
  )
  ```

- [x] **Step 8: Implement `SegmentSmoother`**

  Create `shared/src/commonMain/kotlin/com/triplens/domain/SegmentSmoother.kt`:

  ```kotlin
  package com.triplens.domain

  import com.triplens.model.TrackPoint
  import com.triplens.model.TransportMode
  import kotlin.math.*

  /**
   * Smooths raw per-point transport mode classifications into display-ready segments.
   *
   * Algorithm (TDD Section 6.2):
   * 1. Group consecutive points with the same mode into raw segments.
   * 2. For each segment with fewer than [minSegmentPoints] points:
   *    - If the segment before AND after it share the same mode, absorb the segment
   *      into that surrounding mode (GPS noise elimination).
   *    - If the noise segment is at the start or end (no neighbor on one side), keep it.
   * 3. After absorption, re-group into final Segment objects with computed stats.
   *
   * Smoothing runs at display time only. Raw per-point modes are preserved in the DB.
   *
   * @param points Ordered list of TrackPoints for a session (ascending timestamp).
   * @param minSegmentPoints Segments with fewer points than this are candidates for absorption.
   *                         For STANDARD accuracy profile (8s interval), use 3 (~24 seconds).
   */
  object SegmentSmoother {

      fun smooth(points: List<TrackPoint>, minSegmentPoints: Int): List<Segment> {
          if (points.isEmpty()) return emptyList()

          // Step 1: group into raw (mode, list-of-points) pairs
          val rawGroups = mutableListOf<Pair<TransportMode, MutableList<TrackPoint>>>()
          for (point in points) {
              if (rawGroups.isEmpty() || rawGroups.last().first != point.transportMode) {
                  rawGroups.add(point.transportMode to mutableListOf(point))
              } else {
                  rawGroups.last().second.add(point)
              }
          }

          // Step 2: absorb noise segments flanked on both sides by the same mode
          val smoothed = rawGroups.toMutableList()
          var changed = true
          while (changed) {
              changed = false
              val iter = smoothed.listIterator()
              val result = mutableListOf<Pair<TransportMode, MutableList<TrackPoint>>>()
              var i = 0
              while (i < smoothed.size) {
                  val (mode, pts) = smoothed[i]
                  val prevMode = if (i > 0) smoothed[i - 1].first else null
                  val nextMode = if (i < smoothed.size - 1) smoothed[i + 1].first else null

                  if (pts.size < minSegmentPoints && prevMode != null && nextMode != null && prevMode == nextMode) {
                      // Absorb into surrounding mode by merging with the previous group
                      result.last().second.addAll(pts)
                      changed = true
                  } else {
                      result.add(mode to pts.toMutableList())
                  }
                  i++
              }
              smoothed.clear()
              smoothed.addAll(result)
          }

          // Step 3: build Segment objects
          return smoothed.map { (mode, pts) ->
              val first = pts.first()
              val last = pts.last()
              val distance = computeDistance(pts)
              Segment(
                  mode = mode,
                  startTimestamp = first.timestamp,
                  endTimestamp = last.timestamp,
                  startLat = first.latitude,
                  startLng = first.longitude,
                  endLat = last.latitude,
                  endLng = last.longitude,
                  pointCount = pts.size,
                  distanceMeters = distance,
                  durationSeconds = (last.timestamp - first.timestamp) / 1_000L
              )
          }
      }

      /**
       * Haversine distance sum over consecutive point pairs, in metres.
       * Earth radius = 6,371,000 m (spherical approximation; sufficient for display).
       */
      private fun computeDistance(points: List<TrackPoint>): Double {
          if (points.size < 2) return 0.0
          var total = 0.0
          for (i in 1 until points.size) {
              total += haversine(
                  points[i - 1].latitude, points[i - 1].longitude,
                  points[i].latitude, points[i].longitude
              )
          }
          return total
      }

      private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
          val r = 6_371_000.0
          val dLat = Math.toRadians(lat2 - lat1)
          val dLon = Math.toRadians(lon2 - lon1)
          val a = sin(dLat / 2).pow(2) +
                  cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
          return r * 2 * atan2(sqrt(a), sqrt(1 - a))
      }
  }
  ```

- [x] **Step 9: Run `SegmentSmootherTest` — all 8 tests pass**

- [x] **Step 10: Commit**

  ```bash
  git add shared/src/commonMain/kotlin/com/triplens/domain/ \
          shared/src/commonTest/kotlin/com/triplens/domain/
  git commit -m "feat(domain): add TransportClassifier and SegmentSmoother with full unit tests"
  ```

---

## Summary

| Task | Output | Tests |
|------|--------|-------|
| 1 | KMP Gradle project, all deps, AndroidManifest | Gradle sync + empty app launch |
| 2 | 10 model files (data classes + enums) | 7 serialization tests |
| 3 | 5 SQLDelight `.sq` files, DB wrapper, drivers | 8 schema tests |
| 4 | 5 repository classes | 8 repository tests |
| 5 | TransportClassifier, SegmentSmoother, Segment | 16 classifier + 8 smoother tests |

All tests in Tasks 2–5 run on the JVM with no Android emulator.
