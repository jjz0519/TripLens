# TripLens - Claude Context

## Project Overview

TripLens is a local-first travel recording tool with two components:
1. **Mobile App** (Android-first) — background GPS tracking, gallery auto-indexing, voice/text notes
2. **Desktop Tool** — imports Trip Index File, geo-tags external camera photos via trajectory matching + EXIF write-back

All data stays on-device. No cloud, no account required.

Full specs: [`docs/TripLens-PRD.md`](docs/TripLens-PRD.md) and [`docs/TripLens-TDD.md`](docs/TripLens-TDD.md).

---

## Technology Stack

### Mobile App (KMP)
- **Framework**: Kotlin Multiplatform (KMP) + Compose Multiplatform
- **Database**: SQLDelight 2.0.x (generates type-safe Kotlin from SQL; works in `commonMain`)
- **DI**: Koin 4.0.x (KMP-compatible; shared module in `commonMain`, platform modules in `androidMain`)
- **Map**: MapLibre GL Native 11.x + OpenFreeMap tiles (no API key, no billing, OSM data)
  - Compose wrapper: **ramani-maps** (fallback: `AndroidView` interop)
  - Tile URL: `https://tiles.openfreemap.org/styles/{bright|positron|dark-matter}`
- **Navigation**: AndroidX Navigation Compose 2.8.x (single-activity pattern)
- **GPS**: Google Play Services Fused Location Provider (ForegroundService with `START_STICKY`)
- **Serialization**: Kotlinx Serialization 1.7.x
- **Async**: Kotlinx Coroutines 1.9.x
- **Image loading**: Coil 3.x
- **Voice notes**: M4A (AAC-LC, 64kbps, mono) via Android `MediaRecorder`

### Desktop Tool (Tauri v2)
- **Backend**: Rust — handles file I/O, EXIF read/write, ZIP extraction, GPX parsing
- **Frontend**: React + TypeScript + Tailwind CSS + Leaflet (react-leaflet for map)
- **Key Rust crates**: `little-exif`, `img-parts`, `zip`, `gpx`, `serde_json`, `chrono`, `walkdir`

---

## Project Structure

```
triplens/
├── shared/                  # KMP shared module
│   ├── src/commonMain/      # Data models, SQLDelight DB, use cases, algorithms
│   ├── src/androidMain/     # Android expect/actual: MediaStore, ForegroundService stubs
│   └── src/iosMain/         # (Future) iOS expect/actual
├── androidApp/              # Android entry point + Compose UI screens
│   └── src/main/java/.../
│       ├── ui/              # Compose screens (Recording, TripList, Settings, etc.)
│       ├── service/         # LocationTrackingService (ForegroundService)
│       └── di/              # Koin Android modules
├── desktopApp/              # Tauri v2 app (separate, may be separate repo)
└── docs/                    # PRD and TDD
```

---

## Key Architecture Decisions

- **Shared code scope**: ~60% shared (data models, DB, use cases, export, transport classifier). Platform APIs (GPS, MediaStore, audio) are `expect`/`actual` in `androidMain`.
- **Timestamps**: Always stored as **epoch milliseconds UTC** in SQLite. All timestamps in `index.json` are ISO 8601 UTC.
- **Transport mode**: Speed-based per TrackPoint — stationary (<1 km/h), walking (1–6), cycling (6–20), driving (20–120), fast_transit (>120). Consecutive same-mode points are grouped; short noise segments are smoothed.
- **Timezone**: Offset-based, timezone-agnostic. A single integer offset (seconds) captures both camera clock drift and timezone difference. No timezone database needed.
- **MediaStore scanning**: Every 60s during recording; queries by `DATE_TAKEN > session.start_time`. Reads EXIF GPS if available.
- **GPS interval**: Moving (>1 km/h) → 5–10s; stationary → 60s; auto-pause after 3h stationary (reduce to 1 fix/5 min, resume on movement).

---

## Data Model Summary

- `TripGroup` → `Session[]` → `TrackPoint[]` + `MediaReference[]` + `Note[]`
- `Session.status`: `recording | completed | interrupted`
- `MediaReference.location_source`: `exif | trajectory | null`
- `Note.type`: `text | voice` (voice files stored in app-private storage as `notes/note_{uuid}.m4a`)

---

## Export Format (Trip Index File)

A `.zip` archive:
```
triplens-{name}-{date}/
├── index.json       # Full TripGroup serialization (compact key names, schema_version field)
├── tracks/
│   └── session_{id}.gpx    # GPX 1.1 with custom extensions
├── notes/
│   └── note_*.m4a   # Voice note audio only (photos/videos NOT included — too large)
└── README.txt
```

---

## Dev Rules

### Collaboration Model
- This repo is maintained by Claude (code author) and one human owner. Treat every session as if Claude is the primary developer who needs to re-orient from context alone.
- Keep `CLAUDE.md` up to date as the primary orientation document. When adding non-obvious architecture decisions or changing direction on something previously decided, update this file or add a note to `docs/`.

### Comments & Documentation
- Add inline comments for every non-obvious decision: why a threshold was chosen, why a specific API was used, why a code path exists.
- For complex algorithms (transport classifier, photo matching, timezone offset, segment smoothing), add a block comment above the function explaining the algorithm, its inputs, its outputs, and any known edge cases.
- When a design decision required weighing trade-offs (e.g. choosing SQLDelight over Room), record the rationale in a comment or in `docs/` — not just what was chosen, but why.

### Test-Driven Development (TDD)
- **Always propose test cases to the human owner before writing implementation code.** List: what is being tested, the happy path, edge cases, and failure cases. Wait for approval before proceeding.
- Write the test first, confirm it fails for the right reason, then write the implementation.
- Test layers:
  - **Unit** (`commonMain`, pure JVM, no Android emulator): algorithms, classifiers, use cases, serialization
  - **Integration** (Android instrumented): MediaStore, ForegroundService lifecycle, DB round-trips
  - **Rust** (`cargo test`): EXIF read/write, archive parsing, matching algorithm
- Keep unit tests fast and hermetic. Use fakes/stubs over mocks where possible to avoid brittle tests.
- Test file naming: mirror the source file — `TransportClassifier.kt` → `TransportClassifierTest.kt`.

### Logging
- Use structured, tagged logging throughout so errors are self-diagnosable from logcat/console output alone.
- **Android**: Use `android.util.Log` with a consistent tag per class (e.g., `TAG = "TripLens/LocationService"`). Log at appropriate levels:
  - `Log.d` — routine state changes (GPS fix received, gallery scan started)
  - `Log.i` — significant lifecycle events (session started/stopped, export completed)
  - `Log.w` — recoverable anomalies (GPS accuracy too low, MediaStore returned no results)
  - `Log.e` — errors with full stack trace (always pass the `Throwable`)
- **Rust (desktop)**: Use the `log` crate with `env_logger`. Log file paths, byte counts, and coordinate values on key operations (EXIF parse, GPS interpolation, write-back).
- Every catch/error branch must log the error before handling it. Never silently swallow exceptions.
- For multi-step operations (export pipeline, photo matching), log entry and exit of each step with key parameters so the full flow can be traced from logs alone.

---

## Testing Strategy

- **Unit tests** (`commonMain`, JVM — no emulator): `TransportClassifier`, photo matching algorithm, `ExportUseCase`, DMS conversion, segment smoothing, timezone arithmetic.
- **Integration tests** (Android): gallery scan against test MediaStore, ForegroundService lifecycle, full export round-trip.
- **Rust tests**: EXIF read/write round-trip, archive import, matching algorithm, backup verification.
- **E2E**: Manual full workflow (record → export → desktop import → GPS write-back → verify in photo viewer).

---

## Important Constraints

- **No API keys**: MapLibre + OpenFreeMap only. Never introduce Google Maps, Mapbox, or any key-gated tile provider.
- **No cloud**: No network calls except tile fetching (map display). No auth, no sync, no analytics.
- **iOS not yet built**: `iosMain` expect/actual stubs will be added later. Don't break KMP structure.
- **JPEG write-back is lossless**: Use `little-exif` + `img-parts` to modify only the EXIF segment. Never re-encode the image. Always create a backup before writing.
