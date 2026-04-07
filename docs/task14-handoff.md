# Task 14 Handoff — Trip List + TripGroup Detail Screens

> Created: 2026-04-07  
> Status: Codebase exploration complete. Awaiting answers to clarifying questions before architecture/implementation.

---

## What Was Completed This Session

- Launched 3 parallel exploration agents covering: UI/ViewModel patterns, data/repository layer, navigation/project structure
- Read all key files: `RecordingViewModel`, `RecordingDeps`, all 5 repositories, `SegmentSmoother`, `Segment`, `ExportUseCase`, `AppNavGraph`, `AndroidModule`, `RecordingScreen`

---

## Key Codebase Facts (do not re-derive)

### Patterns to follow exactly
- **ViewModel**: `sealed interface UiState` + `sealed interface Event` nested inside VM; `MutableStateFlow` + `Channel(BUFFERED)`; all IO via `withContext(deps.ioDispatcher)`; Deps data class for 4+ lambdas
- **Koin**: `viewModel { ... }` in `AndroidModule.kt`; `koinViewModel()` at composable call site
- **Events**: one-shot via `Channel`, collected in `LaunchedEffect(Unit)` in the screen composable
- **No Flow from repos**: all repositories return plain `List<T>` — polling pattern

### Data facts
- `TripGroup`: `id, name, createdAt, updatedAt` — **no denormalized stats**
- `Session`: `id, groupId, name, startTime, endTime?, status: SessionStatus (RECORDING|COMPLETED|INTERRUPTED)`
- `TrackPoint.transportMode` already set at insert time — no re-classification needed
- `SegmentSmoother.smooth(points, minSegmentPoints=3): List<Segment>` — `Segment` has `distanceMeters`, `durationSeconds`, `mode`
- `ExportUseCase.export(groupId, nowMs): ExportResult(path, sizeBytes)` — throws `ExportException` or `IllegalArgumentException`
- **No** `renameSession()` in `SessionRepository` — SQL query also missing
- **No** aggregate SQL queries (no SUM of distance, no pre-joined counts)
- All stats (distance, session count, media count) must be computed in Kotlin by loading full lists

### Navigation (already wired, stubs to replace)
- `AppNavGraph.kt` lines 88–91: `TripListScreenStub` with `onGroupClick` wired to `navController.navigate(TripDetailRoute(groupId))`
- `AppNavGraph.kt` lines 104–111: `TripDetailScreenStub` with `groupId` extracted via `backStackEntry.toRoute<TripDetailRoute>()` and `onSessionClick` wired to `SessionReviewRoute`
- Replace stubs with real composables + `koinViewModel()`
- Register `TripListViewModel` and `TripDetailViewModel` as `viewModel { }` in `AndroidModule.kt`

### FileProvider
- **NOT set up yet** — no `file_provider_paths.xml`, no `<provider>` in manifest. Planned for Task 19.

---

## Clarifying Questions (need answers before starting architecture)

**Q1 — TripList distance stat:**  
Loading total distance per TripGroup card requires loading all track points for all sessions of all groups — expensive on large DBs. Which option?
- **A** — Skip distance on TripList card; show only: session count, date range, photo/video/note counts. Full distance only on TripDetail.
- **B** — Load all track points eagerly (simple, potentially slow).
- **C** — Add aggregate SQL (outside Task 14 scope, could defer).

**Q2 — Trajectory thumbnail:**  
Options: (a) MapLibre async snapshot (non-trivial), (b) simple Canvas polyline (fast, basic). Which?

**Q3 — Inline rename UX:**  
"Inline rename on tap" — inline `TextField` in-place on the card, or open a rename dialog?

**Q4 — Long-press / swipe actions:**  
- **A** — Long-press only → bottom sheet / dropdown with Rename / Delete / Export
- **B** — Swipe-to-delete only, long-press for Rename + Export
- **C** — Both long-press and swipe (full spec)

**Q5 — Session name editing:**  
"Each session shows name (editable)" — inline or dialog? Also: should I add `renameSession(id, newName)` to `SessionRepository` (+ SQL query), or treat names as read-only for now?

**Q6 — Export scope in Task 14:**  
FileProvider/share sheet is Task 19. For Task 14 export button:
- **A** — Call `ExportUseCase.export()`, show snackbar with path (no share intent)
- **B** — No-op button; fully wire export in Task 19
- **C** — Implement full share sheet now (overlaps Task 19)

**Q7 — TripDetail track point loading:**  
- **A** — Load all sessions' track points on screen open (simple, one loading state)
- **B** — Load per-session track points lazily (only when expanded)

**Q8 — Tests (mandatory confirmation per CLAUDE.md):**  
Confirm or modify these before any code is written:

`TripListViewModelTest`:
- Empty DB → `groups` state is empty list
- 2 groups in DB → `groups` has 2 items with correct names and session counts
- `onDeleteGroup(id)` → `deleteGroup` called on repo, group removed from state
- `onRenameGroup(id, newName)` → `renameGroup` called on repo

`TripDetailViewModelTest`:
- Session with 100 walking-mode points + 50 driving-mode points → `segments` reflects correct per-mode `distanceMeters` (use `SegmentSmoother` with `minSegmentPoints=3`)

---

## Next Session: Start Here

1. Read this file first
2. Get answers to Q1–Q8 from the user
3. Proceed to Phase 4 (Architecture Design): propose 2–3 approaches, get user approval
4. Then Phase 5 (Implementation): ViewModels first, then screens, then wire into `AppNavGraph.kt` + `AndroidModule.kt`
