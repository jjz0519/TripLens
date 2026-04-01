package com.cooldog.triplens.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.cooldog.triplens.navigation.OnboardingRoute
import com.cooldog.triplens.navigation.RecordingRoute
import com.cooldog.triplens.navigation.SessionReviewRoute
import com.cooldog.triplens.navigation.SettingsRoute
import com.cooldog.triplens.navigation.TripDetailRoute
import com.cooldog.triplens.navigation.TripListRoute
import com.cooldog.triplens.ui.onboarding.OnboardingScreen
import com.cooldog.triplens.ui.onboarding.OnboardingViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Root NavHost for the app. Wraps all screens in a [Scaffold] with a conditional [AppBottomNavBar].
 *
 * @param navController  The NavHostController created by [App].
 * @param startDestination  One of the route objects resolved by [AppViewModel] at startup.
 * @param isSessionActive  True when a recording session is currently active; drives the pulsing
 *   animation on the Record tab. Provided by [AppViewModel.isSessionActive].
 * @param appViewModel  Passed down to screens that need to call [AppViewModel] methods (e.g.
 *   RecordingScreen calling onSessionActiveChanged) without injecting a second ViewModel instance.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: Any,
    isSessionActive: Boolean,
    // Passed to real screens in Task 6 (OnboardingScreen) and Task 8 (RecordingScreen).
    appViewModel: AppViewModel,
) {
    Scaffold(
        bottomBar = {
            AppBottomNavBar(
                navController = navController,
                isSessionActive = isSessionActive,
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
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
            composable<TripListRoute> {
                TripListScreenStub(
                    onGroupClick = { groupId -> navController.navigate(TripDetailRoute(groupId)) },
                )
            }
            composable<RecordingRoute> {
                RecordingScreenStub()
            }
            composable<SettingsRoute> {
                SettingsScreenStub()
            }
            composable<TripDetailRoute> { backStackEntry ->
                val route: TripDetailRoute = backStackEntry.toRoute()
                TripDetailScreenStub(
                    groupId = route.groupId,
                    onSessionClick = { sessionId ->
                        navController.navigate(SessionReviewRoute(sessionId))
                    },
                )
            }
            composable<SessionReviewRoute> { backStackEntry ->
                val route: SessionReviewRoute = backStackEntry.toRoute()
                SessionReviewScreenStub(sessionId = route.sessionId)
            }
        }
    }
}

/**
 * Bottom navigation bar shown only on top-level destinations (TripList, Recording, Settings).
 * Hidden on detail screens (TripDetail, SessionReview) so the user can focus on detail content.
 *
 * The Record tab icon pulses (alpha 1.0 → 0.4, 800 ms cycle) when [isSessionActive] is true.
 * [rememberInfiniteTransition] is called unconditionally (Compose rules), but its animated
 * value is only applied when [isSessionActive] is true.
 */
@Composable
private fun AppBottomNavBar(
    navController: NavHostController,
    isSessionActive: Boolean,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.let {
        it.hasRoute<TripListRoute>() || it.hasRoute<RecordingRoute>() || it.hasRoute<SettingsRoute>()
    } ?: false

    // rememberInfiniteTransition must be called unconditionally regardless of showBottomBar
    // or isSessionActive — calling it conditionally violates Compose's composition rules.
    val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
    val pulsingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordPulseAlpha",
    )
    val recordIconAlpha = if (isSessionActive) pulsingAlpha else 1f

    if (showBottomBar) {
        NavigationBar {
            NavigationBarItem(
                icon = { Icon(Icons.Default.Map, contentDescription = "Trips") },
                label = { Text("Trips") },
                selected = currentDestination?.hasRoute<TripListRoute>() ?: false,
                onClick = { navigateTopLevel(navController, TripListRoute) },
            )
            NavigationBarItem(
                icon = {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Record",
                        modifier = Modifier.alpha(recordIconAlpha),
                    )
                },
                label = { Text("Record") },
                selected = currentDestination?.hasRoute<RecordingRoute>() ?: false,
                onClick = { navigateTopLevel(navController, RecordingRoute) },
            )
            NavigationBarItem(
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
                selected = currentDestination?.hasRoute<SettingsRoute>() ?: false,
                onClick = { navigateTopLevel(navController, SettingsRoute) },
            )
        }
    }
}

/**
 * Navigates to a top-level destination following the standard bottom-nav back-stack contract:
 * - [popUpTo] TripListRoute (the fixed home tab) so only one instance of each top-level screen
 *   lives in the back stack at a time. Using [TripListRoute] instead of
 *   `graph.findStartDestination()` is intentional: when the app starts on [RecordingRoute] (active
 *   session at launch), `findStartDestination()` returns RecordingRoute, causing bottom-nav
 *   transitions to pop back to the recording screen rather than the logical home tab.
 * - [saveState] / [restoreState] = true so each tab's scroll position and state is preserved when
 *   switching tabs and returning (standard Material behavior).
 * - [launchSingleTop] = true avoids re-creating the screen if already on that tab.
 */
private fun navigateTopLevel(navController: NavHostController, route: Any) {
    navController.navigate(route) {
        popUpTo<TripListRoute> {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

// ------------------------------------------------------------------
// Stub screens — empty Box placeholders to be replaced in Tasks 12–16.
// The onXxx callbacks are wired to real navigation now so back-stack
// behavior is correct before the real screens exist.
// ------------------------------------------------------------------

@Composable
private fun TripListScreenStub(onGroupClick: (groupId: String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Trip List Screen")
    }
}

@Composable
private fun RecordingScreenStub() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Recording Screen")
    }
}

@Composable
private fun SettingsScreenStub() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Settings Screen")
    }
}

@Composable
private fun TripDetailScreenStub(groupId: String, onSessionClick: (sessionId: String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Trip Detail\ngroupId: $groupId")
    }
}

@Composable
private fun SessionReviewScreenStub(sessionId: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Session Review\nsessionId: $sessionId")
    }
}
