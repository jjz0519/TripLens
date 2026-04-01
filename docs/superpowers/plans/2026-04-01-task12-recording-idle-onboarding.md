# Task 12 — Recording Idle Screen + First-Launch Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a first-launch permission onboarding screen and the idle state of the Recording Screen (MapLibre map + "Start Recording" button that auto-creates a TripGroup and Session on tap).

**Architecture:** `AppViewModel` gains an `Onboarding` start destination driven by a new DataStore-backed `AppPreferences`. `OnboardingViewModel` owns the sequential permission-request sequence (fine location → background location → audio/media). `RecordingViewModel` auto-creates a TripGroup and Session on start tap and fires `LocationTrackingService` via a testable lambda.

**Tech Stack:** Kotlin + Compose Multiplatform, Koin 4.0, AndroidX DataStore Preferences 1.1.1, MapLibre GL Android 11.8.8 (via `AndroidView`), `ActivityResultContracts` for permissions, `kotlinx-coroutines-test` for ViewModel testing.

**Spec:** `docs/superpowers/specs/2026-04-01-task12-recording-idle-onboarding-design.md`

---

## File Map

| Action | File |
|--------|------|
| Create | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/data/AppPreferences.kt` |
| Create | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModel.kt` |
| Create | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingScreen.kt` |
| Create | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModel.kt` |
| Create | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingScreen.kt` |
| Create | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/PermissionRationaleDialog.kt` |
| Create | `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModelTest.kt` |
| Create | `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModelTest.kt` |
| Create | `composeApp/src/androidInstrumentedTest/kotlin/com/cooldog/triplens/data/AppPreferencesTest.kt` |
| Modify | `gradle/libs.versions.toml` |
| Modify | `composeApp/build.gradle.kts` |
| Modify | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/navigation/AppRoutes.kt` |
| Modify | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/App.kt` |
| Modify | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt` |
| Modify | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppViewModel.kt` |
| Modify | `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/AppViewModelTest.kt` |
| Modify | `composeApp/src/androidMain/kotlin/com/cooldog/triplens/di/AndroidModule.kt` |

---

## Task 1: Add DataStore and MapLibre dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Add version entries and library aliases to `gradle/libs.versions.toml`**

In the `[versions]` section, add after the last entry:
```toml
datastore = "1.1.1"
maplibre = "11.8.8"
```

In the `[libraries]` section, add after the last entry:
```toml
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
maplibre-android = { module = "org.maplibre.gl:android-sdk", version.ref = "maplibre" }
```

- [ ] **Step 2: Add libraries to `composeApp/build.gradle.kts`**

Inside `androidMain.dependencies { }`, add after the existing `implementation(libs.coil.compose)` line:
```kotlin
implementation(libs.datastore.preferences)
implementation(libs.maplibre.android)
```

- [ ] **Step 3: Sync Gradle and verify no errors**

```
./gradlew :composeApp:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "build(task12): add DataStore and MapLibre dependencies"
```

---

## Task 2: AppPreferences — DataStore-backed interface

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/data/AppPreferences.kt`

- [ ] **Step 1: Create `AppPreferences.kt`**

```kotlin
package com.cooldog.triplens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Top-level delegate — one DataStore per named file per process.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "triplens_prefs")

/**
 * Interface over persisted app preferences.
 *
 * Extracted as an interface so ViewModel tests can inject a fake without touching real DataStore
 * (which requires an Android context). Task 16 (Settings) will add more keys here.
 */
interface AppPreferences {
    /** Returns true if first-launch permission onboarding has been completed. Default: false. */
    suspend fun isOnboardingComplete(): Boolean

    /** Marks onboarding as complete. Idempotent — safe to call multiple times. */
    suspend fun setOnboardingComplete()
}

/**
 * Production implementation backed by AndroidX DataStore Preferences.
 *
 * Registered as a singleton in [com.cooldog.triplens.di.AndroidModule]: DataStore must not be
 * opened more than once per file per process, so a singleton is required.
 */
class DataStoreAppPreferences(private val context: Context) : AppPreferences {

    override suspend fun isOnboardingComplete(): Boolean =
        context.dataStore.data
            .map { prefs -> prefs[ONBOARDING_COMPLETE_KEY] ?: false }
            .first()

    override suspend fun setOnboardingComplete() {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETE_KEY] = true
        }
    }

    companion object {
        private val ONBOARDING_COMPLETE_KEY = booleanPreferencesKey("onboarding_complete")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/data/AppPreferences.kt
git commit -m "feat(task12): add AppPreferences DataStore interface and implementation"
```

---

## Task 3: AppViewModel — add Onboarding start destination (TDD)

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppViewModel.kt`
- Modify: `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/AppViewModelTest.kt`

`AppViewModel` already accepts `getActiveSessionFn` as a lambda for testability. We add
`isOnboardingCompleteFn: suspend () -> Boolean` with a default of `{ true }` so the four
existing tests keep compiling unchanged.

- [ ] **Step 1: Write the failing tests**

In `AppViewModelTest.kt`, replace the existing `buildViewModel` helper and add three new tests:

```kotlin
// Replace the existing buildViewModel helper:
private fun buildViewModel(
    activeSession: Session?,
    isOnboardingComplete: Boolean = true,  // default keeps existing tests unaffected
): AppViewModel = AppViewModel(
    getActiveSessionFn = { activeSession },
    isOnboardingCompleteFn = { isOnboardingComplete },
    ioDispatcher = testDispatcher,
)

@Test
fun startDestination_isOnboarding_whenOnboardingNotComplete() = runTest(testDispatcher) {
    val vm = buildViewModel(activeSession = null, isOnboardingComplete = false)
    advanceUntilIdle()
    assertEquals(
        AppViewModel.StartDestination.Onboarding,
        vm.startDestination.value,
        "startDestination must be Onboarding when onboarding has not been completed",
    )
}

@Test
fun startDestination_isTripList_whenOnboardingCompleteAndNoSession() = runTest(testDispatcher) {
    val vm = buildViewModel(activeSession = null, isOnboardingComplete = true)
    advanceUntilIdle()
    assertEquals(AppViewModel.StartDestination.TripList, vm.startDestination.value)
}

@Test
fun startDestination_isRecording_whenOnboardingCompleteAndSessionActive() = runTest(testDispatcher) {
    val vm = buildViewModel(activeSession = aRecordingSession, isOnboardingComplete = true)
    advanceUntilIdle()
    assertEquals(AppViewModel.StartDestination.Recording, vm.startDestination.value)
}
```

Also update the call site of the no-arg-style buildViewModel in the existing
`startDestination_isTripList_whenQueryThrows` test so it compiles with the new signature:
```kotlin
val vm = AppViewModel(
    getActiveSessionFn = { throw RuntimeException("DB corrupted") },
    isOnboardingCompleteFn = { true },   // ← add this line
    ioDispatcher = testDispatcher,
)
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :composeApp:testDebugUnitTest --tests "com.cooldog.triplens.ui.AppViewModelTest"
```
Expected: FAIL — `AppViewModel` has no `isOnboardingCompleteFn` parameter; `StartDestination.Onboarding` does not exist.

- [ ] **Step 3: Replace the full content of `AppViewModel.kt`**

```kotlin
package com.cooldog.triplens.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.model.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolves the app's start destination once on launch.
 *
 * ## Start destination priority
 * 1. [StartDestination.Onboarding] — if [isOnboardingCompleteFn] returns false (first launch)
 * 2. [StartDestination.Recording] — if an active recording session exists (killed mid-recording)
 * 3. [StartDestination.TripList] — default
 *
 * ## Why lambdas, not direct dependencies?
 * Both [getActiveSessionFn] and [isOnboardingCompleteFn] are lambdas so unit tests supply
 * pure functions without constructing a real [com.cooldog.triplens.repository.SessionRepository]
 * or DataStore. The lambda maps 1:1 to the real call sites in [com.cooldog.triplens.di.AndroidModule].
 *
 * ## Why injectable [ioDispatcher]?
 * Both functions perform IO (SQLite and DataStore). Accepting [ioDispatcher] lets tests pass a
 * test dispatcher so `advanceUntilIdle()` controls all coroutines deterministically.
 */
class AppViewModel(
    private val getActiveSessionFn: () -> Session?,
    private val isOnboardingCompleteFn: suspend () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface StartDestination {
        data object Loading : StartDestination
        data object Onboarding : StartDestination
        data object TripList : StartDestination
        data object Recording : StartDestination
    }

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    /**
     * True while a recording session is active. Initialized from the startup DB check.
     * Updated at runtime via [onSessionActiveChanged] when RecordingViewModel starts/stops.
     */
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    /** Called by RecordingScreen when a session starts or stops to update the bottom-nav pulse. */
    fun onSessionActiveChanged(isActive: Boolean) {
        Log.d(TAG, "onSessionActiveChanged: isActive=$isActive")
        _isSessionActive.value = isActive
    }

    init {
        Log.d(TAG, "init: resolving start destination")
        viewModelScope.launch {
            withContext(ioDispatcher) {
                try {
                    if (!isOnboardingCompleteFn()) {
                        Log.i(TAG, "init: onboarding not complete → Onboarding")
                        _startDestination.value = StartDestination.Onboarding
                        return@withContext
                    }
                    val active = getActiveSessionFn()
                    val isActive = active != null
                    _isSessionActive.value = isActive
                    val resolved = if (isActive) StartDestination.Recording else StartDestination.TripList
                    Log.i(TAG, "init: resolved to $resolved (sessionId=${active?.id})")
                    _startDestination.value = resolved
                } catch (e: Exception) {
                    // DB or DataStore errors are non-fatal; default to TripList.
                    Log.e(TAG, "Failed to resolve start destination", e)
                    _startDestination.value = StartDestination.TripList
                }
            }
        }
    }

    companion object {
        private const val TAG = "TripLens/AppViewModel"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :composeApp:testDebugUnitTest --tests "com.cooldog.triplens.ui.AppViewModelTest"
```
Expected: PASS (7/7 — 4 existing + 3 new)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppViewModel.kt \
        composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/AppViewModelTest.kt
git commit -m "feat(task12): add Onboarding start destination to AppViewModel"
```

---

## Task 4: OnboardingRoute + nav wiring in App.kt + AppNavGraph stub

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/navigation/AppRoutes.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`

- [ ] **Step 1: Add `OnboardingRoute` to `AppRoutes.kt`**

Append after the existing route objects (no existing entries change):
```kotlin
/** Shown once on first launch for permission onboarding. Not included in BottomNavBar. */
@Serializable
object OnboardingRoute
```

- [ ] **Step 2: Replace the full content of `App.kt`**

```kotlin
package com.cooldog.triplens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.cooldog.triplens.navigation.OnboardingRoute
import com.cooldog.triplens.navigation.RecordingRoute
import com.cooldog.triplens.navigation.TripListRoute
import com.cooldog.triplens.ui.AppNavGraph
import com.cooldog.triplens.ui.AppViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Root composable. Resolves start destination via [AppViewModel] and delegates to [AppNavGraph].
 *
 * [rememberNavController] is called unconditionally so the NavController is stable across
 * [startDest] transitions (moving it inside a branch would recreate it on Loading → resolved,
 * wiping the back stack).
 *
 * [appViewModel] is passed to [AppNavGraph] so screens (e.g. RecordingScreen) can call
 * [AppViewModel.onSessionActiveChanged] without injecting a second ViewModel instance.
 */
@Composable
fun App() {
    MaterialTheme {
        val appViewModel: AppViewModel = koinViewModel()
        val startDest by appViewModel.startDestination.collectAsState()
        val isSessionActive by appViewModel.isSessionActive.collectAsState()
        val navController = rememberNavController()

        when (startDest) {
            AppViewModel.StartDestination.Loading -> {
                Box(modifier = Modifier.fillMaxSize())
            }
            else -> {
                val resolvedStart = when (startDest) {
                    AppViewModel.StartDestination.Onboarding -> OnboardingRoute
                    AppViewModel.StartDestination.Recording  -> RecordingRoute
                    else                                     -> TripListRoute
                }
                AppNavGraph(
                    navController = navController,
                    startDestination = resolvedStart,
                    isSessionActive = isSessionActive,
                    appViewModel = appViewModel,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Update `AppNavGraph.kt` — add `appViewModel` parameter, `OnboardingRoute` stub, and imports**

Add `appViewModel: AppViewModel` to the function signature:
```kotlin
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: Any,
    isSessionActive: Boolean,
    appViewModel: AppViewModel,        // ← new: passed down to screens that need it
) {
```

Add the `OnboardingRoute` composable inside `NavHost` (before `composable<TripListRoute>`):
```kotlin
composable<OnboardingRoute> {
    // Stub — replaced with real OnboardingScreen in Task 6.
    OnboardingScreenStub()
}
```

Add the stub private composable:
```kotlin
@Composable
private fun OnboardingScreenStub() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Onboarding Screen")
    }
}
```

`OnboardingRoute` must NOT appear in `showBottomBar` — it is not a top-level tab destination.
The existing `showBottomBar` logic is unchanged (only TripList, Recording, Settings).

Add new imports at the top of `AppNavGraph.kt`:
```kotlin
import com.cooldog.triplens.navigation.OnboardingRoute
import com.cooldog.triplens.ui.AppViewModel
```

- [ ] **Step 4: Build to verify compilation**

```
./gradlew :composeApp:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/navigation/AppRoutes.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/App.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt
git commit -m "feat(task12): add OnboardingRoute and wire appViewModel through NavGraph"
```

---

## Task 5: OnboardingViewModel + tests (TDD)

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModel.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModelTest.kt`:

```kotlin
package com.cooldog.triplens.ui.onboarding

import com.cooldog.triplens.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [OnboardingViewModel].
 *
 * Uses [FakeAppPreferences] — no Android context, no real DataStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private class FakeAppPreferences : AppPreferences {
        var onboardingComplete = false
        override suspend fun isOnboardingComplete() = onboardingComplete
        override suspend fun setOnboardingComplete() { onboardingComplete = true }
    }

    private fun buildViewModel(prefs: FakeAppPreferences = FakeAppPreferences()) =
        OnboardingViewModel(appPreferences = prefs, ioDispatcher = testDispatcher)

    @Test
    fun initialState_isShowPermissions() = runTest(testDispatcher) {
        val vm = buildViewModel()
        assertEquals(OnboardingViewModel.UiState.ShowPermissions, vm.uiState.value)
    }

    @Test
    fun onPermissionsHandled_writesOnboardingCompleteToPrefs() = runTest(testDispatcher) {
        val prefs = FakeAppPreferences()
        val vm = buildViewModel(prefs)

        vm.onPermissionsHandled()
        advanceUntilIdle()

        assertTrue(prefs.onboardingComplete, "onboardingComplete must be true after onPermissionsHandled()")
    }

    @Test
    fun onPermissionsHandled_emitsNavigateToTripListEvent() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val events = mutableListOf<OnboardingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onPermissionsHandled()
        advanceUntilIdle()

        assertEquals(listOf(OnboardingViewModel.Event.NavigateToTripList), events)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :composeApp:testDebugUnitTest --tests "com.cooldog.triplens.ui.onboarding.OnboardingViewModelTest"
```
Expected: FAIL — `OnboardingViewModel` does not exist.

- [ ] **Step 3: Create `OnboardingViewModel.kt`**

```kotlin
package com.cooldog.triplens.ui.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.data.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the first-launch permission onboarding screen.
 *
 * ## Responsibilities
 * 1. Expose [UiState.ShowPermissions] — the only UI state (the screen is shown exactly once).
 * 2. After permissions are handled (any outcome), write [AppPreferences.setOnboardingComplete]
 *    and emit [Event.NavigateToTripList] so [OnboardingScreen] navigates away.
 *
 * ## Why no permission logic here?
 * Permission launchers (ActivityResultContracts) are lifecycle-aware and must be registered in
 * a Composable. [OnboardingScreen] owns the launchers and calls [onPermissionsHandled] when done.
 */
class OnboardingViewModel(
    private val appPreferences: AppPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface UiState {
        data object ShowPermissions : UiState
    }

    sealed interface Event {
        /** All permissions handled (any outcome). Navigate away from onboarding. */
        data object NavigateToTripList : Event
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.ShowPermissions)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Channel-backed flow: each event is delivered exactly once even if the collector is slow.
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * Called by [OnboardingScreen] after the full permission sequence finishes (granted or
     * denied — optional permissions do not block completion).
     */
    fun onPermissionsHandled() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                appPreferences.setOnboardingComplete()
            }
            Log.i(TAG, "onPermissionsHandled: onboarding complete")
            _events.send(Event.NavigateToTripList)
        }
    }

    companion object {
        private const val TAG = "TripLens/OnboardingVM"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :composeApp:testDebugUnitTest --tests "com.cooldog.triplens.ui.onboarding.OnboardingViewModelTest"
```
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModel.kt \
        composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingViewModelTest.kt
git commit -m "feat(task12): add OnboardingViewModel with TDD"
```

---

## Task 6: OnboardingScreen + wire into NavGraph + register DI

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingScreen.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/di/AndroidModule.kt`

- [ ] **Step 1: Create `OnboardingScreen.kt`**

```kotlin
package com.cooldog.triplens.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-launch permission onboarding screen. Shown exactly once — [OnboardingViewModel] writes
 * the completion flag to DataStore so it is never shown again.
 *
 * ## Permission request sequence
 * Android requires ACCESS_BACKGROUND_LOCATION to be requested separately after ACCESS_FINE_LOCATION.
 * Two launchers handle this:
 *   1. [mainPermissionsLauncher]: FINE_LOCATION + COARSE_LOCATION + RECORD_AUDIO +
 *      READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION (system may batch the dialogs).
 *   2. [bgLocationLauncher]: ACCESS_BACKGROUND_LOCATION — only launched if step 1 granted fine location.
 *
 * After both launchers return (any outcome), [OnboardingViewModel.onPermissionsHandled] is called.
 * Onboarding completes regardless of which optional permissions were denied.
 *
 * ## "Open Settings" fallback
 * If fine location is permanently denied (rationale suppressed + previously requested),
 * a button to the app settings page replaces the primary Grant button.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Tracks whether we have launched the main permission request at least once.
    // shouldShowRequestPermissionRationale() returns false both on first launch AND after
    // permanent denial — this flag distinguishes the two cases.
    var permissionsRequested by remember { mutableStateOf(false) }
    var locationPermanentlyDenied by remember { mutableStateOf(false) }

    // Step 2: background location (launched only after fine location is granted).
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Any outcome → mark onboarding done.
        viewModel.onPermissionsHandled()
    }

    // Step 1: all non-background permissions.
    val mainPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionsRequested = true
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Fine location denied. Detect permanent denial: rationale suppressed after a request.
            val canShowRationale = activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ?: true
            if (!canShowRationale) {
                locationPermanentlyDenied = true
            }
            viewModel.onPermissionsHandled()
        }
    }

    // Navigate away when ViewModel emits completion.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingViewModel.Event.NavigateToTripList -> onComplete()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "TripLens", style = MaterialTheme.typography.displaySmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "To record your trips, TripLens needs the following permissions:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        PermissionRow(
            icon = Icons.Default.LocationOn,
            title = "Location (Always)",
            description = "Required to track your trip in the background",
        )
        PermissionRow(
            icon = Icons.Default.Mic,
            title = "Microphone",
            description = "For recording voice notes during your trip",
        )
        PermissionRow(
            icon = Icons.Default.Photo,
            title = "Photos & Media",
            description = "To auto-index photos taken during your trip",
        )

        Spacer(modifier = Modifier.height(40.dp))

        if (locationPermanentlyDenied) {
            Text(
                text = "Location permission was denied. Please enable it in Settings to use TripLens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Settings")
            }
        } else {
            Button(
                onClick = {
                    mainPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.ACCESS_MEDIA_LOCATION,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant Permissions")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "You can change these later in Settings",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: Replace `OnboardingScreenStub` in `AppNavGraph.kt` with real screen**

Replace the stub composable inside `NavHost`:
```kotlin
// REMOVE:
composable<OnboardingRoute> {
    OnboardingScreenStub()
}

// REPLACE WITH:
composable<OnboardingRoute> {
    val onboardingViewModel: OnboardingViewModel = koinViewModel()
    OnboardingScreen(
        viewModel = onboardingViewModel,
        onComplete = {
            navController.navigate(TripListRoute) {
                // Remove OnboardingRoute from the back stack so Back doesn't return to it.
                popUpTo<OnboardingRoute> { inclusive = true }
            }
        },
    )
}
```

Delete the `OnboardingScreenStub` private composable function entirely.

Add imports:
```kotlin
import org.koin.androidx.compose.koinViewModel
import com.cooldog.triplens.ui.onboarding.OnboardingScreen
import com.cooldog.triplens.ui.onboarding.OnboardingViewModel
```

- [ ] **Step 3: Update `AndroidModule.kt` — add AppPreferences singleton, update AppViewModel, add OnboardingViewModel**

In `AndroidModule.kt`, add after the existing `single<PlatformFileSystem>` binding:

```kotlin
// AppPreferences — DataStore wrapper. Must be a singleton: DataStore must not be opened twice.
single<AppPreferences> { DataStoreAppPreferences(androidContext()) }
```

Update the existing `AppViewModel` viewModel registration to pass `isOnboardingCompleteFn`:
```kotlin
viewModel {
    AppViewModel(
        getActiveSessionFn = { get<SessionRepository>().getActiveSession() },
        isOnboardingCompleteFn = { get<AppPreferences>().isOnboardingComplete() },
    )
}
```

Add after the AppViewModel block:
```kotlin
// OnboardingViewModel — first-launch permission walkthrough.
viewModel {
    OnboardingViewModel(appPreferences = get())
}
```

Add imports at the top of `AndroidModule.kt`:
```kotlin
import com.cooldog.triplens.data.AppPreferences
import com.cooldog.triplens.data.DataStoreAppPreferences
import com.cooldog.triplens.ui.onboarding.OnboardingViewModel
```

- [ ] **Step 4: Build to verify compilation**

```
./gradlew :composeApp:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/onboarding/OnboardingScreen.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/di/AndroidModule.kt
git commit -m "feat(task12): add OnboardingScreen, wire into NavGraph, register DI"
```

---

## Task 7: RecordingViewModel + tests (TDD)

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModel.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModelTest.kt`:

```kotlin
package com.cooldog.triplens.ui.recording

import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RecordingViewModel].
 *
 * [RecordingViewModel] takes lambdas for all external calls (DB writes, service start) so tests
 * run entirely on the JVM — no Android context, Koin, or database required.
 *
 * [FIXED_EPOCH] = 0L (1970-01-01 UTC) keeps date arithmetic unambiguous across timezones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private val FIXED_EPOCH = 0L  // 1970-01-01 UTC

    // Captured call arguments.
    private var capturedGroupId: String? = null
    private var capturedGroupName: String? = null
    private var capturedGroupNow: Long? = null
    private var capturedSessionId: String? = null
    private var capturedSessionGroupId: String? = null
    private var capturedSessionName: String? = null
    private var capturedSessionStartTime: Long? = null
    private var capturedServiceSessionId: String? = null
    private var capturedServiceProfile: AccuracyProfile? = null
    private var createGroupCallCount = 0

    private fun buildViewModel() = RecordingViewModel(
        createGroupFn = { id, name, now ->
            createGroupCallCount++
            capturedGroupId = id
            capturedGroupName = name
            capturedGroupNow = now
        },
        createSessionFn = { id, groupId, name, startTime ->
            capturedSessionId = id
            capturedSessionGroupId = groupId
            capturedSessionName = name
            capturedSessionStartTime = startTime
        },
        startService = { sessionId, profile ->
            capturedServiceSessionId = sessionId
            capturedServiceProfile = profile
        },
        clock = { FIXED_EPOCH },
        ioDispatcher = testDispatcher,
    )

    @Test
    fun initialState_isIdle() = runTest(testDispatcher) {
        val vm = buildViewModel()
        assertEquals(RecordingViewModel.UiState.Idle, vm.uiState.value)
    }

    @Test
    fun onStartTapped_locationDenied_staysIdle_andEmitsShowRationale() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStartTapped(locationGranted = false)
        advanceUntilIdle()

        assertEquals(RecordingViewModel.UiState.Idle, vm.uiState.value)
        assertEquals(listOf(RecordingViewModel.Event.ShowPermissionRationale), events)
        job.cancel()
    }

    @Test
    fun onStartTapped_locationGranted_transitionsToStartingSession() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()
        assertEquals(RecordingViewModel.UiState.StartingSession, vm.uiState.value)
    }

    @Test
    fun onStartTapped_locationGranted_createsTripGroupWithTodayDate() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertNotNull(capturedGroupId)
        // epoch 0 in UTC = 1970-01-01
        assertEquals("1970-01-01", capturedGroupName, "TripGroup name must be today's date in yyyy-MM-dd UTC")
        assertEquals(FIXED_EPOCH, capturedGroupNow)
    }

    @Test
    fun onStartTapped_locationGranted_createsSessionLinkedToGroup() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertNotNull(capturedSessionId)
        assertEquals(capturedGroupId, capturedSessionGroupId, "Session groupId must match the auto-created TripGroup id")
        assertEquals("Session 1", capturedSessionName)
        assertEquals(FIXED_EPOCH, capturedSessionStartTime)
    }

    @Test
    fun onStartTapped_locationGranted_startsServiceWithStandardProfile() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertNotNull(capturedServiceSessionId)
        assertEquals(capturedSessionId, capturedServiceSessionId, "startService sessionId must match the created session id")
        assertEquals(AccuracyProfile.STANDARD, capturedServiceProfile)
    }

    @Test
    fun onStartTapped_locationGranted_emitsNavigateToActiveRecording() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val events = mutableListOf<RecordingViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onStartTapped(locationGranted = true)
        advanceUntilIdle()

        assertTrue(
            RecordingViewModel.Event.NavigateToActiveRecording in events,
            "NavigateToActiveRecording must be emitted after session creation",
        )
        job.cancel()
    }

    @Test
    fun onStartTapped_calledTwiceWhileStarting_createsOnlyOneGroup() = runTest(testDispatcher) {
        val vm = buildViewModel()

        vm.onStartTapped(locationGranted = true)
        vm.onStartTapped(locationGranted = true)  // second tap while StartingSession
        advanceUntilIdle()

        assertEquals(1, createGroupCallCount, "createGroup must be called exactly once even if tapped twice")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :composeApp:testDebugUnitTest --tests "com.cooldog.triplens.ui.recording.RecordingViewModelTest"
```
Expected: FAIL — `RecordingViewModel` does not exist.

- [ ] **Step 3: Create `RecordingViewModel.kt`**

```kotlin
package com.cooldog.triplens.ui.recording

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cooldog.triplens.platform.AccuracyProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * ViewModel for the Recording screen — drives the idle state machine.
 *
 * On "Start Recording" tap, auto-creates a [TripGroup] (name = today's UTC date) and a [Session]
 * (name = "Session 1"), then fires [LocationTrackingService] via [startService]. No group-selector
 * dialog is shown; the user renames the group after stopping the recording (Task 13).
 *
 * ## Design: lambdas instead of direct repository injection
 * [createGroupFn], [createSessionFn], and [startService] are lambdas so unit tests supply pure
 * functions without a real database or Android context. The DI module wires these to the real
 * repositories and service at runtime.
 *
 * ## Guard against double-tap
 * Once [UiState.StartingSession] is set, further [onStartTapped] calls are no-ops — preventing
 * duplicate TripGroups and Sessions if the user taps rapidly.
 */
class RecordingViewModel(
    private val createGroupFn: (id: String, name: String, now: Long) -> Unit,
    private val createSessionFn: (id: String, groupId: String, name: String, startTime: Long) -> Unit,
    private val startService: (sessionId: String, profile: AccuracyProfile) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface UiState {
        /** "Start Recording" button visible and enabled. */
        data object Idle : UiState

        /** Creating TripGroup + Session + starting service. Button shows loading indicator. */
        data object StartingSession : UiState
    }

    sealed interface Event {
        /** Session created and service started. RecordingScreen notifies AppViewModel and Task 13 handles the UI transition. */
        data object NavigateToActiveRecording : Event

        /** Location permission revoked post-onboarding. Show [PermissionRationaleDialog]. */
        data object ShowPermissionRationale : Event
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * Called when the user taps "Start Recording".
     *
     * @param locationGranted True if [android.Manifest.permission.ACCESS_FINE_LOCATION] is
     *   currently granted. If false, emits [Event.ShowPermissionRationale] and returns without
     *   starting a session.
     */
    fun onStartTapped(locationGranted: Boolean) {
        if (!locationGranted) {
            Log.w(TAG, "onStartTapped: location not granted — emitting ShowPermissionRationale")
            viewModelScope.launch { _events.send(Event.ShowPermissionRationale) }
            return
        }
        if (_uiState.value != UiState.Idle) {
            Log.d(TAG, "onStartTapped: ignored — state is ${_uiState.value}")
            return
        }
        _uiState.value = UiState.StartingSession
        Log.i(TAG, "onStartTapped: auto-creating TripGroup + Session")

        viewModelScope.launch {
            withContext(ioDispatcher) {
                val now = clock()
                val groupId = UUID.randomUUID().toString()
                val sessionId = UUID.randomUUID().toString()

                // Format today's date in UTC. Locale.US ensures consistent yyyy-MM-dd output
                // regardless of device locale (some locales use different separators).
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(now))

                createGroupFn(groupId, dateStr, now)
                Log.d(TAG, "Created TripGroup id=$groupId name=$dateStr")

                createSessionFn(sessionId, groupId, "Session 1", now)
                Log.d(TAG, "Created Session id=$sessionId groupId=$groupId")

                startService(sessionId, AccuracyProfile.STANDARD)
                Log.i(TAG, "LocationTrackingService started with sessionId=$sessionId profile=STANDARD")
            }
            _events.send(Event.NavigateToActiveRecording)
        }
    }

    companion object {
        private const val TAG = "TripLens/RecordingVM"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :composeApp:testDebugUnitTest --tests "com.cooldog.triplens.ui.recording.RecordingViewModelTest"
```
Expected: PASS (7/7)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModel.kt \
        composeApp/src/androidUnitTest/kotlin/com/cooldog/triplens/ui/recording/RecordingViewModelTest.kt
git commit -m "feat(task12): add RecordingViewModel with TDD"
```

---

## Task 8: RecordingScreen + PermissionRationaleDialog + wire into NavGraph + register DI

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/PermissionRationaleDialog.kt`
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingScreen.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/di/AndroidModule.kt`

- [ ] **Step 1: Create `PermissionRationaleDialog.kt`**

```kotlin
package com.cooldog.triplens.ui.recording

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Post-onboarding fallback shown when ACCESS_FINE_LOCATION is revoked after the app is installed.
 *
 * The normal permission flow runs once at first launch via [OnboardingScreen]. This dialog is a
 * recovery path for users who manually revoke location in Android Settings after onboarding.
 *
 * Deep-links to the app's system settings page so the user can re-enable the permission without
 * having to know how to navigate there manually.
 */
@Composable
fun PermissionRationaleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location Permission Required") },
        text = {
            Text(
                "Background location access is required to track your trip. " +
                "Please re-enable it in Settings."
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                    onDismiss()
                }
            ) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

- [ ] **Step 2: Create `RecordingScreen.kt`**

```kotlin
package com.cooldog.triplens.ui.recording

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.cooldog.triplens.navigation.SettingsRoute
import com.cooldog.triplens.ui.AppViewModel
// MapLibre 11.x uses org.maplibre.android package names (renamed from com.mapbox.mapboxsdk in ~v10).
// If the build fails with "unresolved reference: MapView", check the exact package in the SDK sources.
import org.maplibre.android.maps.MapView

/**
 * Recording screen — idle state.
 *
 * Layout:
 *  - Upper ~60%: MapLibre map (read-only in idle state; no polyline or markers yet).
 *  - Lower ~40%: large circular "Start Recording" button (96 dp) + gear icon to Settings.
 *
 * ## Permission check at tap time
 * ACCESS_FINE_LOCATION is checked when the user taps Start, not at composition time. If revoked
 * after onboarding, [PermissionRationaleDialog] is shown and recording does not start.
 *
 * ## Active state
 * This file only implements the idle state. Task 13 adds the active recording overlay
 * (polyline, media strip, voice note button, stop button) as additional [RecordingViewModel.UiState]
 * branches in this same composable.
 *
 * ## MapView lifecycle
 * [MapView] must receive Android lifecycle events when hosted in [AndroidView].
 * [DisposableEffect] attaches a [LifecycleEventObserver] that forwards ON_START/RESUME/PAUSE/STOP
 * to MapView. [MapView.onDestroy] is called in [DisposableEffect.onDispose] to release GL resources.
 */
@Composable
fun RecordingScreen(
    navController: NavHostController,
    appViewModel: AppViewModel,
    viewModel: RecordingViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showRationaleDialog by remember { mutableStateOf(false) }

    // MapView is created once and held for the lifetime of this composable.
    // onCreate(null) must be called before getMapAsync — null bundle is acceptable here because
    // the idle screen does not need to restore camera state after process death.
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    // Forward Android lifecycle events to MapView.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START  -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView.onPause()
                Lifecycle.Event.ON_STOP   -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // Collect one-shot events from ViewModel.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RecordingViewModel.Event.NavigateToActiveRecording -> {
                    // Notify AppViewModel so the Record tab icon starts pulsing.
                    appViewModel.onSessionActiveChanged(true)
                    // Task 13: this branch will trigger the active recording UI overlay.
                    // For now UiState.StartingSession already shows a loading indicator.
                }
                RecordingViewModel.Event.ShowPermissionRationale -> {
                    showRationaleDialog = true
                }
            }
        }
    }

    if (showRationaleDialog) {
        PermissionRationaleDialog(onDismiss = { showRationaleDialog = false })
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Map area (~60% of screen height) ──────────────────────────────────────
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f),
            update = { mv ->
                mv.getMapAsync { map ->
                    // Idle state: display the base map only. Task 13 adds the GPS polyline.
                    map.setStyle("https://tiles.openfreemap.org/styles/bright")
                }
            },
        )

        // ── Bottom panel (~40% of screen height) ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
                .padding(16.dp),
        ) {
            // Gear icon — top-right corner navigates to Settings.
            IconButton(
                onClick = { navController.navigate(SettingsRoute) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }

            // Start Recording button — large circle, centered in the panel.
            Button(
                onClick = {
                    val locationGranted = PermissionChecker.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PermissionChecker.PERMISSION_GRANTED
                    viewModel.onStartTapped(locationGranted)
                },
                enabled = uiState == RecordingViewModel.UiState.Idle,
                shape = CircleShape,
                modifier = Modifier
                    .size(96.dp)
                    .align(Alignment.Center),
            ) {
                if (uiState == RecordingViewModel.UiState.StartingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "Start\nRecording",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Replace `RecordingScreenStub` in `AppNavGraph.kt` with real screen**

Replace:
```kotlin
composable<RecordingRoute> {
    RecordingScreenStub()
}
```
With:
```kotlin
composable<RecordingRoute> {
    val recordingViewModel: RecordingViewModel = koinViewModel()
    RecordingScreen(
        navController = navController,
        appViewModel = appViewModel,
        viewModel = recordingViewModel,
    )
}
```

Delete the `RecordingScreenStub` private composable function entirely.

Add imports:
```kotlin
import com.cooldog.triplens.ui.recording.RecordingScreen
import com.cooldog.triplens.ui.recording.RecordingViewModel
```

- [ ] **Step 4: Register `RecordingViewModel` in `AndroidModule.kt`**

Add after the `OnboardingViewModel` block:

```kotlin
// RecordingViewModel — idle state machine for the Recording screen.
// startService lambda uses ContextCompat.startForegroundService to ensure correct behaviour
// on API 26+ where startService() alone does not allow foreground promotion.
viewModel {
    val ctx = androidContext()
    RecordingViewModel(
        createGroupFn = { id, name, now ->
            get<TripRepository>().createGroup(id, name, now)
        },
        createSessionFn = { id, groupId, name, startTime ->
            get<SessionRepository>().createSession(id, groupId, name, startTime)
        },
        startService = { sessionId, profile ->
            val intent = Intent(ctx, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
                putExtra(LocationTrackingService.EXTRA_SESSION_ID, sessionId)
                putExtra(LocationTrackingService.EXTRA_ACCURACY_PROFILE, profile.name)
            }
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        },
    )
}
```

Add imports:
```kotlin
import android.content.Intent
import com.cooldog.triplens.repository.TripRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.service.LocationTrackingService
import com.cooldog.triplens.ui.recording.RecordingViewModel
```

- [ ] **Step 5: Build to verify compilation**

```
./gradlew :composeApp:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Run all unit tests**

```
./gradlew :composeApp:testDebugUnitTest
```
Expected: PASS — AppViewModelTest (7), OnboardingViewModelTest (3), RecordingViewModelTest (7)

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/PermissionRationaleDialog.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingScreen.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt \
        composeApp/src/androidMain/kotlin/com/cooldog/triplens/di/AndroidModule.kt
git commit -m "feat(task12): add RecordingScreen idle state, PermissionRationaleDialog, wire DI"
```

---

## Task 9: AppPreferences instrumented test

**Files:**
- Create: `composeApp/src/androidInstrumentedTest/kotlin/com/cooldog/triplens/data/AppPreferencesTest.kt`

This test requires a real Android context (DataStore reads the filesystem). Run on a device or emulator.

- [ ] **Step 1: Create `AppPreferencesTest.kt`**

```kotlin
package com.cooldog.triplens.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instrumented tests for [DataStoreAppPreferences].
 *
 * Requires a connected device or emulator — DataStore reads/writes the real filesystem.
 *
 * Note on test isolation: DataStore persists to the same file across test methods within the
 * same process run. The write test ([afterSetOnboardingComplete_isOnboardingComplete_returnsTrue])
 * will cause the read test to see `true` if they run in the same process without reinstalling.
 * In CI each test run installs a fresh APK so the DataStore file starts clean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = DataStoreAppPreferences(context)

    @Test
    fun isOnboardingComplete_returnsFalseByDefault() = runTest {
        // This passes on a fresh install. If the write test has already run in this process,
        // DataStore retains its value and this test will fail — that is expected behaviour.
        assertFalse(prefs.isOnboardingComplete(), "Default value must be false on a fresh install")
    }

    @Test
    fun afterSetOnboardingComplete_isOnboardingComplete_returnsTrue() = runTest {
        prefs.setOnboardingComplete()
        assertTrue(
            prefs.isOnboardingComplete(),
            "isOnboardingComplete must return true after setOnboardingComplete()",
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/androidInstrumentedTest/kotlin/com/cooldog/triplens/data/AppPreferencesTest.kt
git commit -m "test(task12): add AppPreferences instrumented test"
```

- [ ] **Step 3: Note on running instrumented tests**

Connect a device or start an emulator, then run:
```
./gradlew :composeApp:connectedDebugAndroidTest --tests "com.cooldog.triplens.data.AppPreferencesTest"
```
These are not expected to run in every local build cycle — only on-device or in CI.
