# Task 6: LocationTrackingService — Implementation Log

> **Date**: 2026-03-29
> **Status**: Complete — all tests green
> **Spec**: `docs/superpowers/specs/2026-03-29-location-tracking-service-design.md`

---

## What was implemented

All 14 files listed in the spec were created or modified. See spec for full scope.

**Key non-obvious decisions made during implementation:**

### PRAGMA driver split (AppDatabase.kt)
`PRAGMA journal_mode=WAL` returns a result row — Android's driver routes `execute()` through `executeUpdateDelete()` which rejects result-bearing statements. Fixed with `executeQuery()`.

`PRAGMA foreign_keys=ON` returns no rows — JVM's JDBC driver throws "Query does not return results" if called via `executeQuery()`. Kept as `execute()`.

**Rule: use `executeQuery` for result-returning PRAGMAs; `execute` for void PRAGMAs. This split is driver-specific, not SQL-spec behaviour.**

### Instrumented test architecture
`ServiceTestRule` is designed for *bound* services and internally calls `bindService()` waiting for `onServiceConnected`. Our service returns `null` from `onBind()`, so that callback never fires → 5s timeout. Replaced with direct `Context.startService()` + polling `runningInstance`.

### Android 14+ location FGS requirements
Starting a `foregroundServiceType="location"` service on API 34+ requires:
1. `ACCESS_FINE_LOCATION` granted at runtime → `GrantPermissionRule`
2. App in the foreground → `ActivityScenarioRule(MainActivity::class.java)` launched before the service

### GPS isolation in instrumented tests
The `FusedLocationProviderClient` delivers the last known location immediately after `requestLocationUpdates()` is called, and may continue delivering real fixes during the test. This interferes with:
- Auto-pause tests (real fix can pre-seed `stationaryStartMs` with current time, making the 3h threshold unreachable with injected old-timestamp fixes)
- Buffer flush test (real fixes inflate the buffered point count)

Fix: `stopGpsForTest()` sets `isGpsStoppedForTest = true` (guards all three `locationProvider.startUpdates()` call sites in the service) and calls `stopUpdates()`. `resetStationaryStateForTest()` also clears `stationaryStartMs` and `buffer` to eliminate any real fixes that landed between service start and GPS stop.

### Unique test session IDs
`testSessionId = "test-session-${System.currentTimeMillis()}"` prevents stale DB rows from previous instrumented test runs inflating point counts in the buffer flush test.

---

## Tests

| # | Name | Type | Result |
|---|---|---|---|
| 1–11 | `AccuracyProfileTest` | JVM unit | ✓ |
| 12 | `serviceLifecycle_startAndStop` | Instrumented (SM-S9110, API 36) | ✓ |
| 13 | `recovery_nullIntentReadsFromSharedPreferences` | Instrumented | ✓ |
| 14 | `autoPause_triggersAfterThreeHoursStationary` | Instrumented | ✓ |
| 15 | `autoPause_resumesOnMovement` | Instrumented | ✓ |
| 16 | `bufferFlush_persistsAllPointsOnStop` | Instrumented | ✓ |
| (regression) | All existing `shared:test` | JVM unit | ✓ |
