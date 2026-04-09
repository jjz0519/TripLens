package com.cooldog.triplens.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.cooldog.triplens.ui.recording.RecordingScreen
import com.cooldog.triplens.ui.recording.RecordingViewModel
import com.cooldog.triplens.ui.sessionreview.SessionReviewScreen
import com.cooldog.triplens.ui.sessionreview.SessionReviewViewModel
import com.cooldog.triplens.ui.tripdetail.TripDetailScreen
import com.cooldog.triplens.ui.tripdetail.TripDetailViewModel
import com.cooldog.triplens.ui.triplist.TripListScreen
import com.cooldog.triplens.ui.triplist.TripListViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

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
            // Instant tab switching — no fade-in/fade-out when clicking bottom nav buttons.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
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
                val tripListViewModel: TripListViewModel = koinViewModel()
                TripListScreen(
                    viewModel = tripListViewModel,
                    onGroupClick = { groupId -> navController.navigate(TripDetailRoute(groupId)) },
                )
            }
            composable<RecordingRoute> {
                val recordingViewModel: RecordingViewModel = koinViewModel()
                RecordingScreen(
                    navController = navController,
                    appViewModel = appViewModel,
                    viewModel = recordingViewModel,
                )
            }
            composable<SettingsRoute> {
                SettingsScreenStub()
            }
            composable<TripDetailRoute> { backStackEntry ->
                val route: TripDetailRoute = backStackEntry.toRoute()
                val tripDetailViewModel: TripDetailViewModel =
                    koinViewModel(parameters = { parametersOf(route.groupId) })
                TripDetailScreen(
                    viewModel = tripDetailViewModel,
                    onSessionClick = { sessionId ->
                        navController.navigate(SessionReviewRoute(sessionId))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<SessionReviewRoute> { backStackEntry ->
                val route: SessionReviewRoute = backStackEntry.toRoute()
                val viewModel: SessionReviewViewModel =
                    koinViewModel(parameters = { parametersOf(route.sessionId) })
                SessionReviewScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Bottom navigation bar shown only on top-level destinations (TripList, Recording, Settings).
 * Hidden on detail screens (TripDetail, SessionReview) so the user can focus on detail content.
 *
 * The Record tab icon is static (not pulsing). [isSessionActive] is kept in the API for
 * potential future use (e.g. badge dot on the Record tab).
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
// Stub screens — empty Box placeholders to be replaced in future tasks.
// TripListScreenStub and TripDetailScreenStub have been replaced by real screens.
// ------------------------------------------------------------------

@Composable
private fun SettingsScreenStub() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Settings Screen")
    }
}

