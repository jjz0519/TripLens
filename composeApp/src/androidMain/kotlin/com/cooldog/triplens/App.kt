package com.cooldog.triplens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.cooldog.triplens.ui.common.SessionRecoveryDialog
import com.cooldog.triplens.ui.theme.TripLensTheme
import org.koin.androidx.compose.koinViewModel

/**
 * Root composable. Resolves start destination via [AppViewModel] and delegates to [AppNavGraph].
 *
 * ## Session recovery dialog
 * When [AppViewModel.recoverySession] is non-null (orphaned recording session detected at
 * startup), a [SessionRecoveryDialog] is shown as an overlay on top of the resolved screen.
 * The dialog only appears after the start destination is resolved (i.e., not during Loading)
 * so it is never shown on a blank screen.
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
    TripLensTheme {
        val appViewModel: AppViewModel = koinViewModel()
        val startDest       by appViewModel.startDestination.collectAsState()
        val isSessionActive by appViewModel.isSessionActive.collectAsState()
        val recoverySession by appViewModel.recoverySession.collectAsState()
        val navController = rememberNavController()

        // Box allows the recovery dialog to overlay the resolved screen without disrupting the
        // nav graph (which must be stable so the back stack is not recreated).
        Box(modifier = Modifier.fillMaxSize()) {
            when (startDest) {
                AppViewModel.StartDestination.Loading -> {
                    // Blank while the startup IO coroutine resolves the destination.
                    Box(modifier = Modifier.fillMaxSize())
                }
                else -> {
                    val resolvedStart = when (startDest) {
                        AppViewModel.StartDestination.Onboarding -> OnboardingRoute
                        AppViewModel.StartDestination.Recording  -> RecordingRoute
                        else                                     -> TripListRoute
                    }
                    AppNavGraph(
                        navController    = navController,
                        startDestination = resolvedStart,
                        isSessionActive  = isSessionActive,
                        appViewModel     = appViewModel,
                    )
                }
            }

            // Show recovery dialog only after loading resolves, so it overlays a real screen.
            // recoverySession is cleared to null by onRecoveryResume / onRecoveryDiscard.
            if (recoverySession != null && startDest != AppViewModel.StartDestination.Loading) {
                SessionRecoveryDialog(
                    onResume  = { appViewModel.onRecoveryResume() },
                    onDiscard = { appViewModel.onRecoveryDiscard() },
                )
            }
        }
    }
}
