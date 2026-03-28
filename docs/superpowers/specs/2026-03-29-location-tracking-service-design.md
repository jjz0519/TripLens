# Task 6: LocationTrackingService — Design Spec

> **Date**: 2026-03-29
> **Status**: Approved
> **Implements**: tasks-mobile.md Task 6, TDD §4.1–4.3, §13 (Battery Optimization)

---

## Overview

Implement the Android ForegroundService that owns the GPS recording lifecycle. This is the backbone of Phase 2 — everything that follows (GalleryScanner, AudioRecorder, Koin DI, UI) depends on it being wired.

---

## 1. Schema + Model Changes (`shared`)

The auto-pause feature requires a new boolean column on `track_point`. Since the app has no production installs, the schema is updated in place (no migration file).

**`schema.sq`** — add to `track_point` table:
```sql
is_auto_paused INTEGER NOT NULL DEFAULT 0
```

**`TrackPointQueries.sq`** — add `is_auto_paused` to `insert` and all `SELECT` projections.

**`TrackPoint.kt`** — add field:
```kotlin
val isAutoPaused: Boolean   // true during auto-pause intervals (hidden from timeline)
```

**`TrackPointInsert.kt`** — add field:
```kotlin
val isAutoPaused: Boolean = false
```

**`TrackPointRepository.kt`** — update `toTrackPoint()` mapping and `insert()`/`insertBatch()` calls.

---

## 2. `LocationProvider` expect/actual (`shared`)

Abstracts location hardware behind a platform-agnostic interface so iOS can provide a CoreLocation `actual` later.

### `shared/src/commonMain/kotlin/com/cooldog/triplens/platform/`

**`LocationData.kt`** — platform-agnostic location carrier:
```kotlin
data class LocationData(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,
    val speedMs: Float?      // null if unavailable
)
```

**`LocationPriority.kt`** — platform-agnostic priority:
```kotlin
enum class LocationPriority { HIGH_ACCURACY, BALANCED, LOW_POWER }
```

**`AccuracyProfile.kt`** — three named profiles with interval values from TDD §4.2:
```kotlin
enum class AccuracyProfile(
    val movingIntervalMs: Long,
    val stationaryIntervalMs: Long,
    val priority: LocationPriority
) {
    STANDARD(movingIntervalMs = 8_000, stationaryIntervalMs = 60_000, priority = LocationPriority.HIGH_ACCURACY),
    HIGH(movingIntervalMs = 4_000, stationaryIntervalMs = 4_000, priority = LocationPriority.HIGH_ACCURACY),
    BATTERY_SAVER(movingIntervalMs = 45_000, stationaryIntervalMs = 60_000, priority = LocationPriority.BALANCED)
}
```

**`LocationProvider.kt`** — expect class (no constructor — matches `DatabaseDriverFactory` pattern):
```kotlin
expect class LocationProvider {
    fun startUpdates(intervalMs: Long, priority: LocationPriority, onLocation: (LocationData) -> Unit)
    fun stopUpdates()
}
```

### `shared/src/androidMain/kotlin/com/cooldog/triplens/platform/`

**`LocationProvider.kt`** — actual wrapping `FusedLocationProviderClient`:
- Constructor takes `Context` (platform-specific; added only in `actual`, same pattern as `DatabaseDriverFactory`)
- Maps `LocationPriority` → `com.google.android.gms.location.Priority.*`
- Calls `fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())`
- `stopUpdates()` calls `fusedClient.removeLocationUpdates(callback)`

---

## 3. `LocationTrackingService` (`composeApp`)

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/service/LocationTrackingService.kt`

### Intent API

| Intent action | Extras | Effect |
|---|---|---|
| `ACTION_START` | `session_id: String`, `accuracy_profile: String` | Start recording |
| `ACTION_STOP` | — | Flush buffer, stop service |

### State

```
currentSessionId: String?
currentProfile: AccuracyProfile
locationProvider: LocationProvider
buffer: ArrayDeque<TrackPointInsert>   // flushed at size 10 or on stop
serviceScope: CoroutineScope           // SupervisorJob + Dispatchers.IO, cancelled in onDestroy
isAutoPaused: Boolean
stationaryStartMs: Long?               // set when speed drops < 1 km/h, cleared on movement
```

### Location callback logic

```
onLocationReceived(data: LocationData):
  speedKmh = (data.speedMs ?: 0f) * 3.6f
  mode = TransportClassifier.classify(speedKmh)

  // Auto-pause tracking (timestamp-based)
  if speedKmh < 1.0:
    stationaryStartMs = stationaryStartMs ?: data.timestampMs
    if (data.timestampMs - stationaryStartMs) >= 3h AND NOT isAutoPaused:
      enterAutoPause()
  else:
    stationaryStartMs = null
    if isAutoPaused: exitAutoPause()

  point = TrackPointInsert(sessionId, data.timestampMs, lat, lng, alt, acc, speedMs, mode, isAutoPaused)
  buffer.addLast(point)
  if buffer.size >= 10: flushBuffer()
```

### Auto-pause transitions

- **`enterAutoPause()`**: set `isAutoPaused = true`, restart location updates with `intervalMs = 5 * 60 * 1000`
- **`exitAutoPause()`**: set `isAutoPaused = false`, `stationaryStartMs = null`, restart with profile interval

### Recovery (START_STICKY)

On start:
1. If intent is non-null: write `session_id` + `accuracy_profile` to `SharedPreferences`, use extras
2. If intent is null (system restart): read `session_id` + `accuracy_profile` from `SharedPreferences`
3. If neither yields a session ID: return `START_NOT_STICKY` (nothing to recover)

On stop: clear `SharedPreferences` keys.

### Notification

- Channel ID: `"triplens_recording"`, importance `IMPORTANCE_LOW` (silent, no sound)
- Channel created in service `onCreate` (will move to `Application.onCreate` in Task 9)
- Content: "TripLens is recording" — no action buttons in MVP
- Notification ID: `1001`

### Buffer flush on stop

`onStartCommand(ACTION_STOP)` calls `flushBuffer()` before `stopSelf()` — guarantees no points are lost even when buffer has fewer than 10 entries.

### Manifest addition

```xml
<service
    android:name=".service.LocationTrackingService"
    android:foregroundServiceType="location"
    android:exported="false" />
```

---

## 4. Tests

### JVM unit tests — `shared/src/commonTest/kotlin/com/cooldog/triplens/platform/AccuracyProfileTest.kt`

1. `STANDARD` profile: `movingIntervalMs == 8_000`, `stationaryIntervalMs == 60_000`, `priority == HIGH_ACCURACY`
2. `HIGH` profile: `movingIntervalMs == 4_000`, `stationaryIntervalMs == 4_000`, `priority == HIGH_ACCURACY`
3. `BATTERY_SAVER` profile: `movingIntervalMs == 45_000`, `stationaryIntervalMs == 60_000`, `priority == BALANCED`
4. `BATTERY_SAVER.priority != HIGH_ACCURACY` (lower than STANDARD and HIGH)

### Android instrumented tests — `composeApp/src/androidTest/kotlin/com/cooldog/triplens/service/LocationTrackingServiceTest.kt`

5. **Lifecycle**: Start with valid `session_id` → service is running, foreground notification present → send `ACTION_STOP` → service stopped, notification gone
6. **Recovery**: Start service → `SharedPreferences` contains `session_id` → stop service → call `onStartCommand(null, ...)` → service reads session from prefs and resumes without crash
7. **Auto-pause trigger**: Inject stationary `LocationData` with timestamps spanning > 3h → verify `isAutoPaused` becomes true and interval switches to 5 min
8. **Auto-pause resume**: While in auto-pause, inject one moving fix (speed > 1 km/h) → verify `isAutoPaused` becomes false and interval restores to profile default
9. **Buffer flush on stop**: Inject 5 fixes (below batch threshold of 10) → send `ACTION_STOP` → verify all 5 points persisted in DB

---

## 5. Files Created / Modified

| File | Action |
|---|---|
| `shared/src/commonMain/.../model/TrackPoint.kt` | Modify — add `isAutoPaused` |
| `shared/src/commonMain/.../repository/TrackPointInsert.kt` | Modify — add `isAutoPaused` |
| `shared/src/commonMain/sqldelight/.../schema.sq` | Modify — add column |
| `shared/src/commonMain/sqldelight/.../TrackPointQueries.sq` | Modify — update all queries |
| `shared/src/commonMain/.../repository/TrackPointRepository.kt` | Modify — update mapping |
| `shared/src/commonMain/.../platform/LocationData.kt` | Create |
| `shared/src/commonMain/.../platform/LocationPriority.kt` | Create |
| `shared/src/commonMain/.../platform/AccuracyProfile.kt` | Create |
| `shared/src/commonMain/.../platform/LocationProvider.kt` | Create (expect) |
| `shared/src/androidMain/.../platform/LocationProvider.kt` | Create (actual) |
| `shared/src/commonTest/.../platform/AccuracyProfileTest.kt` | Create |
| `composeApp/src/androidMain/.../service/LocationTrackingService.kt` | Create |
| `composeApp/src/androidMain/AndroidManifest.xml` | Modify — add `<service>` |
| `composeApp/src/androidTest/.../service/LocationTrackingServiceTest.kt` | Create |
