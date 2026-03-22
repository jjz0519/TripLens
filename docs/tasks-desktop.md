# TripLens Desktop Tool — Task Breakdown

> Based on: TripLens TDD v1.0 (Sections 1.3, 9–12)
> Stack: Tauri v2 — Rust backend + React + TypeScript frontend
> Order: implementation order (each task depends only on tasks above it)

---

## Format

Each task entry:
- **Goal** — one sentence, what is delivered when the task is done
- **Depends on** — task numbers that must be complete first
- **Scope** — files / modules / components created or significantly modified
- **Tests to propose** — test cases to discuss with the human owner before writing any code

---

## Overview: The Desktop Wizard

The desktop tool is a 5-step linear wizard:

```
[1. Import Archive] → [2. Select Photo Folder] → [3. Calibrate Offset] → [4. Preview Matches] → [5. Write GPS]
```

The Rust backend exposes four Tauri commands (`import_archive`, `scan_photo_folder`, `match_photos`, `write_gps`). The React frontend drives the wizard, calls these commands, and presents results.

---

## Phase 0 — Project Scaffold

### Task 1: Tauri v2 Project Initialization

**Goal**: Create the `triplens-desktop/` project with a compiling Tauri v2 shell, all Rust crate dependencies declared, and a blank React + TypeScript frontend.

**Depends on**: nothing

**Scope**:
- `triplens-desktop/src-tauri/Cargo.toml` — all crate dependencies: `tauri` (v2), `serde` + `serde_json`, `zip`, `little-exif`, `img-parts`, `gpx`, `chrono`, `walkdir`, `log`, `env_logger`, `tokio` (async runtime for commands)
- `triplens-desktop/src-tauri/tauri.conf.json` — app name, window size (900×650 min), `allowlist` for filesystem dialog and shell
- `triplens-desktop/src-tauri/src/main.rs` — Tauri entry point; initializes `env_logger`; registers all four commands (stubs for now); sets up logging to `triplens.log` in app data dir
- `triplens-desktop/package.json` — React 18, TypeScript, Vite, Tailwind CSS, Leaflet + react-leaflet, `@tauri-apps/api`
- `triplens-desktop/src/App.tsx` — blank shell that renders "TripLens Desktop"
- `triplens-desktop/tsconfig.json`, `vite.config.ts`, `tailwind.config.js`

**Tests to propose**: none (scaffold only — verified by `cargo build` succeeding and `npm run tauri dev` launching the window)

---

## Phase 1 — Rust Backend: Data Models & Utilities

### Task 2: Rust Data Models

**Goal**: Define all shared Rust structs and enums used across the four Tauri commands, with full Serde serialization so they can cross the Tauri IPC boundary to the frontend.

**Depends on**: Task 1

**Scope** (`src-tauri/src/models.rs`):
- `TripSummary` — name, exported_at, session_count, total_point_count, date_range_start, date_range_end, bounds (north/south/east/west)
- `SessionTrack` — session_id, name, start_time, end_time (epoch millis), track: `Vec<TrackPoint>`
- `TrackPoint` — timestamp (epoch millis), lat, lng, alt (Option), mode (String)
- `PhotoInfo` — path (String), filename, exif_datetime (Option as epoch millis), already_geotagged (bool)
- `MatchResult` — photo_path, adjusted_timestamp, matched_lat, matched_lng, confidence (`Confidence` enum), time_gap_seconds
- `Confidence` enum — `Green`, `Yellow`, `Red`, `Gray` (serialized as lowercase strings)
- `WriteTarget` — photo_path, lat, lng (only photos the user selected in the preview step)
- `WriteResult` — successful: u32, skipped_already_geotagged: u32, failed: `Vec<FailedFile>`
- `FailedFile` — path, error_message
- `ScanResult` — total_found: u32, in_range: u32, already_geotagged: u32, photos: `Vec<PhotoInfo>`

**Tests to propose**:
- Serialize each struct to JSON and back; verify all fields survive the round-trip (especially `Option` fields serializing as `null`, not omitted)
- `Confidence` serializes as `"green"` / `"yellow"` / `"red"` / `"gray"` (lowercase) — verify this matches the TypeScript enum values the frontend will use

---

### Task 3: Geo Utilities

**Goal**: Implement the geographic calculation functions used by the matching algorithm and the export pipeline: haversine distance, linear coordinate interpolation, and decimal-to-DMS conversion.

**Depends on**: Task 2

**Scope** (`src-tauri/src/geo.rs`):
- `haversine_meters(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64` — standard haversine formula using Earth radius 6_371_000 m
- `interpolate_location(p1: &TrackPoint, p2: &TrackPoint, ratio: f64) -> (f64, f64)` — linear interpolation of lat/lng; ratio is clamped to [0.0, 1.0]
- `decimal_to_dms(decimal: f64) -> ([u32; 3], u32, &'static str)` — converts decimal degrees to degrees/minutes/seconds (seconds stored ×10000 for 4 d.p. precision); returns the three rational numerators, the seconds denominator (10000), and the hemisphere reference ("N"/"S" or "E"/"W" — caller uses the right one for lat vs lng)
- `dms_to_decimal(d: u32, m: u32, s_num: u32, s_den: u32, reference: &str) -> f64` — inverse, for round-trip testing

**Tests to propose**:
- `haversine_meters`: Wellington CBD to Wellington Airport (known distance ≈ 7.1 km) → result within ±50 m
- `haversine_meters`: same point twice → 0.0
- `interpolate_location`: ratio=0.0 → returns p1 coords; ratio=1.0 → returns p2 coords; ratio=0.5 → midpoint
- `interpolate_location`: ratio clamped — ratio=-0.1 → same as 0.0; ratio=1.5 → same as 1.0
- `decimal_to_dms` → `dms_to_decimal` round-trip: test 10 sample coordinates (positive and negative lat/lng); result matches input within 0.00001°
- `decimal_to_dms`: hemisphere reference — negative lat → "S"; positive lng → "E"; negative lng → "W"
- `decimal_to_dms`: 0.0 → (0, 0, 0), denominator 10000, reference "N" (or "E")

---

### Task 4: GPX Parser

**Goal**: Parse a GPX 1.1 file (with optional TripLens extensions) into a `Vec<TrackPoint>` that the matching algorithm can consume.

**Depends on**: Task 2

**Scope** (`src-tauri/src/gpx_parser.rs`):
- `parse_gpx(path: &Path) -> Result<Vec<TrackPoint>, String>` — reads the file, parses GPX 1.1 XML; for each `<trkpt>`: extracts lat/lng from attributes, `<ele>` (optional), `<time>` (ISO 8601 → epoch millis via `chrono`), `<triplens:mode>` extension (optional, defaults to `"stationary"` if absent)
- Handles GPX files without TripLens extensions (standard GPX from other tools) gracefully — `mode` is set to `"stationary"` and `alt` to `None` if elements are missing
- Note: the desktop tool uses the GPX files from the archive as an alternative track source to `index.json`'s inline track array. For MVP, the matching algorithm uses `index.json`'s track array directly; the GPX parser is used for interoperability validation and future use.

**Tests to propose**:
- Parse a minimal valid GPX 1.1 file with 3 trackpoints → verify lat/lng/time/ele for each point
- Parse a TripLens-extended GPX → verify `mode` field is populated from `<triplens:mode>`
- Parse a standard (non-TripLens) GPX → no crash; `mode` defaults to `"stationary"`
- Parse a GPX with a missing `<ele>` element → `alt` is `None`, not an error
- Malformed XML → returns `Err` with a descriptive message (not a panic)
- Empty `<trkseg>` → returns empty `Vec`, no error

---

### Task 5: EXIF Utilities

**Goal**: Implement lossless EXIF GPS read and write operations, and the `DateTimeOriginal` reader used by the photo scanner.

**Depends on**: Tasks 2, 3

**Scope** (`src-tauri/src/exif_utils.rs`):
- `read_exif_datetime(path: &Path) -> Option<i64>` — reads `DateTimeOriginal` tag (`"YYYY:MM:DD HH:MM:SS"` format); parses it as a naive UTC datetime (timezone-agnostic, per TDD Section 12.2); returns epoch millis. Returns `None` if the tag is absent or the file has no EXIF.
- `read_exif_gps(path: &Path) -> Option<(f64, f64)>` — reads `GPSLatitude` + `GPSLatitudeRef` + `GPSLongitude` + `GPSLongitudeRef`; converts DMS rationals to decimal degrees using `dms_to_decimal` from `geo.rs`. Returns `None` if any GPS tag is absent.
- `write_gps_to_jpeg(path: &Path, lat: f64, lng: f64, backup_dir: &Path) -> Result<(), String>` — lossless write:
  1. Copy original to `backup_dir/{filename}` (abort entire operation if this fails — disk full / permissions)
  2. Read existing EXIF via `little-exif`
  3. If `GPSLatitude` tag already present → return `Ok(())` without modifying (skip, already geotagged)
  4. Convert lat/lng to DMS via `decimal_to_dms` from `geo.rs`
  5. Write `GPSLatitude`, `GPSLatitudeRef`, `GPSLongitude`, `GPSLongitudeRef` tags
  6. Use `img-parts` to replace only the EXIF APP1 segment in the JPEG — no re-encoding
  7. Log: file path, coordinates written, backup path

**Tests to propose**:
- `read_exif_datetime`: JPEG with `DateTimeOriginal = "2026:03:15 09:30:45"` → returns correct epoch millis for `2026-03-15 09:30:45 UTC`
- `read_exif_datetime`: JPEG with no EXIF → returns `None`
- `read_exif_datetime`: JPEG with EXIF but no `DateTimeOriginal` → returns `None`
- `read_exif_gps`: JPEG with GPS tags (known coordinates) → returns correct decimal degrees within 0.00001°
- `read_exif_gps`: JPEG without GPS tags → returns `None`
- `write_gps_to_jpeg` + `read_exif_gps` round-trip: write known coordinates to a test JPEG → read back → result matches input within 0.00001°
- `write_gps_to_jpeg`: file with existing GPS → returns `Ok(())`, file is not modified (backup still created but original bytes unchanged)
- `write_gps_to_jpeg`: after write, original JPEG pixel data is byte-identical to the backup (verify by comparing everything except the EXIF APP1 segment)
- `write_gps_to_jpeg`: backup dir does not exist or is not writable → returns `Err` before touching the original file

---

## Phase 2 — Rust Backend: Tauri Commands

### Task 6: `import_archive` Command

**Goal**: Implement the Tauri command that extracts a `.zip` Trip Archive, parses and validates `index.json`, and returns a `TripSummary` to the frontend.

**Depends on**: Tasks 2, 3

**Scope** (`src-tauri/src/commands/import.rs`):
- `import_archive(path: String) -> Result<(TripSummary, Vec<SessionTrack>), String>`:
  1. Extract zip to a temp directory in the OS temp folder
  2. Parse `index.json` → validate `version == 1` (return descriptive error for unsupported versions)
  3. Deserialize all sessions and their inline track arrays into `Vec<SessionTrack>`
  4. Compute `TripSummary`: name, exported_at, session count, total track point count, date range (min start_time / max end_time across sessions), bounding box (min/max lat/lng across all points)
  5. Clean up temp directory
  6. Log: archive path, session count, total point count
- The `Vec<SessionTrack>` is held in Tauri's managed state (or returned alongside the summary) so subsequent commands can use it without re-parsing the archive

**Tests to propose**:
- Parse a well-formed `index.json` fixture (2 sessions, 10 track points each) → verify `TripSummary` fields: session_count=2, total_point_count=20, date_range, bounds
- `version` field is missing or not `1` → returns `Err` with message `"Unsupported archive version"`
- Zip contains no `index.json` → returns `Err` with descriptive message
- `index.json` is malformed JSON → returns `Err` (not a panic)
- Zip file does not exist → returns `Err`
- After successful import, temp directory is cleaned up (verify the temp path no longer exists)

---

### Task 7: `scan_photo_folder` Command

**Goal**: Implement the Tauri command that walks a directory for JPEG files, reads their EXIF `DateTimeOriginal` and existing GPS, and returns a `ScanResult` filtered to the trip's time range.

**Depends on**: Tasks 2, 5

**Scope** (`src-tauri/src/commands/scan.rs`):
- `scan_photo_folder(folder: String, trip_start: i64, trip_end: i64) -> Result<ScanResult, String>`:
  1. Walk `folder` recursively using `walkdir`, collecting files with `.jpg` or `.jpeg` extension (case-insensitive)
  2. For each file: call `read_exif_datetime()` and `read_exif_gps()`
  3. Count totals: `total_found` (all JPEGs), `in_range` (those with datetime within trip_start..=trip_end), `already_geotagged` (those already having GPS)
  4. Build `Vec<PhotoInfo>` containing only in-range files
  5. Log: folder path, total found, in-range count, already-geotagged count

**Tests to propose**:
- Folder with 5 JPEGs: 3 within trip range, 2 outside → `in_range == 3`, `total_found == 5`
- Folder with a JPEG that already has GPS → `already_geotagged` count increments; `PhotoInfo.already_geotagged == true`
- Folder with non-JPEG files (.png, .tiff, .raw, .mov) → not included in results
- Nested subdirectories → JPEGs in subdirectories are found
- JPEG with no EXIF (no `DateTimeOriginal`) → excluded from `in_range`, counted in `total_found`
- Empty folder → `ScanResult` with all counts zero, empty `photos` list
- Folder path does not exist → returns `Err`

---

### Task 8: `match_photos` Command

**Goal**: Implement the Tauri command that applies a timestamp offset to each photo and finds its location on the trajectory via binary search + linear interpolation, assigning a confidence level.

**Depends on**: Tasks 2, 3

**Scope** (`src-tauri/src/commands/match_cmd.rs`):
- `match_photos(photos: Vec<PhotoInfo>, sessions: Vec<SessionTrack>, offset_seconds: i32) -> Result<Vec<MatchResult>, String>`:
  - For each photo with a non-None `exif_datetime`:
    1. `adjusted_time = exif_datetime + offset_seconds * 1000` (epoch millis)
    2. Find the session where `session.start_time <= adjusted_time <= session.end_time`; if none found → `confidence = Gray`, no location
    3. Within the session's `track` array (pre-sorted by timestamp), binary search for the two bracketing points `tp_before` and `tp_after`
    4. Linear interpolation → `(lat, lng)` via `interpolate_location` from `geo.rs`
    5. `time_gap_seconds = min(|adjusted_time - tp_before.t|, |adjusted_time - tp_after.t|) / 1000`
    6. Confidence: Green if gap < 30s, Yellow if 30s ≤ gap < 300s, Red if gap ≥ 300s
  - Photos with no `exif_datetime` → `confidence = Gray`, no location
  - Log: photo count, offset applied, count per confidence level

**Tests to propose**:
- Photo timestamp exactly on a track point → `time_gap_seconds == 0`, confidence Green, location == that track point
- Photo timestamp halfway between two track points 20s apart → interpolated midpoint, gap=10s, confidence Green
- Photo timestamp 60s away from nearest track point → confidence Yellow
- Photo timestamp 400s away from nearest track point → confidence Red
- Photo timestamp before the first point of a session → uses first point's location (no interpolation), gap = distance to first point
- Photo timestamp after the last point of a session → uses last point's location
- Photo timestamp in a gap between two sessions → confidence Gray, no location
- `offset_seconds = -46800` (−13 hours): photo that was Gray becomes Green after applying offset — verify offset arithmetic is correct
- Empty `photos` list → returns empty `Vec`, no error
- Session with a single track point → photo within session range gets that point's location, gap = time distance to that point

---

### Task 9: `write_gps` Command

**Goal**: Implement the Tauri command that writes GPS coordinates back to JPEG EXIF losslessly, with per-file error isolation and a full write report.

**Depends on**: Tasks 2, 5

**Scope** (`src-tauri/src/commands/write.rs`):
- `write_gps(matches: Vec<WriteTarget>, backup_dir: String, create_backup: bool) -> Result<WriteResult, String>`:
  1. Create `backup_dir` if it does not exist; if `create_backup == false` skip backup creation (advanced option, not default)
  2. For each `WriteTarget` (sequential, not parallel — preserves predictable progress):
     - Call `write_gps_to_jpeg(path, lat, lng, backup_dir)`:
       - If backup copy fails → abort the entire batch immediately (disk full / permissions); report how many succeeded before the abort
       - If EXIF write fails → log the error, add to `failed` list, continue with next file
       - If already geotagged (write fn returned Ok but skipped) → increment `skipped_already_geotagged`
  3. Return `WriteResult`: successful, skipped_already_geotagged, failed (with per-file error messages)
  4. Log: each file written (path + coordinates), each failure (path + error), final summary

**Tests to propose**:
- Write GPS to 3 test JPEGs → `successful == 3`, `failed` is empty; read GPS back from each file → coordinates match
- One file already has GPS → `skipped_already_geotagged == 1`, other files still processed
- One file's EXIF write fails (simulate by passing a read-only path) → that file in `failed` list; other files still succeed; `successful + skipped + failed.len() == total`
- Backup copy fails (simulate by making backup_dir read-only) → returns `Err` immediately; no original files modified; how many succeeded before abort is reported
- `create_backup == false` → no backup files created; originals still written correctly

---

## Phase 3 — React Frontend: Types & App Shell

### Task 10: TypeScript Types + Tauri Invoke Hook

**Goal**: Define all TypeScript interfaces mirroring the Rust models, a typed `useTauriCommand` hook with loading/error state, and the top-level wizard state machine in `App.tsx`.

**Depends on**: Task 1

**Scope**:
- `src/types.ts` — TypeScript interfaces for: `TripSummary`, `SessionTrack`, `TrackPoint`, `PhotoInfo`, `MatchResult`, `Confidence` (union type `"green" | "yellow" | "red" | "gray"`), `WriteTarget`, `WriteResult`, `ScanResult`, `WizardStep` (union type `"import" | "select_folder" | "calibrate" | "preview" | "write"`), `WizardState`
- `src/hooks/useTauriCommand.ts` — generic hook `useTauriCommand<T>(command, args)` that calls `invoke`, returns `{ data, isLoading, error }`, resets on new calls
- `src/App.tsx` — renders the current wizard step component based on `WizardState.step`; passes `onNext` / `onBack` callbacks; maintains the accumulated wizard state (archive, scanResult, offsetSeconds, matches, selectedMatchIds, writeResult)
- `src/components/WizardNav.tsx` — reusable "Back" and "Next/Proceed" button row; "Next" is disabled until the current step's required data is present

**Tests to propose**:
- `WizardState` type: verify (TypeScript compile-time) that all 5 steps are covered and all fields referenced in step components are present
- `useTauriCommand`: mock `invoke` to return a resolved value → `data` is set, `isLoading` goes false
- `useTauriCommand`: mock `invoke` to reject → `error` is set with the rejection message, `data` is null
- `App.tsx` step routing: given `step = "calibrate"`, `CalibrationStep` is rendered (not `ImportStep`)

---

## Phase 4 — React Frontend: Wizard Steps

### Task 11: ImportStep

**Goal**: Implement the first wizard step: a file picker for `.zip` archives that calls `import_archive`, shows a trip summary on success, and handles errors gracefully.

**Depends on**: Task 10

**Scope** (`src/components/ImportStep.tsx`):
- File input restricted to `.zip` (or Tauri's `open` dialog API filtered to `.zip`)
- On file selected: call `import_archive` via `useTauriCommand`
- Loading state: spinner + "Reading archive…"
- Success state: shows `TripSummary` card — trip name, date range, session count, total track points, bounding box (human-readable)
- Error state: error message + "Try another file" button
- "Next: Select Photos" button enabled only after successful import

**Tests to propose**:
- No file selected → "Next" button is disabled
- `import_archive` returns a `TripSummary` → summary card is rendered with correct name and session count
- `import_archive` returns an error (e.g., wrong version) → error message displayed, "Next" still disabled
- After a failed import, selecting a new file clears the error and retries

---

### Task 12: FolderSelectStep

**Goal**: Implement the second wizard step: a folder picker that calls `scan_photo_folder` (using the trip's date range from the archive), and shows scan statistics before the user proceeds.

**Depends on**: Tasks 10, 11

**Scope** (`src/components/FolderSelectStep.tsx`):
- Folder picker via Tauri dialog API (`open({ directory: true })`)
- Calls `scan_photo_folder` with `trip_start` and `trip_end` from the imported `TripSummary`
- Loading state: spinner + "Scanning for photos…" + folder path shown
- Success state: three stat chips — "N photos found", "N in trip range", "N already geotagged"
- If `in_range == 0`: warning message "No photos found in the trip's time range. Check your folder or verify the camera clock."
- "Next: Calibrate Timestamp" button enabled only if `in_range > 0`
- "Change folder" button to re-scan a different folder

**Tests to propose**:
- No folder selected → "Next" disabled
- `ScanResult.in_range == 0` → warning shown, "Next" still disabled
- `ScanResult.in_range > 0` → stat chips render correctly, "Next" enabled
- `scan_photo_folder` returns an error → error message shown

---

### Task 13: CalibrationStep

**Goal**: Implement the third wizard step: the offset slider with live match preview so the user can calibrate the camera-to-phone clock difference before seeing the full results.

**Depends on**: Tasks 10, 12

**Scope** (`src/components/CalibrationStep.tsx`):
- Explanation section: "Your camera clock may differ from your phone's clock. Enter the offset to align them." with a link to a help tooltip explaining the offset concept from TDD Section 12.3 (compare a photo taken on both devices at the same moment)
- Offset control: slider (−86400 to +86400 seconds, i.e., −24h to +24h) + numeric text input showing the value in hours/minutes format (e.g., "−13h 00m")
- On offset change: debounce 300ms → call `match_photos` with current photos, sessions, and new offset → update a small preview table showing the first 5 results (filename, confidence badge, adjusted timestamp)
- Mini confidence summary: "X green, Y yellow, Z red, W gray" — updates live as offset changes
- "Next: Preview All Results" button (always enabled; the user decides when the offset looks right)

**Tests to propose**:
- Offset slider at 0 → `match_photos` called with `offset_seconds = 0`
- Offset changed to −46800 → after 300ms debounce, `match_photos` called with `offset_seconds = -46800`
- Two rapid slider changes within 300ms → `match_photos` called only once (debounce working)
- Numeric input updated → slider moves to match; `match_photos` called after debounce
- Confidence summary updates when `match_photos` returns new results

---

### Task 14: MatchPreviewStep

**Goal**: Implement the fourth wizard step: the full match results table with map, confidence colour-coding, per-photo checkboxes, and the offset fine-tuning slider.

**Depends on**: Tasks 10, 13

**Scope** (`src/components/MatchPreviewStep.tsx`):
- Map (Leaflet + OpenFreeMap `positron` tiles):
  - All session trajectory polylines in gray
  - Match markers colour-coded by confidence (green/yellow/red dot; gray = no marker)
  - Clicking a marker highlights the corresponding table row
- Match results table (sortable by confidence, filename, time):
  - Columns: checkbox, filename, captured_at (original), adjusted_at (with offset), confidence badge, matched coordinates, time gap
  - Row background tinted by confidence level
  - Clicking a row pans the map to that marker
- Bulk selection toolbar: "Select All", "Deselect All", "Deselect already geotagged", selection count display
- Offset slider (same control as CalibrationStep, same 300ms debounce re-match behaviour) — allows fine-tuning without going back
- "Write GPS to N selected photos" button → enabled only if at least 1 photo is selected

**Tests to propose**:
- All photos start selected by default
- "Deselect already geotagged" → `already_geotagged == true` photos are unchecked; others remain checked
- "Select All" after deselecting → all photos checked again
- Match result with `confidence = "gray"` → row is grayed out; no map marker; still selectable (user may want to include it anyway)
- "Write GPS" button disabled when 0 photos selected
- "Write GPS" button label updates dynamically: "Write GPS to 3 selected photos"
- Offset slider change → table and map markers update after debounce

---

### Task 15: WriteStep

**Goal**: Implement the fifth and final wizard step: the write confirmation, per-file progress display, results summary, and post-write actions.

**Depends on**: Tasks 10, 14

**Scope** (`src/components/WriteStep.tsx`):
- Confirmation summary before write starts: "Ready to write GPS to N photos. Backups will be saved to: {source_folder}/_triplens_backup/"
- "Write" button → calls `write_gps` command with selected `WriteTarget` list and backup dir
- Progress display during write (sequential): file counter "Processing 3 of 47…" + current filename + indeterminate progress bar (Tauri events for per-file progress, or poll if events are complex for MVP)
- Results summary card after completion:
  - "N photos written successfully" (green)
  - "M photos already had GPS and were skipped" (gray)
  - "K photos failed" (red) — expandable list showing each failed filename + error message
- Action buttons: "Open backup folder" (calls Tauri shell `open`), "Start over" (resets wizard to ImportStep)

**Tests to propose**:
- `WriteResult` with all successful → only green row shown, red/gray rows absent
- `WriteResult` with 2 failed → red section shows 2 expandable error entries
- `WriteResult.skipped_already_geotagged > 0` → gray section shown with correct count
- "Start over" → `WizardState` reset to initial, `ImportStep` rendered
- Write button disabled after write completes (prevents double-write)

---

## Summary

| Task | Title | Phase | Key Output |
|------|-------|-------|------------|
| 1 | Tauri v2 Project Init | Scaffold | Compiling Tauri + React shell |
| 2 | Rust Data Models | Data Models | All structs + Serde serialization |
| 3 | Geo Utilities | Data Models | Haversine, interpolation, DMS conversion |
| 4 | GPX Parser | Data Models | GPX 1.1 → `Vec<TrackPoint>` |
| 5 | EXIF Utilities | Data Models | EXIF read + lossless GPS write |
| 6 | `import_archive` command | Commands | Archive parse → `TripSummary` |
| 7 | `scan_photo_folder` command | Commands | Folder scan → `ScanResult` |
| 8 | `match_photos` command | Commands | Offset + binary search → `Vec<MatchResult>` |
| 9 | `write_gps` command | Commands | Lossless EXIF write → `WriteResult` |
| 10 | TypeScript Types + App Shell | Frontend | Wizard state machine + invoke hook |
| 11 | ImportStep | Wizard UI | File picker + trip summary |
| 12 | FolderSelectStep | Wizard UI | Folder picker + scan stats |
| 13 | CalibrationStep | Wizard UI | Offset slider + live preview |
| 14 | MatchPreviewStep | Wizard UI | Map + results table + checkboxes |
| 15 | WriteStep | Wizard UI | Write progress + results summary |
