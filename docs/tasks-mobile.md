# TripLens Mobile App — Task Breakdown

> Based on: TripLens TDD v1.0 (Mobile sections 1–8, 12–15)
> Platform: Android (KMP, Kotlin + Compose Multiplatform)
> Order: implementation order (each task depends only on tasks above it)

---

## Format

Each task entry:
- **Goal** — one sentence, what is delivered when the task is done
- **Depends on** — task numbers that must be complete first
- **Scope** — files / classes / objects created or significantly modified
- **Tests to propose** — test cases to discuss with the human owner before writing any code

---

## Phase 0 — Project Scaffold

### Task 1: KMP Project Initialization

**Goal**: Create the Gradle project with `shared/` and `androidApp/` modules, all dependency declarations, and a compiling but empty app shell.

**Depends on**: nothing

**Scope**:
- `build.gradle.kts` (root)
- `gradle/libs.versions.toml` — version catalog for all dependencies (KMP 2.1.x, Compose Multiplatform 1.7.x, SQLDelight 2.0.x, Koin 4.0.x, MapLibre 11.x, ramani-maps, Coil 3.x, Navigation Compose 2.8.x, Kotlinx Serialization 1.7.x, Kotlinx Coroutines 1.9.x, Google Play Services Location 21.x)
- `shared/build.gradle.kts` — KMP module with `commonMain`, `androidMain`, `iosMain` source sets declared
- `androidApp/build.gradle.kts`
- `androidApp/src/main/AndroidManifest.xml` — all permissions declared (not yet requested at runtime): `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES`, `ACCESS_MEDIA_LOCATION`
- `androidApp/src/main/java/.../MainActivity.kt` — empty single-activity shell
- `.gitignore` additions for Android/Gradle build artifacts

**Tests to propose**: none (scaffold only — verified by successful Gradle sync and empty app launching on emulator)

---

## Phase 1 — Data Layer (commonMain)

### Task 2: Core Data Models

**Goal**: Define all Kotlin data classes and enums in `commonMain` that represent the full domain — no database or platform dependencies.

**Depends on**: Task 1

**Scope** (all in `shared/src/commonMain/kotlin/.../model/`):
- `TripGroup.kt`
- `Session.kt`
- `TrackPoint.kt`
- `MediaReference.kt`
- `Note.kt`
- `TransportMode.kt` (enum: STATIONARY, WALKING, CYCLING, DRIVING, FAST_TRANSIT)
- `SessionStatus.kt` (enum: RECORDING, COMPLETED, INTERRUPTED)
- `MediaType.kt` (enum: PHOTO, VIDEO)
- `MediaSource.kt` (enum: PHONE_GALLERY, EXTERNAL_CAMERA)
- `LocationSource.kt` (enum: EXIF, TRAJECTORY)

**Tests to propose**:
- Serialize/deserialize each model to JSON (via Kotlinx Serialization) and verify round-trip equality
- Verify enum values match the exact string values used in the SQLite CHECK constraints and index.json schema (e.g., `"walking"` not `"WALKING"`)

---

### Task 3: SQLDelight Database Schema

**Goal**: Define all 5 SQLite tables in `.sq` files and verify that SQLDelight generates correct, type-safe Kotlin query classes.

**Depends on**: Task 2

**Scope**:
- `shared/src/commonMain/sqldelight/schema.sq` — all 5 tables (`trip_group`, `session`, `track_point`, `media_reference`, `note`) with CHECK constraints, foreign keys, and indexes as specified in TDD Section 3.1
- `shared/src/commonMain/sqldelight/TripGroupQueries.sq` — `insert`, `getById`, `getAll`, `updateName`, `delete`
- `shared/src/commonMain/sqldelight/SessionQueries.sq` — `insert`, `getById`, `getByGroupId`, `updateStatus`, `setEndTime`, `delete`
- `shared/src/commonMain/sqldelight/TrackPointQueries.sq` — `insert`, `getBySessionId`, `getBySessionIdAndTimeRange`, `countBySession`
- `shared/src/commonMain/sqldelight/MediaRefQueries.sq` — `insert`, `getBySessionId`, `getByContentUri`, `updateInferredLocation`
- `shared/src/commonMain/sqldelight/NoteQueries.sq` — `insert`, `getBySessionId`, `getVoiceNotesBySession`, `delete`
- `shared/src/androidMain/kotlin/.../db/DatabaseDriverFactory.kt` — `actual` implementation using `AndroidSqliteDriver`
- `shared/src/commonMain/kotlin/.../db/DatabaseDriverFactory.kt` — `expect` declaration
- `shared/src/commonMain/kotlin/.../db/TripLensDatabase.kt` — database factory wrapper, enables WAL mode (`PRAGMA journal_mode=WAL`), runs `PRAGMA integrity_check` on open

**Tests to propose**:
- Each table: insert a row and query it back; verify all fields round-trip correctly (especially nullable fields like `end_time`, `altitude`, `inferred_lat/lng`)
- Foreign key cascade: delete a `session` and verify its `track_point` and `note` rows are also deleted
- CHECK constraint violation: attempt to insert a `session` with an invalid `status` value and verify it throws
- `integrity_check` returns `"ok"` for a freshly created database

---

### Task 4: Repository Layer

**Goal**: Create repository classes in `commonMain` that wrap SQLDelight queries and expose clean, suspending Kotlin APIs for the domain layer.

**Depends on**: Task 3

**Scope** (all in `shared/src/commonMain/kotlin/.../repository/`):
- `TripRepository.kt` — `createGroup()`, `renameGroup()`, `deleteGroup()`, `getAllGroups()`, `getGroupById()`
- `SessionRepository.kt` — `createSession()`, `completeSession()`, `markInterrupted()`, `getActiveSession()`, `getSessionsByGroup()`
- `TrackPointRepository.kt` — `insert()`, `insertBatch()` (single transaction for up to 10 buffered points), `getBySession()`, `getInRange()`
- `MediaRefRepository.kt` — `insertIfNotExists()` (deduplication by `content_uri`), `getBySession()`, `updateInferredLocation()`
- `NoteRepository.kt` — `createTextNote()`, `createVoiceNote()`, `getBySession()`, `delete()`

**Tests to propose**:
- Use the in-memory SQLDelight driver (JVM) — no Android emulator required
- `TripRepository`: create a group, fetch all, verify count; rename, verify new name; delete, verify cascades
- `SessionRepository.getActiveSession()`: returns null when no session is recording; returns the session when one is in RECORDING status
- `TrackPointRepository.insertBatch()`: insert 10 points in one call, verify all 10 are persisted in a single DB transaction (mock/wrap the driver to assert transaction count)
- `MediaRefRepository.insertIfNotExists()`: calling twice with the same `content_uri` results in only one row
- `NoteRepository`: create text note and voice note, query by session, verify type and content

---

### Task 5: TransportClassifier + Segment Smoothing

**Goal**: Implement and fully test the two pure-Kotlin algorithms that classify GPS speed into transport modes and smooth noisy mode transitions for display.

**Depends on**: Task 2

**Scope** (all in `shared/src/commonMain/kotlin/.../domain/`):
- `TransportClassifier.kt` — `fun classify(speedKmh: Double): TransportMode` using thresholds from TDD Section 6.1
- `SegmentSmoother.kt` — `fun smooth(points: List<TrackPoint>, minSegmentPoints: Int): List<Segment>` — groups consecutive same-mode points, absorbs short noise segments (< N points flanked by same mode), returns display-ready `Segment` objects (mode, start/end time, start/end coords, distance, duration)
- `Segment.kt` data class

**Tests to propose**:
- `TransportClassifier`: exact boundary values — 0.0, 0.9, 1.0, 1.1, 5.9, 6.0, 6.1, 19.9, 20.0, 20.1, 119.9, 120.0, 120.1, 999.0 km/h
- `TransportClassifier`: negative speed (GPS can return negative) → treat as stationary
- `SegmentSmoother`: all-same-mode list → one segment
- `SegmentSmoother`: alternating modes → no smoothing applied (each segment exceeds min size)
- `SegmentSmoother`: one short "cycling" point between two long "driving" segments → absorbed into driving
- `SegmentSmoother`: short segment at the start or end (not flanked on both sides) → kept as-is
- `SegmentSmoother`: empty list → empty result
- `SegmentSmoother`: single point → one segment

---

## Phase 2 — Platform Services (androidMain)

### Task 6: LocationTrackingService (ForegroundService)

**Goal**: Implement the Android ForegroundService that owns the GPS recording lifecycle, including dynamic interval switching, START_STICKY recovery, and session persistence to SharedPreferences.

**Depends on**: Tasks 4, 5

**Scope**:
- `androidApp/src/main/java/.../service/LocationTrackingService.kt` — extends `Service`, uses `FusedLocationProviderClient`, calls `TransportClassifier.classify()`, writes TrackPoints via `TrackPointRepository`, buffers up to 10 points before flushing in one transaction
- `AccuracyProfile.kt` (enum: STANDARD, HIGH, BATTERY_SAVER with interval and priority values from TDD Section 4.2)
- `LocationProvider.kt` (`expect` interface in `commonMain`) + `AndroidLocationProvider.kt` (`actual` in `androidMain`)
- SharedPreferences keys: `active_session_id`, `accuracy_profile` — read on `onStartCommand` when intent is null (system restart)
- Auto-pause logic: after 3h stationary, switch to 1 fix/5min; resume on movement — add `is_auto_paused` column to `track_point` schema (requires schema migration in Task 3's `.sq` file)

**Tests to propose**:
- `AccuracyProfile`: verify interval and priority values for each profile match TDD spec
- Service lifecycle (Android instrumented): start service with a session ID → verify foreground notification appears → stop service → verify notification disappears
- Recovery: start service, persist session ID to SharedPreferences, stop service, restart with null intent → verify it reads session ID from SharedPreferences and continues
- Auto-pause trigger: inject 180 consecutive stationary TrackPoints (3h × 60s interval) → verify service enters auto-pause state and switches to 5min interval
- Auto-pause resume: while in auto-pause, inject a moving point → verify service exits auto-pause and restores normal interval

---

### Task 7: GalleryScanner

**Goal**: Implement the MediaStore-based gallery scanner that detects new phone photos and videos since session start, reads EXIF location, and deduplicates by `content_uri`.

**Depends on**: Task 4

**Scope**:
- `GalleryScanner.kt` (`expect` interface in `commonMain`)
- `androidApp/src/main/.../AndroidGalleryScanner.kt` (`actual`) — queries `MediaStore.Images.Media` and `MediaStore.Video.Media` with `DATE_TAKEN > sessionStartTime AND DATE_TAKEN > lastScanTimestamp`; reads `LATITUDE`/`LONGITUDE` columns for phone photos; builds `ScannedMedia` objects; tracks `lastScanTimestamp`
- `ScannedMedia.kt` data class (content_uri, filename, capturedAt, originalLat, originalLng)
- `ACCESS_MEDIA_LOCATION` handling: if not granted, `originalLat/Lng` = null (location will be inferred from trajectory)

**Tests to propose**:
- Android instrumented: insert a test photo into MediaStore with a known `DATE_TAKEN` → call `scanNewMedia(sessionStartTime)` → verify photo appears in results with correct `content_uri` and filename
- Photos taken before `sessionStartTime` → do not appear in results
- Calling scanner twice without new photos → second call returns empty list (deduplication via `lastScanTimestamp`)
- Photo with GPS in EXIF → `originalLat/Lng` populated
- Photo without GPS in EXIF → `originalLat/Lng` null
- Video files are also returned (not just photos)

---

### Task 8: AudioRecorder

**Goal**: Implement the voice note recorder that produces M4A (AAC-LC, 64kbps, mono) files in app-private storage.

**Depends on**: Task 1 (project structure only)

**Scope**:
- `AudioRecorder.kt` (`expect` interface in `commonMain`) with `start()`, `stop(): String` (returns file path), `cancel()`
- `androidApp/src/main/.../AndroidAudioRecorder.kt` (`actual`) — uses `android.media.MediaRecorder`; output format `MPEG_4`, audio encoder `AAC`, bit rate 64000, channel count 1; saves to `{filesDir}/notes/{uuid}.m4a`

**Tests to propose**:
- Android instrumented: call `start()`, wait 2 seconds, call `stop()` → verify returned file path exists, file size > 0
- `cancel()` after `start()` → file is deleted, `stop()` subsequently throws or is a no-op (no orphan files)
- `stop()` without `start()` → throws `IllegalStateException` with clear message
- Output file is a valid M4A container (verify magic bytes: `ftyp` box at offset 4)

---

## Phase 3 — Domain / Use Cases (commonMain)

### Task 9: Koin Dependency Injection Setup

**Goal**: Wire all repositories, use cases, and platform services into Koin modules; verify the full DI graph resolves without errors.

**Depends on**: Tasks 4, 6, 7, 8

**Scope**:
- `shared/src/commonMain/kotlin/.../di/SharedModule.kt` — Koin `module { }` for: `TripLensDatabase`, all repositories, `ExportUseCase` (stub for now), `TransportClassifier`
- `androidApp/src/main/java/.../di/AndroidModule.kt` — Koin `module { }` for: `AndroidLocationProvider`, `AndroidGalleryScanner`, `AndroidAudioRecorder`
- `androidApp/src/main/java/.../TripLensApplication.kt` — `Application` subclass, calls `startKoin { modules(sharedModule, androidModule) }`
- `AndroidManifest.xml` — `android:name=".TripLensApplication"`

**Tests to propose**:
- JVM unit test: call `koin.checkModules()` (Koin's built-in DI graph verification) on `sharedModule` with mocked platform dependencies → verifies all declared bindings resolve without runtime errors
- Verify `TripRepository` injected into a test component receives the same `TripLensDatabase` singleton (not a new instance)

---

### Task 10: Export Use Case — index.json + GPX Writer

**Goal**: Implement the full export pipeline: serialize a TripGroup to `index.json` (v1 schema with compact track keys), generate GPX 1.1 files per session, copy voice note files, and zip everything into a `.triplens` archive.

**Depends on**: Tasks 4, 9

**Scope**:
- `PlatformFileSystem.kt` (`expect` in `commonMain`) — `createTempDir()`, `writeText()`, `createDir()`, `copy()`, `zip()`, `deleteRecursive()`, `size()`
- `AndroidFileSystem.kt` (`actual` in `androidMain`)
- `GpxWriter.kt` (pure `commonMain`) — serializes `Session` + `List<TrackPoint>` to GPX 1.1 XML string with `<triplens:speed>`, `<triplens:accuracy>`, `<triplens:mode>` extensions
- `IndexJsonBuilder.kt` (pure `commonMain`) — serializes `TripGroup` + sessions to `index.json` v1 schema: compact track keys (`t`, `lat`, `lng`, `alt`, `acc`, `spd`, `mode`), ISO 8601 timestamps for non-track fields, `track_summary` block
- `ExportUseCase.kt` (`commonMain`) — orchestrates the 8-step pipeline from TDD Section 8.1; logs entry/exit of each step
- `ExportResult.kt` data class (path, sizeBytes)
- `README.txt` content constant

**Tests to propose**:
- `GpxWriter`: single-session, 3-point track → verify output is valid XML, has correct `<trkpt>` elements with lat/lng/ele/time, has `<triplens:mode>` extensions
- `GpxWriter`: session with no track points → valid GPX with empty `<trkseg>`
- `IndexJsonBuilder`: verify compact key names (`t` not `timestamp`, `lat` not `latitude`, etc.)
- `IndexJsonBuilder`: verify `track_summary.distance_meters` is the haversine sum of all consecutive point pairs
- `IndexJsonBuilder`: voice note has `audio_file` field pointing to correct relative path; text note has `content` field
- `ExportUseCase` (with fake `PlatformFileSystem`): create a TripGroup with 2 sessions and 1 voice note → call `export()` → verify `writeText` was called for `index.json` and both GPX files → verify `copy` was called for the voice note → verify `zip` was called once
- Full round-trip (Android instrumented): export real data → unzip the result → parse `index.json` → verify session count, point count, note count match the source data → parse GPX → verify it is valid GPX 1.1

---

## Phase 4 — App Navigation

### Task 11: Navigation Graph + App Shell

**Goal**: Set up Compose Navigation with all routes, dynamic start destination, and the conditional bottom navigation bar.

**Depends on**: Task 9

**Scope**:
- `androidApp/src/main/java/.../MainActivity.kt` — hosts `NavHost`
- `androidApp/src/main/java/.../navigation/AppNavGraph.kt` — routes: `tripList`, `recording`, `settings`, `tripDetail/{groupId}`, `sessionReview/{sessionId}`
- `androidApp/src/main/java/.../navigation/BottomNavBar.kt` — shows on top-level destinations only; "Recording" tab highlighted/pulsing when a session is active
- Dynamic start: on app open, query `SessionRepository.getActiveSession()` → if non-null → start at `recording`, else → start at `tripList`
- Stub composable screens (empty `Box`) for each route so the nav graph compiles

**Tests to propose**:
- `AppViewModel` (or wherever the dynamic start logic lives): when `getActiveSession()` returns null → `startDestination == "tripList"`; when it returns a session → `startDestination == "recording"`
- Navigation: from `tripList` tap a group → navigates to `tripDetail/{groupId}` with correct argument
- Back stack: `sessionReview` → back → returns to `tripDetail`, not `tripList`

---

## Phase 5 — UI Screens

### Task 12: Recording Screen — Idle State + Permission Flow

**Goal**: Implement the idle recording screen (the "Start Recording" button state), the full runtime permission request flow, and the TripGroup selector / creator dialog.

**Depends on**: Tasks 4, 11

**Scope**:
- `androidApp/src/main/java/.../ui/recording/RecordingViewModel.kt` — idle state machine: `Idle`, `RequestingPermissions`, `SelectingGroup`, `CreatingGroup`, `StartingSession`
- `androidApp/src/main/java/.../ui/recording/RecordingScreen.kt` — idle state UI: large circular "Start Recording" button, gear icon
- `androidApp/src/main/java/.../ui/recording/PermissionRationaleDialog.kt` — explains why "Always Allow" is required for background location; includes "Open Settings" button for permanently denied case
- `androidApp/src/main/java/.../ui/recording/GroupSelectorDialog.kt` — lists existing TripGroups + "Create New Trip" option; on creation: default name `"YYYY-MM-DD"` (city appended later via reverse geocode)
- Permission checks: `ACCESS_FINE_LOCATION` (required), `ACCESS_BACKGROUND_LOCATION` (required, separate rationale), `RECORD_AUDIO` (required for voice notes), `READ_MEDIA_IMAGES` + `ACCESS_MEDIA_LOCATION` (required for gallery scan)

**Tests to propose**:
- `RecordingViewModel`: initial state is `Idle`
- `RecordingViewModel`: `onStartTapped()` with all permissions granted → transitions to `SelectingGroup`
- `RecordingViewModel`: `onStartTapped()` with location permission denied → transitions to `RequestingPermissions`
- `RecordingViewModel`: `onGroupSelected(existingGroupId)` → transitions to `StartingSession`, starts `LocationTrackingService`, creates a new Session, navigates to active recording state
- `RecordingViewModel`: `onCreateNewGroup(name)` → creates TripGroup in DB, then transitions to `StartingSession`
- `RecordingViewModel`: if `ACCESS_BACKGROUND_LOCATION` is permanently denied → state includes a flag that triggers "Open Settings" UI instead of the rationale dialog

---

### Task 13: Recording Screen — Active State + Map

**Goal**: Implement the active recording screen: real-time map with colored polyline, media markers, bottom panel with note buttons and media strip, and the stop recording flow.

**Depends on**: Tasks 6, 7, 8, 12

**Scope**:
- `RecordingViewModel.kt` — active state: `Recording(session, trackPoints, recentMedia)`, observes DB for new TrackPoints (Flow) and new MediaReferences (Flow)
- MapLibre + ramani-maps integration in `RecordingScreen.kt`:
  - Colored polyline by transport mode (stationary=gray, walking=green, cycling=orange, driving=blue, fast_transit=purple)
  - Media markers: photo thumbnail circles, voice note icon, text note icon at GPS positions
  - Blue dot for current position with accuracy circle
  - Camera auto-follow with a re-center button (shown only after user pans away)
  - Polyline updates throttled to every 5 seconds (not every GPS fix)
- `RecordingScreen.kt` active state layout: top bar (group name, timer with pulsing red dot, Stop button), map (~60%), bottom panel (~40%: Text Note button, Voice Note button, media strip)
- Text Note bottom sheet: text input field + Save button
- Voice Note: tap to start (button transforms to pulsing red + elapsed time display), tap again to stop
- Stop confirmation dialog: "End this session?" with End / Cancel options
- On session end: update DB, stop `LocationTrackingService`, navigate to `tripList`

**Tests to propose**:
- `RecordingViewModel`: new TrackPoint emitted from DB → `trackPoints` state list updates
- `RecordingViewModel`: new MediaReference emitted from DB → `recentMedia` list prepends the new item, capped at 10
- `RecordingViewModel`: `onSaveTextNote(text)` → creates a Note in DB with correct timestamp and current GPS location
- `RecordingViewModel`: `onStopConfirmed()` → session `status` set to `completed`, `end_time` set, service stopped
- `RecordingViewModel`: timer increments — unit test with a fake clock advancing by 1 second intervals

---

### Task 14: Trip List Screen + TripGroup Detail Screen

**Goal**: Implement the Trip List screen (TripGroup cards with stats and trajectory thumbnail) and the TripGroup Detail screen (session list with transport breakdown).

**Depends on**: Tasks 4, 11

**Scope**:
- `androidApp/src/main/java/.../ui/triplist/TripListViewModel.kt` — loads all TripGroups with aggregated stats (session count, total distance, total duration, photo/video/note counts)
- `androidApp/src/main/java/.../ui/triplist/TripListScreen.kt` — sorted list of TripGroup cards; each card: name (inline rename on tap), date range, stats summary, trajectory thumbnail (static mini-map via MapLibre snapshot or simple Canvas polyline), photo/video/note icon counts
- Long-press / swipe actions on TripGroup card: Rename (inline), Delete (confirmation dialog), Export (triggers `ExportUseCase`)
- `androidApp/src/main/java/.../ui/tripdetail/TripDetailViewModel.kt` — loads sessions for a group with transport mode breakdown per session
- `androidApp/src/main/java/.../ui/tripdetail/TripDetailScreen.kt` — header with group stats; session list: each session shows name (editable), date+time range, duration+distance, transport breakdown icons (e.g. "🚶 2.3km 🚗 45km"); Export FAB

**Tests to propose**:
- `TripListViewModel`: empty DB → `groups` state is empty list
- `TripListViewModel`: 2 groups in DB → `groups` has 2 items with correct names and session counts
- `TripListViewModel`: `onDeleteGroup(id)` → calls `TripRepository.deleteGroup(id)`, group disappears from state
- `TripListViewModel`: `onRenameGroup(id, newName)` → calls `TripRepository.renameGroup(id, newName)`
- `TripDetailViewModel`: transport breakdown for a session with 100 walking points and 50 driving points → shows correct mode distances

---

### Task 15: Session Review Screen

**Goal**: Implement the full-screen session review: interactive map with complete trajectory + media markers, and a scrollable timeline with media preview.

**Depends on**: Tasks 5, 13

**Scope**:
- `androidApp/src/main/java/.../ui/sessionreview/SessionReviewViewModel.kt` — loads session, TrackPoints (with `SegmentSmoother` applied), MediaReferences, Notes; computes stats (distance, duration, transport breakdown)
- `androidApp/src/main/java/.../ui/sessionreview/SessionReviewScreen.kt`:
  - Map area (~50% height): full trajectory polyline (same color scheme as recording screen), media markers; tapping a marker scrolls the timeline to that item
  - Timeline (scrollable, ~50% height): chronological list of events — transport segment cards ("🚶 Walking — 1.2 km, 18 min"), photo thumbnails (tapping opens full-screen photo viewer), voice note items (tapping plays audio), text note items
- `MediaPreviewSheet.kt` — full-screen photo viewer (pinch-to-zoom via Coil), voice note playback (Android `MediaPlayer`), text note display

**Tests to propose**:
- `SessionReviewViewModel`: TrackPoints passed through `SegmentSmoother` → `displaySegments` matches expected smoothed output (reuse Task 5 test data)
- `SessionReviewViewModel`: total distance is haversine sum of all non-auto-paused points
- `SessionReviewViewModel`: total duration excludes auto-paused intervals
- `SessionReviewViewModel`: `onMarkerTapped(mediaId)` → `selectedMediaId` state updates (drives timeline scroll and preview sheet)

---

### Task 16: Settings Screen

**Goal**: Implement the Settings screen with language selection, GPS accuracy profile, and gallery scan interval, all persisted via DataStore.

**Depends on**: Task 11

**Scope**:
- `androidApp/src/main/java/.../data/AppPreferences.kt` — DataStore wrapper with typed accessors for: `language` (SYSTEM / EN / ZH_CN), `accuracyProfile` (STANDARD / HIGH / BATTERY_SAVER), `scanIntervalSeconds` (30 / 60 / 120)
- `androidApp/src/main/java/.../ui/settings/SettingsViewModel.kt` — reads/writes `AppPreferences`
- `androidApp/src/main/java/.../ui/settings/SettingsScreen.kt` — three preference sections, each a row with a label and a chip group or dropdown
- Language change applies immediately via `AppCompatDelegate.setApplicationLocales()` (Android 13+) / AndroidX compat for older versions

**Tests to propose**:
- `AppPreferences`: write `language = ZH_CN`, read back → returns `ZH_CN`; default value is `SYSTEM`
- `AppPreferences`: write `accuracyProfile = HIGH`, read back → `HIGH`; default is `STANDARD`
- `SettingsViewModel`: `onLanguageSelected(ZH_CN)` → writes to DataStore; flow collector receives updated value
- `SettingsViewModel`: `onAccuracyProfileSelected(BATTERY_SAVER)` → if a session is currently recording, the new profile takes effect at the next `LocationRequest` update (verify `LocationTrackingService` is notified via an Intent or broadcast)

---

## Phase 6 — Cross-Cutting Concerns

### Task 17: Localization

**Goal**: Add all English and Simplified Chinese strings, wire up the per-app language setting, and ensure shared strings (used in export and GPX metadata) are also localized.

**Depends on**: Task 16

**Scope**:
- `androidApp/src/main/res/values/strings.xml` — all English UI strings
- `androidApp/src/main/res/values-zh-rCN/strings.xml` — all Simplified Chinese translations
- `shared/src/commonMain/kotlin/.../i18n/Strings.kt` — `expect object Strings` with keys for: `defaultTripNameFormat`, `sessionDefaultName`, `exportReadmeContent`, `gpxCreatorTag`
- `shared/src/androidMain/kotlin/.../i18n/Strings.kt` — `actual object Strings` backed by `context.getString(R.string.*)`
- Language application: in `TripLensApplication.onCreate()` read language preference and call `AppCompatDelegate.setApplicationLocales()`

**Tests to propose**:
- Verify every string key defined in `strings.xml` has a corresponding entry in `strings-zh-rCN/strings.xml` (can be done with a simple file diff test or a custom lint rule)
- `Strings.defaultTripNameFormat` returns a non-empty string in both locales
- Changing language to ZH_CN and then reading any string returns the Chinese version (requires setting the locale in the test)

---

### Task 18: Error Handling & Session Recovery

**Goal**: Implement the interrupted session recovery dialog shown on app open, the DB integrity check on startup, and robust export failure cleanup.

**Depends on**: Tasks 4, 10, 12

**Scope**:
- `androidApp/src/main/java/.../ui/MainActivity.kt` (or `AppViewModel.kt`) — on startup: (1) run `TripLensDatabase.integrityCheck()` — if it fails, rename DB to `.corrupt` and create fresh; (2) check for any session with `status = "recording"` and no running `LocationTrackingService` → if found, show recovery dialog
- `SessionRecoveryDialog.kt` — "Your last recording was interrupted. Resume or discard?" — Resume: create new Session in same TripGroup, start service; Discard: set status to `interrupted`
- `ExportUseCase.kt` — wrap the zip step in try/catch; on failure: delete temp dir, rethrow with a descriptive message; the caller (ViewModel) shows an error snackbar
- DB integrity check path: `TripLensDatabase.kt` — add `fun integrityCheck(): Boolean` using `PRAGMA integrity_check`

**Tests to propose**:
- `AppViewModel` (or startup logic): no recording session in DB → no recovery dialog shown
- `AppViewModel`: session with `status = "recording"` in DB but `LocationTrackingService` not running → `showRecoveryDialog` state is true
- `AppViewModel`: `onRecoveryResume()` → new session created in same group, service started, `showRecoveryDialog` cleared
- `AppViewModel`: `onRecoveryDiscard()` → interrupted session's status updated to `interrupted`, no new session created
- `TripLensDatabase.integrityCheck()`: fresh DB → returns true
- `ExportUseCase`: if `PlatformFileSystem.zip()` throws → temp directory is cleaned up and the exception propagates with a meaningful message

---

## Phase 7 — Export & Sharing

### Task 19: Export Pipeline Integration + Share Sheet

**Goal**: Wire the `ExportUseCase` into the UI, set up `FileProvider` for scoped storage, trigger the Android share sheet, and add export progress indication.

**Depends on**: Tasks 10, 14

**Scope**:
- `androidApp/src/main/res/xml/file_provider_paths.xml` — declares the app's `files-path` for `FileProvider`
- `AndroidManifest.xml` — `<provider>` declaration for `androidx.core.content.FileProvider`
- `TripDetailViewModel.kt` + `TripListViewModel.kt` — `onExport(groupId)` → launches coroutine → calls `ExportUseCase.export()` → on success: create `FileProvider` URI → trigger `ACTION_SEND` intent → expose `ExportState` (Idle / InProgress / Done / Error) to UI
- `TripDetailScreen.kt` / `TripListScreen.kt` — show a `LinearProgressIndicator` (indeterminate) while export is in progress; show error snackbar on failure
- Verify the share sheet launches correctly with the `.zip` MIME type

**Tests to propose**:
- `TripDetailViewModel`: `onExport(groupId)` → `exportState` transitions: `Idle → InProgress → Done`
- `TripDetailViewModel`: `ExportUseCase` throws → `exportState` transitions to `Error` with message
- Full round-trip (Android instrumented): create a TripGroup with 1 session + 3 TrackPoints + 1 text note → export → verify the zip file exists at the returned path → unzip and parse `index.json` → verify it contains 1 session, 3 track points, 1 note

---

## Summary

| Task | Title | Phase | Key Output |
|------|-------|-------|------------|
| 1 | KMP Project Init | Scaffold | Compiling project with all deps |
| 2 | Core Data Models | Data | Domain classes + enums |
| 3 | SQLDelight Schema | Data | DB schema + generated queries |
| 4 | Repository Layer | Data | Suspending repository APIs |
| 5 | TransportClassifier + Smoother | Data | Algorithms, fully unit-tested |
| 6 | LocationTrackingService | Platform | ForegroundService + recovery |
| 7 | GalleryScanner | Platform | MediaStore integration |
| 8 | AudioRecorder | Platform | M4A voice note recording |
| 9 | Koin DI Setup | Domain | Full DI graph wired |
| 10 | ExportUseCase | Domain | index.json + GPX + zip |
| 11 | Navigation Graph | Navigation | All routes + dynamic start |
| 12 | Recording Screen — Idle | UI | Permissions + group selector |
| 13 | Recording Screen — Active | UI | Live map + notes + stop |
| 14 | Trip List + Detail Screens | UI | Group cards + session list |
| 15 | Session Review Screen | UI | Full-trip map + timeline |
| 16 | Settings Screen | UI | DataStore-backed preferences |
| 17 | Localization | Cross-cutting | EN + ZH strings |
| 18 | Error Handling + Recovery | Cross-cutting | Recovery dialog + DB check |
| 19 | Export Integration + Share | Export | Share sheet + progress UI |
