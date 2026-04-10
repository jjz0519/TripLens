package com.cooldog.triplens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.cooldog.triplens.R
import com.cooldog.triplens.di.androidModule
import com.cooldog.triplens.di.sharedModule
import com.cooldog.triplens.i18n.Strings
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

private const val TAG = "TripLens/Application"

// Notification channel constants shared with LocationTrackingService.
// Defined here because the channel must be created before startForeground() is called,
// and the Application is guaranteed to be created before any Service by Android.
const val RECORDING_CHANNEL_ID = "triplens_recording"

/**
 * Application subclass that initialises Koin and the recording notification channel.
 *
 * ## Koin startup
 * [startKoin] is called here so the DI graph is available to all components (Activities,
 * Services, ViewModels) from the moment they are created. The [sharedModule] +
 * [androidModule] together form the complete DI graph for the Android app.
 *
 * ## Notification channel
 * Android 8.0+ requires a notification channel to exist before [startForeground()] can
 * be called. Creating it here (rather than in [LocationTrackingService.onCreate]) avoids
 * repeated creation attempts on every service restart (the call is idempotent anyway, but
 * it is conceptually Application-level infrastructure, not Service-level).
 */
class TripLensApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: initialising Strings, Koin, and notification channel")

        // Initialise the shared-module Strings context holder before startKoin so that any
        // commonMain code that runs during DI graph construction can safely access string values.
        Strings.init(this)

        // MapLibre requires a one-time getInstance() call before any MapView is created.
        // An empty API key is correct for OpenFreeMap (no key required).
        // WellKnownTileServer.MapLibre matches the tile URL format we use.
        MapLibre.getInstance(this, "", WellKnownTileServer.MapLibre)

        startKoin {
            androidContext(this@TripLensApplication)
            modules(sharedModule, androidModule)
        }

        createRecordingNotificationChannel()
    }

    /**
     * Creates the notification channel used by [LocationTrackingService] for the
     * foreground recording notification.
     *
     * [NotificationManager.createNotificationChannel] is idempotent — safe to call on every
     * app start. Using IMPORTANCE_LOW: silent, no sound, no heads-up. This respects the
     * background recording UX (users should not be interrupted while the app silently tracks).
     */
    private fun createRecordingNotificationChannel() {
        val channel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            getString(R.string.notification_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_recording_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel '$RECORDING_CHANNEL_ID' created")
    }
}
