package com.cooldog.triplens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.cooldog.triplens.navigation.RecordingRoute
import com.cooldog.triplens.navigation.TripListRoute
import com.cooldog.triplens.ui.AppNavGraph
import com.cooldog.triplens.ui.AppViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Root composable. Resolves the start destination via [AppViewModel] and delegates to [AppNavGraph].
 *
 * While [AppViewModel.StartDestination.Loading] is active (the IO query is in-flight), an empty
 * [Box] is shown. The query typically completes in <10 ms so the blank state is imperceptible;
 * no splash screen is needed at this stage.
 *
 * [rememberNavController] is called unconditionally so the NavController is allocated at a stable
 * position in the composition tree. Placing it inside the `else` branch would cause it to be
 * discarded and recreated whenever [startDest] transitions through [AppViewModel.StartDestination.Loading],
 * wiping the entire back stack.
 */
@Composable
fun App() {
    // TODO(Task 12+): Replace MaterialTheme with TripLensTheme once custom colors/typography exist.
    MaterialTheme {
        val viewModel: AppViewModel = koinViewModel()
        val startDest by viewModel.startDestination.collectAsState()
        val isSessionActive by viewModel.isSessionActive.collectAsState()
        // Unconditional: NavController must not be recreated if startDest oscillates.
        val navController = rememberNavController()

        when (startDest) {
            AppViewModel.StartDestination.Loading -> {
                // Blank screen while the IO coroutine queries SessionRepository.
                Box(modifier = Modifier.fillMaxSize())
            }
            else -> {
                AppNavGraph(
                    navController = navController,
                    startDestination = if (startDest == AppViewModel.StartDestination.Recording) {
                        RecordingRoute
                    } else {
                        TripListRoute
                    },
                    isSessionActive = isSessionActive,
                )
            }
        }
    }
}
