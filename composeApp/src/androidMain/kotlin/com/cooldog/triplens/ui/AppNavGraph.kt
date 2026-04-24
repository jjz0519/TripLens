package com.cooldog.triplens.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooldog.triplens.R
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
import com.cooldog.triplens.ui.settings.SettingsScreen
import com.cooldog.triplens.ui.settings.SettingsViewModel
import com.cooldog.triplens.ui.triplist.TripListScreen
import com.cooldog.triplens.ui.triplist.TripListViewModel
import com.cooldog.triplens.ui.theme.LocalBiophilicColors
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
                val settingsViewModel: SettingsViewModel = koinViewModel()
                SettingsScreen(viewModel = settingsViewModel)
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
 * Biophilic design (Task 21):
 * - Active tab: pill with mossPale2 background and mossDeep icon/text.
 * - Record tab while recording: pill tinted recordRed @ 14% alpha; pulsing 8dp red dot beside icon.
 * - Inactive tab: transparent pill, ink2 icon/text.
 * - Container: bio.surface background with a 1dp top divider in bio.line2.
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

    if (!showBottomBar) return

    // Tab descriptor: route object, string label, outlined icon.
    val tabs: List<Triple<Any, String, ImageVector>> = listOf(
        Triple(TripListRoute,  stringResource(R.string.nav_trips),    Icons.Outlined.Map),
        Triple(RecordingRoute, stringResource(R.string.nav_record),   Icons.Outlined.Mic),
        Triple(SettingsRoute,  stringResource(R.string.nav_settings), Icons.Outlined.Settings),
    )

    val bio = LocalBiophilicColors.current

    Surface(color = bio.surface) {
        Column {
            // 1dp top border separating the nav bar from screen content.
            HorizontalDivider(color = bio.line2, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEach { (route, label, icon) ->
                    val active = currentDestination?.hasRoute(route::class) ?: false
                    val isRecord = route == RecordingRoute

                    // Pill background and content color follow the biophilic spec:
                    // recording-active Record tab uses a red tint; other active tabs use moss;
                    // inactive tabs are fully transparent so only the text/icon are tinted.
                    val pillBg = when {
                        active && isRecord && isSessionActive -> bio.recordRed.copy(alpha = 0.14f)
                        active -> bio.mossPale2
                        else -> Color.Transparent
                    }
                    val contentColor = when {
                        active && isRecord && isSessionActive -> bio.recordRed
                        active -> bio.mossDeep
                        else -> bio.ink2
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { navigateTopLevel(navController, route) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Pill containing icon (+ optional pulsing dot for active recording).
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(pillBg)
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = contentColor,
                                modifier = Modifier.size(22.dp),
                            )

                            // Pulsing red dot — only shown while a recording session is active
                            // on the Record tab. Alpha animates 1f → 0.3f at 700ms to signal
                            // background activity without being visually distracting.
                            if (isRecord && isSessionActive) {
                                val infiniteTransition = rememberInfiniteTransition(
                                    label = "recordDotPulse"
                                )
                                val dotAlpha by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 0.3f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 700),
                                        repeatMode = RepeatMode.Reverse,
                                    ),
                                    label = "recordDotAlpha",
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(bio.recordRed.copy(alpha = dotAlpha)),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = label,
                            color = contentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
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


