# Task 12 Design — Recording Screen (Idle State) + First-Launch Onboarding

**Date:** 2026-04-01  
**Task ref:** `docs/tasks-mobile.md` § Task 12  
**Status:** Approved by human owner

---

## Overview

Task 12 delivers two things:
1. A **first-launch onboarding screen** that requests all required permissions once
2. The **idle state of the Recording Screen** — a map + "Start Recording" button that auto-creates a TripGroup and Session on tap

Task 13 will add the active recording state to `RecordingScreen.kt`.

---

## Decisions & Divergences from Original Spec

| Original spec | Approved change | Reason |
|---|---|---|
| GroupSelectorDialog + SelectingGroup/CreatingGroup states | Removed | Auto-create TripGroup on tap — simpler UX |
| Permission flow triggered from RecordingScreen on each tap | Dedicated onboarding screen on first launch | One-time, upfront, clear |
| All 5 permissions are hard blockers | Only location is a hard blocker; audio/gallery degrade gracefully | User preference |
| Idle state: button only | Idle state: map (upper ~60%) + button (lower ~40%) | Visual continuity with active state |

---

## Architecture

### New Files

```
composeApp/src/androidMain/kotlin/com/cooldog/triplens/
├── data/
│   └── AppPreferences.kt                  ← DataStore wrapper (onboardingComplete flag)
└── ui/
    ├── onboarding/
    │   ├── OnboardingScreen.kt            ← Single-screen permission walkthrough
    │   └── OnboardingViewModel.kt         ← Permission sequence + DataStore write
    └── recording/
        ├── RecordingScreen.kt             ← Idle state (map + Start button)
        ├── RecordingViewModel.kt          ← Idle state machine + auto session creation
        └── PermissionRationaleDialog.kt   ← Post-onboarding location-revoked fallback
```

### Modified Files

| File | Change |
|---|---|
| `navigation/AppRoutes.kt` | Add `OnboardingRoute` object |
| `ui/AppNavGraph.kt` | Add `OnboardingRoute` composable; wire `RecordingRoute` to `RecordingScreen` |
| `ui/AppViewModel.kt` | Read `onboardingComplete` from DataStore; add `Onboarding` to `StartDestination` |
| `di/AndroidModule.kt` | Register `OnboardingViewModel`, `RecordingViewModel` as `viewModel {}` |

---

## Component Details

### `AppPreferences` (DataStore wrapper)

Single file, single key for now (Task 16 adds more):

```kotlin
object Keys {
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
}

suspend fun isOnboardingComplete(): Boolean  // default: false
suspend fun setOnboardingComplete()          // writes true
```

Injected as a singleton in `AndroidModule`.

---

### `AppViewModel` — Start Destination Logic

Reads `onboardingComplete` and `getActiveSession()` in parallel on IO dispatcher at init:

```
onboardingComplete == false               → StartDestination.Onboarding
onboardingComplete == true, session null  → StartDestination.TripList
onboardingComplete == true, session set   → StartDestination.Recording
```

`StartDestination` sealed interface gains a new `Onboarding` variant.

---

### `OnboardingViewModel`

**States:**
```
Loading       → checking DataStore (brief)
ShowPermissions → display permission list + Grant button
NavigatingToApp → permissions handled, DataStore written, navigate away
```

**Permission request sequence** (triggered by "Grant Permissions" button):
1. `ACCESS_FINE_LOCATION` — hard required; if permanently denied, show "Open Settings" button; button stays enabled so user can retry after returning from Settings
2. `ACCESS_BACKGROUND_LOCATION` — requested after fine location granted (Android OS requirement)
3. `RECORD_AUDIO` — optional; if denied, onboarding continues (voice notes degraded)
4. `READ_MEDIA_IMAGES` — optional; if denied, onboarding continues (gallery scan degraded)
5. `ACCESS_MEDIA_LOCATION` — optional; if denied, onboarding continues (EXIF location degraded)

After all requests are handled (any outcome), writes `onboardingComplete = true` to DataStore and emits a `NavigateToApp` one-shot event. Onboarding is never shown again regardless of optional permission outcomes.

---

### `OnboardingScreen`

Single screen layout:
- App name / logo at top
- List of 5 permissions with icon + one-line explanation each
- Prominent "Grant Permissions" primary button
- Fine print: "You can change these later in Settings"
- If location is permanently denied: "Open Settings" button replaces "Grant Permissions"

No multi-step wizard — one screen, one button.

---

### `RecordingViewModel`

**States:**
```kotlin
sealed interface RecordingUiState {
    object Idle : RecordingUiState
    object StartingSession : RecordingUiState
}
```

**Events (one-shot):**
```kotlin
sealed interface RecordingEvent {
    object NavigateToActiveRecording : RecordingEvent
    object ShowPermissionRationale : RecordingEvent   // location revoked post-onboarding
}
```

**`onStartTapped(locationGranted: Boolean)`:**
- `locationGranted == false` → emit `ShowPermissionRationale`, stay `Idle`
- `locationGranted == true` → transition to `StartingSession`, then:
  1. Create `TripGroup`: `id = UUID`, `name = "yyyy-MM-dd"` (today's date), `now = currentTimeMillis()`
  2. Create `Session`: `id = UUID`, `groupId`, `name = "Session 1"`, `startTime = now`, `status = RECORDING`
  3. Start `LocationTrackingService` via `ACTION_START` Intent with `sessionId` + `accuracyProfile`
  4. Emit `NavigateToActiveRecording`

Auto-creates with no dialog. Default group name is the date; user renames after stopping (Task 13).

`RecordingScreen` observes the `NavigateToActiveRecording` event and calls both `navController.navigate(...)` and `appViewModel.onSessionActiveChanged(true)` — ViewModels must not inject other ViewModels.

**Dependencies injected:**
- `TripRepository`
- `SessionRepository`
- `Context` (to start service)

`AccuracyProfile` is hardcoded to `STANDARD` for now; Task 16 (Settings) will wire it to DataStore.

---

### `RecordingScreen` (idle state)

```
┌─────────────────────────────┐
│                         ⚙️  │  ← gear icon, top-right → SettingsRoute
│                             │
│      MapLibre map           │  ← ~60% height
│  (centered on last location │
│   or device default)        │
│                             │
├─────────────────────────────┤
│                             │
│     ◉  Start Recording      │  ← large circular primary button, ~96dp
│                             │  ← ~40% height
└─────────────────────────────┘
```

- Map is read-only in idle state (no polyline, no markers yet)
- Map attempts to center on last known location via `FusedLocationProviderClient.lastLocation`; falls back to a default center if unavailable
- Gear icon navigates to `SettingsRoute` (stub, implemented in Task 16)
- When `uiState == StartingSession`, the Start button shows a loading indicator and is disabled

---

### `PermissionRationaleDialog`

Shown only when location permission is revoked post-onboarding (not during onboarding).

- Message: "Background location access is required to track your trip. Please re-enable it in Settings."
- Buttons: "Open Settings" (deep-link to `ACTION_APPLICATION_DETAILS_SETTINGS`) + "Cancel"
- Does not re-trigger the onboarding flow

---

## Tests

All unit tests (JVM, no emulator) unless noted.

### `OnboardingViewModel`
1. Initial state is `ShowPermissions`
2. `onPermissionsHandled()` → writes `onboardingComplete = true` to fake DataStore → emits `NavigateToApp`
3. If DataStore already returns `true` on init → immediately emits `NavigateToApp`

### `AppViewModel` (additions)
4. `onboardingComplete = false` → `startDestination == Onboarding`
5. `onboardingComplete = true`, no active session → `startDestination == TripList`
6. `onboardingComplete = true`, active session → `startDestination == Recording`

### `RecordingViewModel`
7. Initial state is `Idle`
8. `onStartTapped(locationGranted = true)` → transitions to `StartingSession`, TripGroup and Session written to fake repositories, emits `NavigateToActiveRecording`
9. `onStartTapped(locationGranted = false)` → stays `Idle`, emits `ShowPermissionRationale`
10. Auto-created TripGroup name matches today's `"yyyy-MM-dd"` date string
11. Auto-created Session has `status == RECORDING` and non-null `startTime`

### `AppPreferences` (Android instrumented)
12. Write `onboardingComplete = true`, read back → `true`; default is `false`
