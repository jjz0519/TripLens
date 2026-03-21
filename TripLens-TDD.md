# TripLens - Technical Design Document (TDD)

> **Version**: 1.0 (MVP)  
> **Based on**: TripLens PRD v1.0  
> **Date**: 2026-03-22  
> **Status**: Draft

---

## Table of Contents

1. [Technology Decisions](#1-technology-decisions)
2. [Mobile App Architecture](#2-mobile-app-architecture)
3. [Local Database Schema](#3-local-database-schema)
4. [GPS Tracking Service](#4-gps-tracking-service)
5. [Gallery Scanning Engine](#5-gallery-scanning-engine)
6. [Transport Mode Classification](#6-transport-mode-classification)
7. [Trip Index File Format (Finalized)](#7-trip-index-file-format-finalized)
8. [Export Pipeline](#8-export-pipeline)
9. [Desktop Tool Architecture](#9-desktop-tool-architecture)
10. [Photo Matching Algorithm](#10-photo-matching-algorithm)
11. [EXIF Write-back](#11-exif-write-back)
12. [Timezone Handling Strategy](#12-timezone-handling-strategy)
13. [Battery Optimization](#13-battery-optimization)
14. [Error Handling & Recovery](#14-error-handling--recovery)
15. [Localization Architecture](#15-localization-architecture)
16. [Testing Strategy](#16-testing-strategy)
17. [Decisions Log (PRD Open Questions Resolved)](#17-decisions-log)

---

## 1. Technology Decisions

### 1.1 Mobile App: Kotlin Multiplatform (KMP) + Compose Multiplatform

**Decision for Q2**: Use **Kotlin Multiplatform** with **Compose Multiplatform** for the UI layer.

**Rationale**:

The PRD lists iOS as a high-priority future feature. The framework choice must balance MVP velocity on Android with a credible iOS migration path. Here is how the candidates compare:

- **Native Android (Kotlin + Jetpack Compose)**: Fastest MVP on Android, but zero code reuse for iOS. Would require a full rewrite later.
- **Flutter**: Excellent cross-platform story, but Dart is a niche language. Interop with platform APIs (ForegroundService, MediaStore, background location) requires writing platform channels — effectively maintaining native code anyway for core features.
- **React Native**: JavaScript/TypeScript ecosystem is large, but performance for map-heavy, background-service-heavy apps is a concern. Native module bridge has significant friction for the level of platform API access TripLens needs.
- **Kotlin Multiplatform (KMP)**: Shared business logic in Kotlin (data models, database access, export logic, transport classification). Android UI uses Compose Multiplatform natively. iOS UI can use Compose Multiplatform or SwiftUI wrapping the shared module. Platform-specific code (ForegroundService, MediaStore, CoreLocation) is written with `expect`/`actual` declarations — this is the same code you'd write natively anyway, just behind a shared interface.

KMP wins because TripLens is a platform-API-heavy app (GPS, gallery, audio recording, foreground services). The shared layer is the data/domain logic, not the platform integration. KMP lets us share ~60% of code (data models, database, algorithms, export) while keeping platform code genuinely native.

**Project structure**:

```
triplens/
├── shared/                          # KMP shared module
│   ├── src/commonMain/              # Pure Kotlin: models, DB, algorithms, export
│   ├── src/androidMain/             # Android expect/actual: MediaStore, ForegroundService
│   └── src/iosMain/                 # (Future) iOS expect/actual: CoreLocation, PhotoKit
├── androidApp/                      # Android app entry point + Compose UI
│   ├── src/main/
│   │   ├── java/.../
│   │   │   ├── ui/                  # Compose screens
│   │   │   ├── service/             # LocationTrackingService (ForegroundService)
│   │   │   └── di/                  # Koin dependency injection modules
│   │   ├── res/                     # Android resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── desktopApp/                      # (Separate repo, see Section 9)
└── build.gradle.kts
```

### 1.2 Map SDK: MapLibre GL Native + OpenFreeMap

**Decision for Q3**: Use **MapLibre GL Native** with **OpenFreeMap** tiles.

**Rationale**:

A core principle of TripLens is zero cost and zero dependency on third-party subscriptions for the developer. This rules out options that require API keys tied to billing:

- **Google Maps**: Requires a Google Play Services dependency and an API key tied to a billing account. Even the free tier requires a credit card on file.
- **Mapbox**: Generous free tier (25,000 users/month), but still requires an API key and account. Usage beyond the free tier incurs charges. The SDK license (post-2020) is proprietary.
- **MapLibre GL Native**: The open-source fork of Mapbox GL (BSD license), created after Mapbox changed their license. Fully free with no API key, no usage limits, no proprietary dependencies. Renders vector tiles with good performance and visual quality.

**Tile source**: **OpenFreeMap** provides free vector tiles based on OpenStreetMap data, with no API key required and no usage limits. Tile URL pattern: `https://tiles.openfreemap.org/styles/{style}`. Available styles include "bright", "positron", and "dark matter" — the latter two map well to TripLens's light/dark mode.

**Compose integration**: MapLibre doesn't have an official Compose wrapper as polished as Mapbox's. Options:
- **ramani-maps**: Community-maintained Compose wrapper for MapLibre. Actively developed and sufficient for TripLens's needs (polyline rendering, markers, camera follow).
- **AndroidView interop**: Wrap MapLibre's `MapView` in a Compose `AndroidView` as a fallback if ramani-maps proves insufficient.

**OSM data quality**: For TripLens's use case (displaying trajectory lines and media markers on a street map), OpenStreetMap data quality is excellent in urban areas worldwide and particularly strong in Europe, Japan, and New Zealand. The map is a background canvas, not a navigation or POI discovery tool, so the areas where Google Maps excels (business data, satellite imagery) are not relevant.

**Future offline support**: When the offline map feature from the roadmap is implemented, MapLibre supports PMTiles (a single-file tile archive format). Users could download regional tilesets for offline use at zero cost — a significant advantage over Mapbox and Google Maps, which both charge for offline tile downloads.

### 1.3 Desktop Tool: Tauri v2

**Decision for Q4**: Use **Tauri v2** with a **React + TypeScript** frontend.

**Rationale**:

- The desktop tool is a single-purpose utility with a 5-step wizard flow. It doesn't need the full weight of Electron (~150MB+ base bundle).
- Tauri v2 produces ~5-10MB installers, starts faster, and uses less memory. It's sufficiently mature for a wizard-style app with no complex OS integrations.
- The frontend is a standard web app (React + TypeScript) which is well-understood and easy to iterate on.
- Backend (Rust side) handles file I/O, EXIF read/write, and ZIP extraction — all tasks where Rust excels.
- Tauri v2 supports Windows and macOS from the same codebase, aligning with the future macOS port.

**Key Tauri crates**:
- `kamadak-exif` or `rexif` for EXIF reading
- `img-parts` + `little-exif` for EXIF writing without re-encoding the JPEG
- `zip` crate for Trip Archive extraction
- `gpx` crate for GPX parsing
- `serde` + `serde_json` for index.json parsing

### 1.4 Local Database: SQLite via SQLDelight

For the mobile app, use **SQLDelight** as the database layer. SQLDelight generates type-safe Kotlin APIs from SQL statements, works across KMP targets, and uses SQLite under the hood — perfect for a fully offline app.

**Why not Room**: Room is Android-only. SQLDelight works in KMP `commonMain`, so the database layer is shared if/when we build the iOS app.

### 1.5 Voice Note Format

**Decision for Q5**: **M4A (AAC-LC, 64kbps, mono)**.

AAC at 64kbps mono gives good speech intelligibility at roughly 480KB per minute — a 2-minute voice note is under 1MB. Android's `MediaRecorder` supports AAC output natively with no additional libraries. The `.m4a` container is widely compatible with iOS, desktop players, and web browsers.

---

## 2. Mobile App Architecture

### 2.1 High-Level Architecture

The app follows a **single-activity, Compose Navigation** pattern with a clean layered architecture:

```
┌─────────────────────────────────────────────────────┐
│  UI Layer (Compose)                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ Recording │  │ TripList │  │ Settings │          │
│  │  Screen   │  │  Screen  │  │  Screen  │          │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘          │
│       │              │              │                │
│  ┌────▼──────────────▼──────────────▼────┐          │
│  │         ViewModels (per screen)       │          │
│  └────────────────┬──────────────────────┘          │
├───────────────────┼─────────────────────────────────┤
│  Domain Layer     │  (shared/ commonMain)           │
│  ┌────────────────▼──────────────────────┐          │
│  │  Use Cases / Repositories              │          │
│  │  - TripRepository                      │          │
│  │  - SessionRepository                   │          │
│  │  - NoteRepository                      │          │
│  │  - ExportUseCase                       │          │
│  │  - TransportClassifier                 │          │
│  └────────────────┬──────────────────────┘          │
├───────────────────┼─────────────────────────────────┤
│  Data Layer       │                                 │
│  ┌────────────────▼────────┐ ┌───────────────────┐  │
│  │  SQLDelight Database    │ │  Platform Services │  │
│  │  (commonMain)           │ │  (androidMain)     │  │
│  │  - TripGroupQueries     │ │  - LocationProvider│  │
│  │  - SessionQueries       │ │  - GalleryScanner  │  │
│  │  - TrackPointQueries    │ │  - AudioRecorder   │  │
│  │  - MediaRefQueries      │ │  - ReverseGeocoder │  │
│  │  - NoteQueries          │ │  - FileExporter    │  │
│  └─────────────────────────┘ └───────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 2.2 Dependency Injection

Use **Koin** (KMP-compatible). Define modules in `commonMain` for shared dependencies, and platform-specific modules in `androidMain`.

```kotlin
// shared/commonMain - sharedModule
val sharedModule = module {
    single { DatabaseDriverFactory(get()).createDriver() }
    single { TripLensDatabase(get()) }
    single { TripRepository(get()) }
    single { SessionRepository(get()) }
    single { ExportUseCase(get(), get(), get()) }
    single { TransportClassifier() }
}

// androidApp - androidModule
val androidModule = module {
    single<LocationProvider> { AndroidLocationProvider(get()) }
    single<GalleryScanner> { AndroidGalleryScanner(get()) }
    single<AudioRecorder> { AndroidAudioRecorder(get()) }
}
```

### 2.3 Navigation Graph

```
NavHost(startDestination = dynamicStart()) {
    // dynamicStart() returns "tripList" if no active session, 
    // "recording" if a session is in "recording" status

    composable("tripList") { TripListScreen() }
    composable("recording") { RecordingScreen() }
    composable("settings") { SettingsScreen() }
    composable("tripDetail/{groupId}") { TripDetailScreen(groupId) }
    composable("sessionReview/{sessionId}") { SessionReviewScreen(sessionId) }
}
```

Bottom navigation bar is only shown on the three top-level destinations (`tripList`, `recording`, `settings`). The `recording` tab is conditionally shown/highlighted based on whether a session is active.

---

## 3. Local Database Schema

SQLDelight `.sq` files define the schema. All timestamps are stored as **milliseconds since Unix epoch (UTC)**. UUIDs are stored as TEXT.

### 3.1 SQL Schema

```sql
-- schema.sq

CREATE TABLE trip_group (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL,   -- epoch millis UTC
    updated_at INTEGER NOT NULL
);

CREATE TABLE session (
    id TEXT NOT NULL PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES trip_group(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    start_time INTEGER NOT NULL,
    end_time INTEGER,              -- NULL while recording
    status TEXT NOT NULL DEFAULT 'recording'
        CHECK (status IN ('recording', 'completed', 'interrupted'))
);

CREATE TABLE track_point (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    timestamp INTEGER NOT NULL,    -- epoch millis UTC
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    altitude REAL,
    accuracy REAL NOT NULL,
    speed REAL,                    -- m/s, nullable
    transport_mode TEXT NOT NULL DEFAULT 'stationary'
        CHECK (transport_mode IN ('stationary','walking','cycling','driving','fast_transit'))
);
CREATE INDEX idx_tp_session_time ON track_point(session_id, timestamp);

CREATE TABLE media_reference (
    id TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('photo', 'video')),
    source TEXT NOT NULL CHECK (source IN ('phone_gallery', 'external_camera')),
    content_uri TEXT,
    original_filename TEXT,
    captured_at INTEGER NOT NULL,
    timestamp_offset INTEGER NOT NULL DEFAULT 0,  -- seconds
    original_lat REAL,
    original_lng REAL,
    inferred_lat REAL,
    inferred_lng REAL,
    location_source TEXT CHECK (location_source IN ('exif', 'trajectory')),
    matched_session_id TEXT,
    matched_trackpoint_id INTEGER
);
CREATE INDEX idx_mr_session ON media_reference(session_id);
CREATE INDEX idx_mr_captured ON media_reference(captured_at);

CREATE TABLE note (
    id TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('text', 'voice')),
    content TEXT,
    audio_filename TEXT,
    duration_seconds INTEGER,
    created_at INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL
);
CREATE INDEX idx_note_session ON note(session_id);
```

### 3.2 Storage Estimates

For a 3-week trip (Scenario 2 from PRD):

| Data | Count | Size per item | Total |
|------|-------|---------------|-------|
| TrackPoints (10s interval, 12h/day, 21 days) | ~90,000 | ~60 bytes | ~5.4 MB |
| MediaReferences | ~4,000 | ~200 bytes | ~800 KB |
| Notes | ~100 | ~500 bytes | ~50 KB |
| Voice note audio files | ~30 | ~500 KB each | ~15 MB |
| **Total** | | | **~21 MB** |

The SQLite database itself will be well under 10 MB for even the longest trips. Voice notes dominate storage.

---

## 4. GPS Tracking Service

### 4.1 Android Foreground Service

The `LocationTrackingService` is an Android Foreground Service that owns the GPS recording lifecycle. It survives activity destruction and runs while the phone is locked.

```kotlin
class LocationTrackingService : Service() {
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentSessionId: String? = null
    private var currentAccuracyProfile: AccuracyProfile = AccuracyProfile.STANDARD
    
    // Dynamic interval state
    private var isMoving = false
    private var stationarySince: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra("session_id") ?: return START_NOT_STICKY
        currentSessionId = sessionId
        currentAccuracyProfile = intent.getSerializableExtra("accuracy") as? AccuracyProfile
            ?: AccuracyProfile.STANDARD
        
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        
        return START_STICKY  // System will restart the service if killed
    }
    
    // ... (see Section 4.2 for location callback details)
}
```

### 4.2 Dynamic Interval Strategy

The PRD specifies three accuracy profiles. These map to `LocationRequest` parameters:

```kotlin
enum class AccuracyProfile(
    val movingIntervalMs: Long,
    val stationaryIntervalMs: Long,
    val priority: Int
) {
    STANDARD(
        movingIntervalMs = 8_000,       // ~5-10s when moving
        stationaryIntervalMs = 60_000,  // 60s when stationary
        priority = Priority.PRIORITY_HIGH_ACCURACY
    ),
    HIGH(
        movingIntervalMs = 4_000,       // ~3-5s always
        stationaryIntervalMs = 4_000,   // Same when stationary for hiking
        priority = Priority.PRIORITY_HIGH_ACCURACY
    ),
    BATTERY_SAVER(
        movingIntervalMs = 45_000,      // ~30-60s
        stationaryIntervalMs = 60_000,
        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
    );
}
```

The service switches between moving and stationary intervals by monitoring speed:

```kotlin
private fun onLocationReceived(location: Location) {
    val speedKmh = (location.speed ?: 0f) * 3.6f
    val wasMoving = isMoving
    isMoving = speedKmh > 1.0f
    
    // Switch interval if state changed
    if (isMoving != wasMoving) {
        updateLocationRequest()  // Reconfigure FusedLocationProviderClient interval
    }
    
    // Classify transport mode and persist
    val mode = TransportClassifier.classify(speedKmh)
    val trackPoint = TrackPoint(
        timestamp = location.time,
        latitude = location.latitude,
        longitude = location.longitude,
        altitude = if (location.hasAltitude()) location.altitude else null,
        accuracy = location.accuracy,
        speed = if (location.hasSpeed()) location.speed else null,
        transportMode = mode
    )
    
    // Write to database (off main thread via coroutine)
    serviceScope.launch {
        trackPointRepository.insert(currentSessionId!!, trackPoint)
    }
}
```

### 4.3 Service Lifecycle & Recovery

When `START_STICKY` is set, the system restarts the service after killing it. However, the `Intent` extras are lost on restart. To handle this:

1. When a session starts recording, persist the active session ID and accuracy profile to `SharedPreferences` (or DataStore).
2. In `onStartCommand`, if the intent is null (system restart), read the persisted session ID.
3. If the persisted session has status `recording` and `end_time` is null, resume tracking.
4. If the service is killed and not restarted before the user opens the app, the app detects the `recording` status session with no running service and shows the "Resume/Discard" dialog from the PRD.

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val sessionId = intent?.getStringExtra("session_id")
        ?: prefs.getString("active_session_id", null)
        ?: run { stopSelf(); return START_NOT_STICKY }
    
    // ... continue as normal
}
```

---

## 5. Gallery Scanning Engine

### 5.1 MediaStore Query

The scanner runs on a periodic coroutine (configurable interval: 30s / 60s / 120s) while a session is active.

```kotlin
class AndroidGalleryScanner(private val context: Context) : GalleryScanner {
    
    private var lastScanTimestamp: Long = 0L
    
    suspend fun scanNewMedia(sessionStartTime: Long): List<ScannedMedia> {
        val results = mutableListOf<ScannedMedia>()
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.LATITUDE,    // Deprecated but still works
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        
        // Query photos taken after session start AND after last scan
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} > ? AND ${MediaStore.Images.Media.DATE_TAKEN} > ?"
        val selectionArgs = arrayOf(
            sessionStartTime.toString(),
            lastScanTimestamp.toString()
        )
        
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                val lat = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.LATITUDE))
                val lng = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.LONGITUDE))
                
                results.add(ScannedMedia(
                    contentUri = contentUri.toString(),
                    filename = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)),
                    capturedAt = dateTaken,
                    originalLat = lat,
                    originalLng = lng
                ))
                
                lastScanTimestamp = maxOf(lastScanTimestamp, dateTaken)
            }
        }
        
        // Repeat for video (MediaStore.Video.Media) with same logic
        
        return results
    }
}
```

### 5.2 Deduplication

MediaReferences are keyed by `content_uri`. Before inserting, check if a reference with the same `content_uri` already exists in the session. This prevents duplicates if a photo is modified (and re-indexed by MediaStore) or if the scanner runs twice within the same interval.

### 5.3 EXIF Location for Phone Photos

For Android 10+ (API 29+), `MediaStore.Images.Media.LATITUDE` and `LONGITUDE` columns return 0 unless the app has `ACCESS_MEDIA_LOCATION` permission. TripLens should request this permission alongside `READ_MEDIA_IMAGES`. Without it, the app can fall back to inferring phone photo locations from the trajectory (the same way it handles camera photos), though this is less precise.

**Manifest addition**:
```xml
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />
```

---

## 6. Transport Mode Classification

### 6.1 Classifier Implementation

A pure function in `commonMain` (shared code):

```kotlin
object TransportClassifier {
    
    // Speed thresholds in km/h (from PRD Section 2.2)
    private const val STATIONARY_MAX = 1.0
    private const val WALKING_MAX = 6.0
    private const val CYCLING_MAX = 20.0
    private const val DRIVING_MAX = 120.0
    
    fun classify(speedKmh: Double): TransportMode {
        return when {
            speedKmh < STATIONARY_MAX -> TransportMode.STATIONARY
            speedKmh < WALKING_MAX    -> TransportMode.WALKING
            speedKmh < CYCLING_MAX    -> TransportMode.CYCLING
            speedKmh < DRIVING_MAX    -> TransportMode.DRIVING
            else                      -> TransportMode.FAST_TRANSIT
        }
    }
}
```

### 6.2 Segment Smoothing for Display

When rendering track segments on the map or timeline, apply a smoothing pass to eliminate noise. The algorithm:

1. Group consecutive TrackPoints by `transport_mode` into raw segments.
2. If a segment has fewer than N points AND the segments before and after it have the same mode, absorb it into the surrounding mode. N depends on recording interval — for STANDARD mode (~8s interval), use N=3 (about 24 seconds of data). The intuition: a single "cycling" blip between two "driving" segments is GPS noise, not a real mode change.
3. After smoothing, re-group into final display segments. Each segment stores: mode, start/end timestamps, start/end coordinates, total distance (summed haversine), and duration.

This runs at display time (when building the timeline or rendering the map), not at recording time. Raw per-point classifications are always preserved in the database.

---

## 7. Trip Index File Format (Finalized)

This resolves **PRD Q1**.

### 7.1 Archive Structure

```
triplens-{group_name_slug}-{YYYYMMDD}/
├── index.json
├── tracks/
│   ├── session_{uuid_short}.gpx
│   └── ...
├── notes/
│   ├── {uuid}.m4a
│   └── ...
└── README.txt
```

`{group_name_slug}` is the TripGroup name lowercased, spaces replaced with hyphens, non-ASCII characters kept (for Chinese names). `{uuid_short}` is the first 8 characters of the session UUID.

### 7.2 index.json Schema

```jsonc
{
  "version": 1,                          // Schema version for forward compatibility
  "generator": "TripLens Android 1.0",
  "exported_at": "2026-03-22T14:30:00Z", // ISO 8601 UTC
  
  "trip": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Wellington Weekend",
    "created_at": "2026-03-15T08:00:00Z",
    "updated_at": "2026-03-16T20:00:00Z"
  },
  
  "sessions": [
    {
      "id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
      "name": "Day 1",
      "start_time": "2026-03-15T08:30:00Z",
      "end_time": "2026-03-15T19:45:00Z",
      "status": "completed",
      "gpx_file": "tracks/session_6ba7b810.gpx",
      
      "track_summary": {
        "point_count": 4320,
        "distance_meters": 48500.0,
        "duration_seconds": 40500,
        "bounds": {
          "north": -41.2700,
          "south": -41.3100,
          "east": 174.7900,
          "west": 174.7500
        }
      },
      
      "track": [
        {
          "t": 1742028600000,       // epoch millis (compact key names to save space)
          "lat": -41.2865,
          "lng": 174.7762,
          "alt": 45.2,
          "acc": 8.5,
          "spd": 1.4,
          "mode": "walking"
        }
        // ... thousands of points
      ],
      
      "media": [
        {
          "id": "a1b2c3d4-...",
          "type": "photo",
          "source": "phone_gallery",
          "filename": "IMG_20260315_093045.jpg",
          "captured_at": "2026-03-15T09:30:45Z",
          "timestamp_offset": 0,
          "original_location": { "lat": -41.2900, "lng": 174.7800 },
          "inferred_location": null,
          "location_source": "exif",
          "width": 4032,
          "height": 3024
        }
        // ...
      ],
      
      "notes": [
        {
          "id": "f47ac10b-...",
          "type": "voice",
          "content": null,
          "audio_file": "notes/f47ac10b.m4a",
          "duration_seconds": 45,
          "created_at": "2026-03-15T12:15:00Z",
          "location": { "lat": -41.2920, "lng": 174.7780 }
        },
        {
          "id": "e12d3a45-...",
          "type": "text",
          "content": "Amazing coffee at Customs Brew Bar",
          "audio_file": null,
          "duration_seconds": null,
          "created_at": "2026-03-15T10:30:00Z",
          "location": { "lat": -41.2870, "lng": 174.7760 }
        }
      ]
    }
  ]
}
```

**Design notes**:

- Track point keys are abbreviated (`t`, `lat`, `lng`, `alt`, `acc`, `spd`, `mode`) because a 3-week trip can have 90,000+ points. This reduces JSON size by roughly 40%.
- Timestamps in track points use epoch millis (integers) for compactness. All other timestamps use ISO 8601 for human readability.
- `track_summary` provides quick stats without needing to iterate all points.
- The `version` field allows the desktop tool (and future tools) to handle schema migrations gracefully.
- Media references include `width` and `height` to allow the desktop tool to display thumbnails without needing the actual image files.

### 7.3 GPX File Format

Standard GPX 1.1 with TripLens extensions for transport mode:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="TripLens Android 1.0"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:triplens="http://triplens.app/gpx/1">
  <metadata>
    <name>Day 1</name>
    <time>2026-03-15T08:30:00Z</time>
  </metadata>
  <trk>
    <name>Day 1</name>
    <trkseg>
      <trkpt lat="-41.2865" lon="174.7762">
        <ele>45.2</ele>
        <time>2026-03-15T08:30:00Z</time>
        <extensions>
          <triplens:speed>1.4</triplens:speed>
          <triplens:accuracy>8.5</triplens:accuracy>
          <triplens:mode>walking</triplens:mode>
        </extensions>
      </trkpt>
      <!-- ... -->
    </trkseg>
  </trk>
</gpx>
```

The `<extensions>` block is ignored by tools that don't understand TripLens extensions, maintaining full GPX compatibility.

### 7.4 README.txt

```
TripLens Trip Archive
=====================

This archive was exported from TripLens (https://triplens.app).

Contents:
- index.json: Complete trip data including GPS trajectory, photo/video references, and notes.
- tracks/: GPX files for each recording session (compatible with most mapping tools).
- notes/: Voice note audio files (M4A format).

Note: Photos and videos are NOT included in this archive. The index.json file contains
references (filenames, timestamps, dimensions) to help match them with the trajectory.

File format version: 1
```

---

## 8. Export Pipeline

### 8.1 Export Flow

Export runs on a background coroutine and produces a `.zip` file:

```kotlin
class ExportUseCase(
    private val db: TripLensDatabase,
    private val gpxWriter: GpxWriter,       // commonMain
    private val fileSystem: PlatformFileSystem // expect/actual
) {
    suspend fun export(groupId: String, outputDir: String): ExportResult {
        val group = db.tripGroupQueries.getById(groupId).executeAsOne()
        val sessions = db.sessionQueries.getByGroupId(groupId).executeAsList()
        
        // 1. Build index.json in memory
        val indexJson = buildIndexJson(group, sessions)
        
        // 2. Create temp directory
        val tempDir = fileSystem.createTempDir("triplens-export")
        val archiveName = buildArchiveName(group)
        
        // 3. Write index.json
        fileSystem.writeText("$tempDir/index.json", indexJson)
        
        // 4. Generate GPX files
        fileSystem.createDir("$tempDir/tracks")
        for (session in sessions) {
            val points = db.trackPointQueries.getBySessionId(session.id).executeAsList()
            val gpx = gpxWriter.write(session, points)
            val filename = "session_${session.id.take(8)}.gpx"
            fileSystem.writeText("$tempDir/tracks/$filename", gpx)
        }
        
        // 5. Copy voice note audio files
        fileSystem.createDir("$tempDir/notes")
        for (session in sessions) {
            val voiceNotes = db.noteQueries.getVoiceNotesBySession(session.id).executeAsList()
            for (note in voiceNotes) {
                note.audio_filename?.let { src ->
                    val dest = "$tempDir/notes/${note.id}.m4a"
                    fileSystem.copy(src, dest)
                }
            }
        }
        
        // 6. Write README.txt
        fileSystem.writeText("$tempDir/README.txt", README_CONTENT)
        
        // 7. Zip everything
        val zipPath = "$outputDir/$archiveName.zip"
        fileSystem.zip(tempDir, zipPath)
        
        // 8. Clean up temp directory
        fileSystem.deleteRecursive(tempDir)
        
        return ExportResult(path = zipPath, sizeBytes = fileSystem.size(zipPath))
    }
}
```

### 8.2 Sharing

After export, trigger Android's share sheet via an `ACTION_SEND` intent with the zip file URI (using `FileProvider` for scoped storage compatibility). This lets the user save to Files, send via WhatsApp/Telegram, copy to a USB drive, etc.

---

## 9. Desktop Tool Architecture

### 9.1 Tauri v2 Project Structure

```
triplens-desktop/
├── src-tauri/
│   ├── src/
│   │   ├── main.rs                  # Tauri entry point
│   │   ├── commands/
│   │   │   ├── import.rs            # Import & parse Trip Archive
│   │   │   ├── scan.rs              # Scan photo folder, read EXIF
│   │   │   ├── match.rs             # Photo-to-trajectory matching
│   │   │   └── write.rs             # EXIF GPS write-back
│   │   ├── models.rs                # Shared data types
│   │   ├── exif.rs                  # EXIF read/write utilities
│   │   ├── geo.rs                   # Haversine, interpolation
│   │   └── gpx.rs                   # GPX parser
│   ├── Cargo.toml
│   └── tauri.conf.json
├── src/                             # React frontend
│   ├── App.tsx
│   ├── components/
│   │   ├── ImportStep.tsx
│   │   ├── FolderSelectStep.tsx
│   │   ├── CalibrationStep.tsx
│   │   ├── MatchPreviewStep.tsx
│   │   └── WriteStep.tsx
│   ├── hooks/
│   │   └── useTauriCommand.ts
│   └── types.ts
├── package.json
└── tsconfig.json
```

### 9.2 Tauri Command API

The frontend communicates with the Rust backend through Tauri's `invoke` commands:

```rust
// commands/import.rs
#[tauri::command]
async fn import_archive(path: String) -> Result<TripSummary, String> {
    // 1. Extract zip to temp directory
    // 2. Parse index.json
    // 3. Validate version field
    // 4. Return summary (name, date range, session count, point count, bounds)
}

// commands/scan.rs
#[tauri::command]
async fn scan_photo_folder(
    folder: String,
    trip_start: i64,    // epoch millis
    trip_end: i64
) -> Result<ScanResult, String> {
    // 1. Walk directory recursively for .jpg/.jpeg
    // 2. Read EXIF: DateTimeOriginal, GPS coords
    // 3. Return: total count, in-range count, already-geotagged count, file list
}

// commands/match.rs
#[tauri::command]
async fn match_photos(
    photos: Vec<PhotoInfo>,
    sessions: Vec<SessionTrack>,
    offset_seconds: i32
) -> Result<Vec<MatchResult>, String> {
    // See Section 10 for algorithm
}

// commands/write.rs
#[tauri::command]
async fn write_gps(
    matches: Vec<WriteTarget>,
    create_backup: bool
) -> Result<WriteResult, String> {
    // See Section 11
}
```

### 9.3 Frontend State Machine

The wizard is modeled as a linear state machine. Each step unlocks only after the previous step is complete:

```typescript
type WizardStep = 'import' | 'select_folder' | 'calibrate' | 'preview' | 'write';

interface WizardState {
  step: WizardStep;
  archive: TripSummary | null;
  scanResult: ScanResult | null;
  offsetSeconds: number;
  matches: MatchResult[];
  selectedMatchIds: Set<string>;
  writeResult: WriteResult | null;
}
```

The `CalibrationStep` and `MatchPreviewStep` are tightly coupled: adjusting the offset slider triggers a re-match (debounced at 300ms) and the preview table updates reactively.

### 9.4 Map Integration (Desktop)

For the desktop match preview map, use **Leaflet** with **OpenFreeMap** tiles (same tile source as the mobile app, ensuring visual consistency). Leaflet is lightweight, free, and well-suited for a read-only map with markers.

---

## 10. Photo Matching Algorithm

### 10.1 Core Algorithm

The matching problem: given a photo with timestamp `T` (adjusted by offset), find the best matching location from the recorded trajectory.

```
Input:
  - Photo timestamp T (epoch millis, offset-adjusted)
  - All sessions' TrackPoints, sorted by timestamp within each session

Output:
  - Matched location (lat, lng)
  - Nearest trackpoint
  - Time gap (seconds)
  - Confidence level
```

**Algorithm (per photo)**:

1. **Find the containing session**: Iterate sessions and find one where `session.start_time <= T <= session.end_time`. If no session contains T, mark confidence as NONE (gray) and skip.

2. **Binary search for nearest trackpoint**: Within the session's track array (sorted by timestamp), binary search for the two trackpoints that bracket T: `tp_before.t <= T <= tp_after.t`.

3. **Linear interpolation**: If both bracket points exist, interpolate the location:
   ```
   ratio = (T - tp_before.t) / (tp_after.t - tp_before.t)
   lat = tp_before.lat + ratio * (tp_after.lat - tp_before.lat)
   lng = tp_before.lng + ratio * (tp_after.lng - tp_before.lng)
   ```
   If T is before the first point or after the last point in the session, use the nearest endpoint without interpolation.

4. **Compute time gap**: `gap = min(|T - tp_before.t|, |T - tp_after.t|)` in seconds.

5. **Assign confidence**:
   - GREEN: gap < 30s
   - YELLOW: 30s ≤ gap < 300s
   - RED: gap ≥ 300s
   - GRAY: no session covers this timestamp

### 10.2 Complexity

With tracks pre-sorted and binary search, each photo match is O(log N) where N is the number of TrackPoints in the session. For 500 photos against 90,000 TrackPoints, total matching time is negligible (<100ms).

### 10.3 Edge Cases

- **Photo taken during a gap between sessions** (e.g., session ended at 18:00, next session started at 08:00 next day, photo at 20:00): The photo falls outside all sessions. Confidence = GRAY. No location is inferred.
- **Photo taken while stationary for a long time**: Interpolation between two nearby stationary points yields the correct location. Gap may be large (e.g., 60s between stationary trackpoints) but location is accurate. The confidence indicator (based purely on time gap) may show YELLOW even though the location is fine. This is an acceptable trade-off for MVP — a more sophisticated confidence model could factor in speed/movement in future versions.
- **Multiple sessions in one day**: The algorithm checks all sessions, not just the first match. A photo at 14:00 will match against whichever session was recording at 14:00.

---

## 11. EXIF Write-back

### 11.1 Strategy

EXIF modification must preserve the original JPEG data without re-encoding. The Rust implementation uses `img-parts` to parse the JPEG structure and `little-exif` to modify the EXIF segment:

```rust
use img_parts::jpeg::Jpeg;
use little_exif::exif_tag::ExifTag;
use little_exif::metadata::Metadata;

fn write_gps_to_jpeg(path: &Path, lat: f64, lng: f64, backup_dir: &Path) -> Result<(), Error> {
    // 1. Backup original
    let backup_path = backup_dir.join(path.file_name().unwrap());
    fs::copy(path, &backup_path)?;
    
    // 2. Read existing EXIF
    let mut metadata = Metadata::new_from_path(path)?;
    
    // 3. Check if GPS already exists — skip if so
    if metadata.get_tag(&ExifTag::GPSLatitude).is_some() {
        return Ok(()); // Already geotagged, don't overwrite
    }
    
    // 4. Convert decimal degrees to EXIF rational format
    let (lat_dms, lat_ref) = decimal_to_dms_exif(lat);   // Returns ([d, m, s], "N"/"S")
    let (lng_dms, lng_ref) = decimal_to_dms_exif(lng);
    
    // 5. Write GPS tags
    metadata.set_tag(ExifTag::GPSLatitude(lat_dms));
    metadata.set_tag(ExifTag::GPSLatitudeRef(lat_ref.to_string()));
    metadata.set_tag(ExifTag::GPSLongitude(lng_dms));
    metadata.set_tag(ExifTag::GPSLongitudeRef(lng_ref.to_string()));
    
    // 6. Write back to file (modifies EXIF segment only, no re-encoding)
    metadata.write_to_file(path)?;
    
    Ok(())
}
```

### 11.2 Decimal to DMS Conversion

EXIF stores GPS coordinates as degrees/minutes/seconds in rational number format:

```rust
fn decimal_to_dms_exif(decimal: f64) -> ([Rational; 3], &'static str) {
    let is_positive = decimal >= 0.0;
    let abs = decimal.abs();
    
    let degrees = abs.floor() as u32;
    let minutes_decimal = (abs - degrees as f64) * 60.0;
    let minutes = minutes_decimal.floor() as u32;
    let seconds = ((minutes_decimal - minutes as f64) * 60.0 * 10000.0).round() as u32;
    // seconds stored as rational: seconds/10000 for 4 decimal places of precision
    
    let dms = [
        Rational::new(degrees, 1),
        Rational::new(minutes, 1),
        Rational::new(seconds, 10000),
    ];
    
    // For latitude: positive = N, negative = S
    // For longitude: positive = E, negative = W
    // Caller determines which reference to use based on lat vs lng context
    let reference = if is_positive { "N_or_E" } else { "S_or_W" };
    // (actual code splits this into separate lat/lng functions)
    
    (dms, reference)
}
```

### 11.3 Error Handling During Write

The write operation processes files sequentially with per-file error handling:

- If backup copy fails (disk full, permissions): abort entire operation, report error.
- If EXIF write fails for a single file: log the error, skip the file, continue with remaining files.
- The final report shows: N successful, M skipped (already had GPS), K failed (with error messages).

---

## 12. Timezone Handling Strategy

This resolves **PRD Q7**.

### 12.1 The Problem

Timestamps in TripLens come from three sources with different timezone behaviors:

| Source | Timezone | Format |
|--------|----------|--------|
| Phone GPS (`Location.getTime()`) | Always UTC | epoch millis |
| Phone MediaStore (`DATE_TAKEN`) | Usually UTC | epoch millis |
| Camera EXIF (`DateTimeOriginal`) | Camera's local time (no timezone info) | `"2026:03:15 09:30:45"` |

The camera's EXIF `DateTimeOriginal` is the tricky one. It's a "wall clock" time with no timezone indicator. If Chris sets his camera clock to Auckland time (NZDT, UTC+13) and travels to Japan (JST, UTC+9), his camera timestamps will be 4 hours ahead of the actual local time in Japan — but they'll still be consistent relative to each other.

### 12.2 Strategy: Offset-Based, Timezone-Agnostic

The desktop tool does **not** attempt to determine the camera's timezone. Instead, it relies entirely on the **relative offset** between the camera clock and the phone clock:

1. All trajectory timestamps (from GPS) are in UTC epoch millis. This is the reference clock.
2. The user provides a single offset value: "camera is X seconds ahead of phone." This offset absorbs both clock drift AND timezone differences.
3. For matching, each camera photo's EXIF timestamp is parsed as-is (no timezone conversion) and the offset is applied: `adjusted_time = exif_time_as_utc + offset_seconds * 1000`.

**Why "as_is to UTC"?** The EXIF time `"2026:03:15 09:30:45"` is parsed as if it were UTC. This gives a wrong absolute time, but the offset corrects it. The key insight: we only care about the relative time between the camera and the GPS track, not the absolute time. The offset captures the total difference (timezone + clock drift) in one number.

### 12.3 Practical Example

Chris is in Japan. His camera is set to Auckland time (UTC+13). His phone auto-adjusts to Japan time (UTC+9).

- Chris takes a photo at 14:00 Japan time.
- His camera shows 18:00 (Auckland time). EXIF: `"2026:03:20 18:00:00"`.
- His phone records a GPS point at 14:00 Japan time = 05:00 UTC (epoch millis).
- Desktop tool parses EXIF naively as UTC: epoch millis for `2026-03-20 18:00:00 UTC`.
- The offset to make this match `05:00 UTC` is: `05:00 - 18:00 = -13 hours = -46800 seconds`.
- Chris determines this by comparing a photo he took with both his phone and camera at the same moment. The phone photo's EXIF has the correct UTC time; the camera photo's EXIF has the "wrong" time. The difference is the offset.

This approach handles any timezone combination with a single offset number and no timezone database.

---

## 13. Battery Optimization

### 13.1 Target: <15% for a Full-Day Recording

The PRD sets a success metric of <15% battery for a full-day recording. Key strategies:

**GPS management**:
- Use `FusedLocationProviderClient` (not raw GPS) which fuses GPS, WiFi, and cell tower data for better efficiency.
- Dynamic interval switching (Section 4.2): drop to 60s when stationary. Most travel days have significant stationary time (meals, museums, hotels) where this saves substantial battery.
- STANDARD mode uses `PRIORITY_HIGH_ACCURACY` but with 8s intervals, not continuous.

**Gallery scanning**:
- Default 60s interval is sufficient. Each scan is a single MediaStore query — very lightweight.
- The scan only queries for new items since the last scan timestamp, keeping result sets small.

**Database writes**:
- Batch TrackPoint inserts: buffer up to 10 points in memory, flush to SQLite in a single transaction. This reduces disk I/O.
- Use WAL (Write-Ahead Logging) mode on SQLite for better concurrent read/write performance.

**Map rendering**:
- When the app is in the background (recording continues via ForegroundService), no map rendering occurs. The map is only rendered when the user opens the app.
- When in the foreground, throttle polyline updates to every 5 seconds, not every location update.

### 13.2 Estimated Battery Breakdown (STANDARD mode, 12-hour day)

| Component | Est. battery/hour | 12h total |
|-----------|-------------------|-----------|
| GPS (8s interval, moving) | ~2% | ~8% (assuming 4h moving) |
| GPS (60s interval, stationary) | ~0.3% | ~2.4% (assuming 8h stationary) |
| Gallery scan (60s) | ~0.1% | ~1.2% |
| DB writes, misc CPU | ~0.1% | ~1.2% |
| **Total estimate** | | **~12.8%** |

This is within the 15% target. HIGH mode will be higher (~18-22%) due to constant 4s GPS, which is acceptable — the PRD notes it's for hiking where users expect higher consumption.

---

## 14. Error Handling & Recovery

### 14.1 Session Auto-Pause (Resolves Q6)

If the user forgets to stop recording overnight, the app should not record a useless 8-hour stationary block. Implementation:

- After 3 consecutive hours of `stationary` mode (all TrackPoints in the last 3 hours have speed < 1 km/h), the service enters **auto-pause** state.
- In auto-pause, GPS is reduced to one fix every 5 minutes (enough to detect if the user starts moving again).
- TrackPoints during auto-pause are still recorded but flagged with `is_auto_paused = true` (a boolean column added to the `track_point` table). These points are hidden in the timeline and do not contribute to distance calculations, but they remain in the database for the full trajectory record.
- When movement resumes (speed > 1 km/h), the service exits auto-pause and resumes normal tracking.
- In the timeline display, an auto-paused period appears as a collapsed "Paused overnight — 8h 23m" card.

### 14.2 Database Corruption Recovery

SQLite is robust, but as a defensive measure:

- Enable WAL mode and set `PRAGMA journal_mode=WAL` on database creation.
- Run `PRAGMA integrity_check` on app startup. If it fails, copy the database to a `.corrupt` backup and create a fresh one. (Data loss is unfortunate but should be exceptionally rare.)
- All database writes happen in transactions. A crash mid-write rolls back cleanly.

### 14.3 Export Failure Handling

If export fails mid-way (e.g., disk full while zipping), clean up the temp directory and show a clear error message. The user's database is never affected by export operations.

---

## 15. Localization Architecture

### 15.1 String Resources

Use Android's standard `res/values/strings.xml` and `res/values-zh-rCN/strings.xml` for Android-specific UI strings.

For shared strings (used in export, GPX metadata, etc.), define a `Strings` expect/actual in KMP:

```kotlin
// commonMain
expect object Strings {
    val defaultTripNameFormat: String  // "YYYY-MM-DD {city}"
    val sessionDefaultName: String    // "Day {n}"
    val exportReadme: String
    // ...
}

// androidMain
actual object Strings {
    actual val defaultTripNameFormat: String
        get() = context.getString(R.string.default_trip_name_format)
    // ...
}
```

### 15.2 Language Selection Logic

1. Check app setting (persisted in DataStore).
2. If "Follow System", use `Locale.getDefault()`.
3. Map locale to supported language: `zh-CN` → Chinese, everything else → English (MVP fallback).
4. Apply via `AppCompatDelegate.setApplicationLocales()` (per-app language API, Android 13+; for older versions, use the AndroidX compat layer).

---

## 16. Testing Strategy

### 16.1 Unit Tests (commonMain)

Testable in JVM without Android emulator:

- `TransportClassifier`: boundary values at each threshold.
- Photo matching algorithm: test interpolation, edge cases (before first point, after last point, between sessions, empty track).
- `ExportUseCase`: mock database and file system, verify index.json structure and GPX validity.
- DMS conversion: verify round-trip accuracy (decimal → DMS → decimal matches within 0.0001°).
- Segment smoothing: verify short noise segments are absorbed correctly.
- Timezone/offset handling: verify offset arithmetic with known timezone pairs.

### 16.2 Integration Tests (Android)

- Gallery scanning against a test MediaStore with known photos.
- ForegroundService lifecycle: start → background → foreground → stop.
- Full export: create a TripGroup with sessions, export, unzip, validate index.json parses correctly and GPX files are valid.

### 16.3 Desktop Tool Tests (Rust)

- EXIF read/write round-trip: write GPS to a test JPEG, read it back, verify coordinates match.
- Archive import: parse a sample index.json, verify data model is correctly deserialized.
- Matching algorithm: known trajectory + known photo timestamps → verify expected locations.
- Backup verification: after write-back, verify backup files are byte-identical to originals.

### 16.4 End-to-End Validation

Per PRD success metric: manually execute the full workflow (record trip → review → export → desktop import → match → write GPS → verify in photo viewer). This is the primary acceptance test for MVP.

---

## 17. Decisions Log

Resolved PRD open questions:

| ID | Question | Decision | Rationale |
|----|----------|----------|-----------|
| Q1 | Export file format | index.json v1 schema defined in Section 7.2; GPX 1.1 with extensions | Compact key names for track points; schema version field for future compat |
| Q2 | Mobile framework | Kotlin Multiplatform + Compose Multiplatform | ~60% shared code (data/domain), native platform APIs, clear iOS migration path |
| Q3 | Map SDK | MapLibre GL Native + OpenFreeMap tiles | Fully free (no API key, no usage limits, no billing), OSM data quality sufficient for trajectory display, PMTiles support enables free offline maps in future |
| Q4 | Desktop framework | Tauri v2 (Rust + React/TypeScript) | ~5-10MB bundle, Rust is ideal for EXIF/file I/O, cross-platform from day 1 |
| Q5 | Voice note format | M4A (AAC-LC, 64kbps, mono) | ~480KB/min, native Android MediaRecorder support, broad playback compatibility |
| Q6 | Auto-pause behavior | Auto-pause after 3h stationary; reduce GPS to 1 fix per 5 min | Prevents useless overnight data; resumes automatically on movement |
| Q7 | Timezone handling | Offset-based, timezone-agnostic; single offset captures both drift and TZ difference | No timezone database needed; user determines offset from a reference photo pair |

---

## Appendix: Key Library Dependencies

### Mobile (Android / KMP)

| Library | Purpose | Version (approx) |
|---------|---------|------------------|
| Kotlin Multiplatform | Shared code | 2.1.x |
| Compose Multiplatform | UI framework | 1.7.x |
| SQLDelight | Local database | 2.0.x |
| Koin | Dependency injection | 4.0.x |
| MapLibre GL Native | Map rendering | 11.x |
| ramani-maps | Compose wrapper for MapLibre | latest |
| Google Play Services Location | Fused location provider | 21.x |
| AndroidX Navigation Compose | Navigation | 2.8.x |
| Kotlinx Serialization | JSON serialization | 1.7.x |
| Kotlinx Coroutines | Async/concurrency | 1.9.x |
| Coil | Image loading (thumbnails) | 3.x |

### Desktop (Tauri / Rust)

| Crate | Purpose |
|-------|---------|
| tauri | App framework |
| serde / serde_json | JSON parsing |
| zip | Archive extraction |
| little-exif | EXIF read/write |
| img-parts | JPEG structure parsing |
| gpx | GPX file parsing |
| chrono | Timestamp handling |
| walkdir | Recursive directory scanning |

### Desktop (Frontend)

| Library | Purpose |
|---------|---------|
| React | UI framework |
| TypeScript | Type safety |
| Leaflet + react-leaflet | Map rendering |
| Tailwind CSS | Styling |
