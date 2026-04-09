package com.cooldog.triplens.data

/**
 * Gallery scan interval options (Task 16 — Settings screen).
 *
 * The chosen value is persisted in DataStore and read by [LocationTrackingService] at the
 * start of each new recording session. Changing the preference while a session is already
 * recording has no effect on the current session — only the next one picks it up.
 */
enum class ScanInterval(val seconds: Int) {
    SHORT(30),
    STANDARD(60),
    LONG(120),
}
