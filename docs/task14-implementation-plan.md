# Task 14 Implementation Plan: Trip List & TripGroup Detail Screens

> Created: 2026-04-07
> Status: Ready for implementation (user decisions finalized)
> Depends on: Tasks 4, 5, 9, 10, 11

**Goal:** Implement the Trip List screen (TripGroup cards with stats and trajectory thumbnail) and
the TripGroup Detail screen (session list with transport breakdown). Also apply three global UI
polish items: remove nav fade animations, make the recording icon static, and switch to a Morandi
green color theme.

---

## User Decisions (locked in)

| # | Question | Decision |
|---|----------|----------|
| Q1 | TripList distance stat | **C — Aggregate SQL.** Add `distance_meters REAL` column to `session` table. Compute distance in `completeSession`, query via `SUM()`. |
| Q2 | Trajectory thumbnail | **B — Canvas polyline.** Isolated composable, upgradeable to MapLibre later. |
| Q3 | Inline rename UX | **B — Dialog.** `AlertDialog` with `OutlinedTextField`. |
| Q4 | Long-press / swipe actions | **None.** Three-dot menu → `DropdownMenu` with Rename / Export / Delete. |
| Q5 | Session name editing | **Dialog.** Add `renameSession()` to repo + SQL. |
| Q6 | Export scope | **A — Dev button.** Call `ExportUseCase.export()`, show snackbar with file path. Full share in Task 19. |
| Q7 | TripDetail track loading | **A — Eager.** Load all sessions' track points on screen open. |
| Q8 | Tests | Confirmed as-is. See Section 7. |

---

## 1. Data Layer Changes

### 1.1 Schema Migration — `session.distance_meters`

**File:** `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/schema.sq`

Add a new nullable column to the `session` table:

```sql
-- After the existing `status` column:
distance_meters REAL
```

The column is nullable so existing rows (recorded before this change) remain valid with `NULL`.
Future `completeSession` calls will populate it.

> **Migration note:** SQLDelight handles schema changes automatically for in-memory test DBs.
> For production, a migration `.sqm` file will be created if needed, but since the app has no
> shipped production database yet, we can modify the schema directly.

### 1.2 New SQL Queries

**File:** `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/SessionQueries.sq`

Add these queries after the existing ones:

```sql
-- Rename a session
updateName:
UPDATE session SET name = ? WHERE id = ?;

-- Set distance when completing a session (called alongside setEndTime)
setDistance:
UPDATE session SET distance_meters = ? WHERE id = ?;
```

**File:** `shared/src/commonMain/sqldelight/com/cooldog/triplens/db/TripGroupQueries.sq`

Add aggregate stats queries:

```sql
-- Aggregate stats for a single group (used by TripDetailScreen header)
getGroupStats:
SELECT
    COUNT(s.id)                       AS session_count,
    MIN(s.start_time)                 AS earliest_start,
    MAX(COALESCE(s.end_time, s.start_time)) AS latest_end,
    COALESCE(SUM(s.distance_meters), 0.0) AS total_distance_meters
FROM session s
WHERE s.group_id = ?;

-- Aggregate stats for ALL groups (used by TripListScreen cards)
-- Returns one row per group. Groups with no sessions still appear (LEFT JOIN).
getAllGroupsWithStats:
SELECT
    g.id,
    g.name,
    g.created_at,
    g.updated_at,
    COUNT(s.id)                       AS session_count,
    MIN(s.start_time)                 AS earliest_start,
    MAX(COALESCE(s.end_time, s.start_time)) AS latest_end,
    COALESCE(SUM(s.distance_meters), 0.0) AS total_distance_meters,
    (SELECT COUNT(*) FROM media_reference mr
         JOIN session ss ON mr.session_id = ss.id
         WHERE ss.group_id = g.id AND mr.type = 'photo') AS photo_count,
    (SELECT COUNT(*) FROM media_reference mr
         JOIN session ss ON mr.session_id = ss.id
         WHERE ss.group_id = g.id AND mr.type = 'video') AS video_count,
    (SELECT COUNT(*) FROM note n
         JOIN session ss ON n.session_id = ss.id
         WHERE ss.group_id = g.id) AS note_count
FROM trip_group g
LEFT JOIN session s ON s.group_id = g.id
GROUP BY g.id
ORDER BY g.created_at DESC;
```

### 1.3 Repository Changes

**File:** `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/SessionRepository.kt`

Add two new methods:

```kotlin
fun renameSession(id: String, newName: String) {
    db.sessionQueries.updateName(newName, id)
}

fun setDistance(id: String, distanceMeters: Double) {
    db.sessionQueries.setDistance(distanceMeters, id)
}
```

**File:** `shared/src/commonMain/kotlin/com/cooldog/triplens/repository/TripRepository.kt`

Add a method that returns group list with pre-computed stats:

```kotlin
/**
 * Data class for a TripGroup row joined with aggregate stats from SQL.
 * Avoids loading all track points into memory just to compute distance and counts.
 */
data class TripGroupWithStats(
    val group: TripGroup,
    val sessionCount: Long,
    val earliestStart: Long?,
    val latestEnd: Long?,
    val totalDistanceMeters: Double,
    val photoCount: Long,
    val videoCount: Long,
    val noteCount: Long,
)

fun getAllGroupsWithStats(): List<TripGroupWithStats> =
    db.tripGroupQueries.getAllGroupsWithStats().executeAsList().map {
        TripGroupWithStats(
            group = TripGroup(it.id, it.name, it.created_at, it.updated_at),
            sessionCount = it.session_count,
            earliestStart = it.earliest_start,
            latestEnd = it.latest_end,
            totalDistanceMeters = it.total_distance_meters,
            photoCount = it.photo_count,
            videoCount = it.video_count,
            noteCount = it.note_count,
        )
    }
```

### 1.4 Incremental Distance Persistence

Distance must be saved **incrementally during the recording poll loop** (every ~3s), not just
at session end. Rationale: the app may be killed silently by the OS at any time. If distance
is only computed on `onStopConfirmed()`, a killed session loses all distance data permanently.

This requires updating `RecordingDeps` and `RecordingViewModel`:

- Add `setDistanceFn: (id: String, distanceMeters: Double) -> Unit` to `RecordingDeps`
- In `refreshActiveData()` (the poll loop), after fetching track points, compute total
  haversine distance and call `setDistanceFn(sessionId, totalDistance)` to persist it
- Also compute+persist in `onStopConfirmed()` as a final flush before completing the session
- Wire in `AndroidModule.kt`: `setDistanceFn = { id, d -> get<SessionRepository>().setDistance(id, d) }`

The haversine computation is already available via `SegmentSmoother.smooth()` which returns
segments with `distanceMeters`. We sum those to get total distance. Or, we can reuse the
same haversine function from SegmentSmoother (currently private — expose as a utility or
duplicate the simple 5-line function).

**Decision:** Add a top-level `fun haversineDistance(points: List<TrackPoint>): Double` utility
in `shared/.../domain/HaversineUtils.kt` and refactor `SegmentSmoother` to use it. Both the
polling distance computation and SegmentSmoother's internal distance call share the same function.

> **Koin approach note:** `TripDetailViewModel` receives `groupId` via Koin's `parametersOf()`
> which is the cross-platform Koin API (works in commonMain, future iOS). `SavedStateHandle`
> was rejected as it is Android-only.

---

## 2. ViewModels

### 2.1 `TripListViewModel`

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/TripListViewModel.kt`

Follows the exact same patterns as `RecordingViewModel`:
- `sealed interface UiState` + `sealed interface Event` nested inside the VM
- `MutableStateFlow<UiState>` + `Channel<Event>(BUFFERED)`
- All IO via `withContext(deps.ioDispatcher)`
- `TripListDeps` data class bundling all lambdas

```kotlin
class TripListViewModel(private val deps: TripListDeps) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Loaded(val groups: List<TripGroupItem>) : UiState
        data class Error(val message: String) : UiState
    }

    sealed interface Event {
        data class ShowSnackbar(val message: String) : Event
    }

    // State and event plumbing (same pattern as RecordingViewModel)
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init { loadGroups() }

    fun onDeleteGroup(id: String) { ... }
    fun onRenameGroup(id: String, newName: String) { ... }
    fun onExportGroup(id: String) { ... }

    private fun loadGroups() { ... } // calls deps.getAllGroupsWithStatsFn
}
```

**`TripGroupItem`** — UI model (not the raw DB row):

```kotlin
data class TripGroupItem(
    val id: String,
    val name: String,
    val sessionCount: Long,
    val dateRange: String,           // formatted "Apr 2 – Apr 5, 2026"
    val totalDistanceMeters: Double,
    val photoCount: Long,
    val videoCount: Long,
    val noteCount: Long,
    val thumbnailPoints: List<Pair<Double, Double>>,  // down-sampled lat/lng for Canvas
)
```

**`TripListDeps`** data class:

```kotlin
data class TripListDeps(
    val getAllGroupsWithStatsFn: () -> List<TripGroupWithStats>,
    val getTrackPointsByGroupFn: (groupId: String) -> List<TrackPoint>,  // for thumbnail
    val deleteGroupFn: (id: String) -> Unit,
    val renameGroupFn: (id: String, newName: String, now: Long) -> Unit,
    val exportFn: suspend (groupId: String, nowMs: Long) -> ExportResult,
    val clock: () -> Long = { System.currentTimeMillis() },
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
```

**Thumbnail point loading:** For each group, load all track points from all its sessions and
down-sample to ~50 points (take every Nth point). This is done once at load time, not reactively.
Future optimization: cache thumbnails or generate them lazily.

### 2.2 `TripDetailViewModel`

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/tripdetail/TripDetailViewModel.kt`

```kotlin
class TripDetailViewModel(private val deps: TripDetailDeps) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Loaded(
            val groupName: String,
            val totalDistance: Double,
            val totalDurationSeconds: Long,
            val sessions: List<SessionItem>,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    sealed interface Event {
        data class ShowSnackbar(val message: String) : Event
    }

    // ... standard plumbing ...

    init { loadDetail() }

    fun onRenameSession(id: String, newName: String) { ... }
    fun onExportGroup() { ... }
}
```

**`SessionItem`** — UI model:

```kotlin
data class SessionItem(
    val id: String,
    val name: String,
    val startTime: Long,
    val endTime: Long?,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val transportBreakdown: List<TransportStat>,  // mode → distance
)

data class TransportStat(
    val mode: TransportMode,
    val distanceMeters: Double,
)
```

**`TripDetailDeps`** data class:

```kotlin
data class TripDetailDeps(
    val groupId: String,  // from navigation route argument
    val getGroupByIdFn: (id: String) -> TripGroup?,
    val getSessionsByGroupFn: (groupId: String) -> List<Session>,
    val getTrackPointsFn: (sessionId: String) -> List<TrackPoint>,
    val renameSessionFn: (id: String, newName: String) -> Unit,
    val exportFn: suspend (groupId: String, nowMs: Long) -> ExportResult,
    val clock: () -> Long = { System.currentTimeMillis() },
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
```

**Eager loading (Q7 = A):** On init, load all sessions, then for each session load all track
points and process through `SegmentSmoother.smooth()` to compute transport breakdowns.

---

## 3. Koin Registration

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/di/AndroidModule.kt`

Add two `viewModel { }` blocks for the new ViewModels, following the `RecordingViewModel` pattern.
`TripDetailViewModel` receives `groupId` from the navigation route via a Koin parameter.

```kotlin
viewModel {
    TripListViewModel(
        deps = TripListDeps(
            getAllGroupsWithStatsFn = { get<TripRepository>().getAllGroupsWithStats() },
            getTrackPointsByGroupFn = { groupId ->
                val sessions = get<SessionRepository>().getSessionsByGroup(groupId)
                sessions.flatMap { get<TrackPointRepository>().getBySession(it.id) }
            },
            deleteGroupFn = { id -> get<TripRepository>().deleteGroup(id) },
            renameGroupFn = { id, name, now -> get<TripRepository>().renameGroup(id, name, now) },
            exportFn = { groupId, nowMs -> get<ExportUseCase>().export(groupId, nowMs) },
        )
    )
}

viewModel { params ->
    val groupId: String = params.get()
    TripDetailViewModel(
        deps = TripDetailDeps(
            groupId = groupId,
            getGroupByIdFn = { get<TripRepository>().getGroupById(it) },
            getSessionsByGroupFn = { get<SessionRepository>().getSessionsByGroup(it) },
            getTrackPointsFn = { get<TrackPointRepository>().getBySession(it) },
            renameSessionFn = { id, name -> get<SessionRepository>().renameSession(id, name) },
            exportFn = { gId, nowMs -> get<ExportUseCase>().export(gId, nowMs) },
        )
    )
}
```

---

## 4. UI Screens

### 4.1 `TripListScreen`

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/TripListScreen.kt`

- `LazyColumn` of `TripGroupCard` composables
- Empty state: centered illustration + "No trips yet" text
- Loading state: centered `CircularProgressIndicator`

**`TripGroupCard`** layout:

```
┌──────────────────────────────────────────────┐
│  [TrajectoryThumbnail]  │  Group Name   [⋮]  │
│   (Canvas polyline)     │  Apr 2 – Apr 5     │
│                         │  12.3 km · 3 sess  │
│                         │  📷 5  📹 2  📝 3   │
└──────────────────────────────────────────────┘
```

- Three-dot `IconButton` (⋮) shows `DropdownMenu`:
  - **Rename** → `RenameDialog` (AlertDialog + OutlinedTextField, pre-filled with current name)
  - **Export** → calls `viewModel.onExportGroup(id)` → snackbar with path
  - **Delete** → `DeleteConfirmDialog` (AlertDialog: "Delete trip __{name}__ and all its sessions?")
- Card click → `onGroupClick(groupId)` → navigates to `TripDetailRoute(groupId)`

### 4.2 `TrajectoryThumbnail`

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/TrajectoryThumbnail.kt`

- A `@Composable` that takes `points: List<Pair<Double, Double>>` and a `Modifier`
- Draws a `Canvas` polyline:
  1. Normalize lat/lng to [0, 1] range within the bounding box
  2. Map to canvas pixel coordinates
  3. Draw a `Path` with `drawPath(..., style = Stroke(width = 2.dp))`
- Uses Morandi green accent color for the line
- If points are empty, show a small "No GPS data" placeholder icon

**Future-proofing:** The composable is a standalone function with a clean `List<Pair<Double, Double>>`
input. Swapping to a MapLibre static snapshot only requires replacing the internals, not the call sites.

### 4.3 `TripDetailScreen`

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/tripdetail/TripDetailScreen.kt`

Layout:

```
┌──────────────────────────────────────────────┐
│  ← Back    Trip Name                  [Export]│
├──────────────────────────────────────────────┤
│  Total: 57.3 km · 4h 23m · 3 sessions       │
├──────────────────────────────────────────────┤
│  Session 1                            [⋮]    │
│  Apr 2, 10:30 – 12:45 · 2h 15m              │
│  🚶 2.3 km  🚗 45.0 km                       │
├──────────────────────────────────────────────┤
│  Session 2                            [⋮]    │
│  Apr 3, 09:00 – 11:30 · 2h 30m              │
│  🚶 1.1 km  🚲 8.5 km                        │
└──────────────────────────────────────────────┘
```

- Session row click → `navController.navigate(SessionReviewRoute(sessionId))`
- Session three-dot menu → `DropdownMenu` with "Rename"
- Rename → `AlertDialog` + `OutlinedTextField`
- Export button (top-right or FAB) → calls `viewModel.onExportGroup()` → snackbar with path

### 4.4 Shared Dialogs

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/common/RenameDialog.kt`

Reusable rename dialog used by both TripList and TripDetail:
```kotlin
@Composable
fun RenameDialog(
    currentName: String,
    title: String,          // "Rename Trip" or "Rename Session"
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
)
```

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/common/DeleteConfirmDialog.kt`

```kotlin
@Composable
fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
)
```

---

## 5. Global UI Polish

### 5.1 Remove Navigation Fade Animations

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`

The current `NavHost` uses the default `composable<>` which includes fade-in/fade-out transitions.
Override with `EnterTransition.None` and `ExitTransition.None` on the `NavHost`:

```kotlin
NavHost(
    navController = navController,
    startDestination = startDestination,
    modifier = Modifier.padding(innerPadding),
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    popEnterTransition = { EnterTransition.None },
    popExitTransition = { ExitTransition.None },
)
```

### 5.2 Make Recording Icon Static

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`

In `AppBottomNavBar`, change the recording icon to always use `alpha = 1f`:
- Remove the `infiniteTransition` and `pulsingAlpha` animation code
- Set `recordIconAlpha = 1f` unconditionally (or just remove the `Modifier.alpha()` entirely)
- Keep the `isSessionActive` parameter available for potential future use (e.g., badge dot)

### 5.3 Morandi Green Color Theme

**Files:**
- `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Color.kt` [NEW]
- `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Theme.kt` [NEW]
- `composeApp/src/androidMain/kotlin/com/cooldog/triplens/App.kt` [MODIFY] — use `TripLensTheme` instead of plain `MaterialTheme`

**Morandi Green Palette (low-saturation, muted tones):**

| Role | Light | Dark |
|------|-------|------|
| Primary | `#7A9E7E` (sage green) | `#A3C4A7` |
| PrimaryContainer | `#D4E6D5` | `#3A5A3D` |
| Secondary | `#8B9F8E` (muted olive) | `#B0C4B3` |
| SecondaryContainer | `#DAE8DC` | `#3E5240` |
| Background | `#FAFBF7` (warm off-white) | `#1A1C1A` |
| Surface | `#F5F6F2` | `#222422` |
| SurfaceVariant | `#E8EBE5` | `#3A3D3A` |
| Error | `#BA6B6B` (muted red) | `#D89B9B` |
| OnPrimary | `#FFFFFF` | `#1A3A1D` |
| OnBackground | `#2C2F2C` | `#E4E6E2` |
| OnSurface | `#2C2F2C` | `#E4E6E2` |

The `TripLensTheme` composable wraps `MaterialTheme` with these custom `ColorScheme` values.
Both **light and dark modes** are implemented with elegant, modern, minimal aesthetics.
The dark mode uses deeper muted greens with warm off-black backgrounds.
Typography uses the system default sans-serif for now (clean and minimal).

---

## 6. Navigation Wiring

**File:** `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`

Replace the two stubs:

```kotlin
// BEFORE:
composable<TripListRoute> {
    TripListScreenStub(
        onGroupClick = { groupId -> navController.navigate(TripDetailRoute(groupId)) },
    )
}

// AFTER:
composable<TripListRoute> {
    val tripListViewModel: TripListViewModel = koinViewModel()
    TripListScreen(
        viewModel = tripListViewModel,
        onGroupClick = { groupId -> navController.navigate(TripDetailRoute(groupId)) },
    )
}
```

```kotlin
// BEFORE:
composable<TripDetailRoute> { backStackEntry ->
    val route: TripDetailRoute = backStackEntry.toRoute()
    TripDetailScreenStub(
        groupId = route.groupId,
        onSessionClick = { sessionId -> navController.navigate(SessionReviewRoute(sessionId)) },
    )
}

// AFTER:
composable<TripDetailRoute> { backStackEntry ->
    val route: TripDetailRoute = backStackEntry.toRoute()
    val tripDetailViewModel: TripDetailViewModel =
        koinViewModel(parameters = { parametersOf(route.groupId) })
    TripDetailScreen(
        viewModel = tripDetailViewModel,
        onSessionClick = { sessionId -> navController.navigate(SessionReviewRoute(sessionId)) },
        onBack = { navController.popBackStack() },
    )
}
```

Delete the `TripListScreenStub` and `TripDetailScreenStub` private composables as they are no
longer needed.

---

## 7. Tests

**File:** `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/triplist/TripListViewModelTest.kt`

Following the exact same test structure as `AppViewModelTest.kt`:
- `StandardTestDispatcher` injected into deps
- `Dispatchers.setMain(testDispatcher)` in `@Before`
- Lambda-based fakes (no mocking library)

| Test | Assertion |
|------|-----------|
| `emptyDb_emptyGroupsList` | `UiState.Loaded(groups = emptyList())` |
| `twoGroups_twoItemsWithCorrectStats` | `groups.size == 2`, names correct, session counts correct |
| `onDeleteGroup_removesFromState` | After delete, group no longer in state list |
| `onRenameGroup_updatesName` | Verify `renameGroupFn` was called with correct args |
| `onExportGroup_success_showsSnackbar` | Event contains file path string |
| `onExportGroup_failure_showsErrorSnackbar` | Event contains error message |

**File:** `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/tripdetail/TripDetailViewModelTest.kt`

| Test | Assertion |
|------|-----------|
| `loadedState_hasCorrectTransportBreakdown` | 100 walking + 50 driving points → segments reflect correct per-mode distances (via SegmentSmoother) |
| `onRenameSession_callsRepo` | `renameSessionFn` invoked with correct id and new name |

---

## 8. Execution Order

| Step | Description | Files |
|------|-------------|-------|
| 1 | Schema: add `distance_meters` column to `session` | `schema.sq` |
| 2 | SQL queries: add `updateName`, `setDistance` to SessionQueries; add `getAllGroupsWithStats` / `getGroupStats` to TripGroupQueries | `SessionQueries.sq`, `TripGroupQueries.sq` |
| 3 | Extract haversine utility | `shared/.../domain/HaversineUtils.kt` [NEW], `SegmentSmoother.kt` [MODIFY] |
| 4 | Repository: add `renameSession`, `setDistance` to SessionRepository; add `TripGroupWithStats`, `getAllGroupsWithStats` to TripRepository | `SessionRepository.kt`, `TripRepository.kt` |
| 5 | Update `RecordingDeps` + `RecordingViewModel` to compute & persist distance on session complete | `RecordingDeps.kt`, `RecordingViewModel.kt` |
| 6 | Wire `setDistanceFn` in `AndroidModule.kt` | `AndroidModule.kt` |
| 7 | Create theme: `Color.kt`, `Theme.kt` (Morandi green) | [NEW] `ui/theme/Color.kt`, `ui/theme/Theme.kt` |
| 8 | Update `App.kt` to use `TripLensTheme` | `App.kt` |
| 9 | Update `AppNavGraph.kt`: remove nav animations, make recording icon static | `AppNavGraph.kt` |
| 10 | Create shared UI: `RenameDialog`, `DeleteConfirmDialog` | [NEW] `ui/common/RenameDialog.kt`, `ui/common/DeleteConfirmDialog.kt` |
| 11 | Create `TripListDeps`, `TripGroupItem`, `TripListViewModel` | [NEW] `ui/triplist/TripListDeps.kt`, `ui/triplist/TripListViewModel.kt` |
| 12 | Create `TrajectoryThumbnail` | [NEW] `ui/triplist/TrajectoryThumbnail.kt` |
| 13 | Create `TripListScreen` | [NEW] `ui/triplist/TripListScreen.kt` |
| 14 | Create `TripDetailDeps`, `SessionItem`, `TransportStat`, `TripDetailViewModel` | [NEW] `ui/tripdetail/TripDetailDeps.kt`, `ui/tripdetail/TripDetailViewModel.kt` |
| 15 | Create `TripDetailScreen` | [NEW] `ui/tripdetail/TripDetailScreen.kt` |
| 16 | Register VMs in `AndroidModule.kt` | `AndroidModule.kt` |
| 17 | Wire screens into `AppNavGraph.kt`, delete stubs | `AppNavGraph.kt` |
| 18 | Write `TripListViewModelTest` | [NEW] `androidUnitTest/.../triplist/TripListViewModelTest.kt` |
| 19 | Write `TripDetailViewModelTest` | [NEW] `androidUnitTest/.../tripdetail/TripDetailViewModelTest.kt` |
| 20 | Run all tests: `./gradlew composeApp:testDebugUnitTest` | — |
| 21 | Update `docs/task14-handoff.md` status | `task14-handoff.md` |

---

## 9. Files Summary

### New files (14)
- `shared/.../domain/HaversineUtils.kt`
- `composeApp/.../ui/theme/Color.kt`
- `composeApp/.../ui/theme/Theme.kt`
- `composeApp/.../ui/common/RenameDialog.kt`
- `composeApp/.../ui/common/DeleteConfirmDialog.kt`
- `composeApp/.../ui/triplist/TripListDeps.kt`
- `composeApp/.../ui/triplist/TripListViewModel.kt`
- `composeApp/.../ui/triplist/TripGroupItem.kt`
- `composeApp/.../ui/triplist/TripListScreen.kt`
- `composeApp/.../ui/triplist/TrajectoryThumbnail.kt`
- `composeApp/.../ui/tripdetail/TripDetailDeps.kt`
- `composeApp/.../ui/tripdetail/TripDetailViewModel.kt`
- `composeApp/.../ui/tripdetail/TripDetailScreen.kt`
- `composeApp/.../androidUnitTest/.../triplist/TripListViewModelTest.kt`
- `composeApp/.../androidUnitTest/.../tripdetail/TripDetailViewModelTest.kt`

### Modified files (9)
- `schema.sq` — add `distance_meters` column
- `SessionQueries.sq` — add `updateName`, `setDistance`
- `TripGroupQueries.sq` — add aggregate queries
- `SessionRepository.kt` — add `renameSession`, `setDistance`
- `TripRepository.kt` — add `TripGroupWithStats`, `getAllGroupsWithStats`
- `RecordingDeps.kt` — add `setDistanceFn`
- `RecordingViewModel.kt` — compute distance in `onStopConfirmed`
- `AndroidModule.kt` — register 2 new VMs + wire `setDistanceFn`
- `AppNavGraph.kt` — replace stubs, remove animations, remove icon pulse
- `App.kt` — use `TripLensTheme`
- `SegmentSmoother.kt` — refactor to use shared haversine utility
