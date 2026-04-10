package com.cooldog.triplens.i18n

import android.content.Context
import android.util.Log

/**
 * Android `actual` implementation of [Strings].
 *
 * ## Context initialisation
 * Call [Strings.init] once in `TripLensApplication.onCreate()` — before `startKoin` so that
 * any `commonMain` code executed during DI graph construction can safely access these values.
 * The Application context is used (not Activity) so there is no lifecycle concern.
 *
 * ## Resource lookup
 * Resources are resolved by name via [Context.resources.getIdentifier] rather than by
 * the generated `R` class because `shared` module cannot reference `composeApp`'s `R`:
 * the dependency direction is composeApp → shared, not the reverse.
 *
 * ## Non-context constants
 * [exportReadmeContent] and [gpxCreatorTag] are compile-time constants that do not vary by
 * locale, so they do not require a Context. They are defined here rather than in `commonMain`
 * so that the README text is co-located with the other export strings and easy to find.
 */
actual object Strings {

    private var appCtx: Context? = null

    /**
     * Must be called once from `TripLensApplication.onCreate()` before any `commonMain` code
     * reads [Strings] properties. Stores the Application context — safe to hold as a static
     * reference because Application outlives all other components.
     */
    fun init(context: Context) {
        appCtx = context.applicationContext
        Log.i(TAG, "init: Strings context initialised (package=${context.packageName})")
    }

    /** Throws [IllegalStateException] with a clear message if [init] was not called yet. */
    private val ctx: Context
        get() = checkNotNull(appCtx) {
            "Strings.init(context) must be called in TripLensApplication.onCreate() before use"
        }

    /**
     * Resolves a string resource by name, falling back to [fallback] if not found.
     * Logs a warning if the resource key is missing — indicates a misspelled key or a
     * translation file that was not updated after a key rename.
     */
    private fun getString(name: String, fallback: String): String {
        val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        if (resId == 0) {
            Log.w(TAG, "getString: resource key '$name' not found in '${ctx.packageName}', using fallback '$fallback'")
            return fallback
        }
        return ctx.getString(resId)
    }

    actual val defaultTripNameFormat: String
        get() = getString("default_trip_name_format", "yyyy-MM-dd")

    actual val sessionDefaultName: String
        get() = getString("session_default_name", "Session")

    /**
     * README.txt content written into every `.triplens` archive.
     *
     * Intentionally a compile-time constant in English — the desktop import tool must be able
     * to parse this file regardless of the user's locale. Not backed by a string resource.
     */
    actual val exportReadmeContent: String
        get() = """TripLens Export — schema_version: 1
=====================================

Contents:
  index.json              Full trip data (groups, sessions, track points, notes)
  tracks/session_*.gpx    GPX 1.1 track files per session, with transport mode extensions
  notes/note_*.m4a        Voice note audio recordings

To import into TripLens Desktop, open this .triplens file from File → Import.

Format spec: https://github.com/triplens/triplens/blob/main/docs/TripLens-TDD.md
"""

    /**
     * Value of the GPX `creator` attribute — the product name, never localised.
     * Kept here (rather than hardcoded in [com.cooldog.triplens.export.GpxWriter]) so all
     * product-name constants live in one place.
     */
    actual val gpxCreatorTag: String
        get() = "TripLens"

    private const val TAG = "TripLens/Strings"
}
