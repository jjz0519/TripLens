package com.cooldog.triplens.platform

/**
 * Platform-agnostic location accuracy priority. Each value maps to a
 * platform-specific constant in the [LocationProvider] actual:
 * - Android: com.google.android.gms.location.Priority.*
 * - iOS (future): CLLocationAccuracy.*
 */
enum class LocationPriority {
    HIGH_ACCURACY,
    BALANCED,
    LOW_POWER
}
