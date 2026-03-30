package com.cooldog.triplens.navigation

import kotlinx.serialization.Serializable

/**
 * Navigation route objects for the entire app.
 *
 * All routes are @Serializable so Navigation Compose 2.8 type-safe navigation can encode them
 * into the back stack without manual string concatenation or argument parsing.
 *
 * Top-level destinations (TripListRoute, RecordingRoute, SettingsRoute) appear in the
 * BottomNavBar and have no arguments.
 *
 * Detail destinations (TripDetailRoute, SessionReviewRoute) take a single String ID argument
 * and do not appear in the BottomNavBar.
 */

/** Top-level: trip list with TripGroup cards. Entry point when no session is active. */
@Serializable
object TripListRoute

/** Top-level: active (or idle) recording screen. Entry point when a session is active at launch. */
@Serializable
object RecordingRoute

/** Top-level: app settings. */
@Serializable
object SettingsRoute

/** Detail: TripGroup detail with session list. Reached from TripListRoute. */
@Serializable
data class TripDetailRoute(val groupId: String)

/** Detail: full-screen session review with map and timeline. Reached from TripDetailRoute. */
@Serializable
data class SessionReviewRoute(val sessionId: String)
