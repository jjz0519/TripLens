package com.cooldog.triplens.i18n

/**
 * Platform-specific string constants used by `commonMain` code (export pipeline, session naming).
 *
 * UI strings are accessed directly via Android's `stringResource()` in Compose screens — this
 * object is only for strings needed outside of the Compose UI layer (e.g. [ExportUseCase] writing
 * README.txt, [GpxWriter] embedding the creator tag, and ViewModel/repository defaults that are
 * set before any UI is available).
 *
 * ## Keys
 * - [defaultTripNameFormat] — `SimpleDateFormat` pattern for the auto-generated TripGroup name.
 * - [sessionDefaultName] — Default display name for a new Session (shown before the user renames).
 * - [exportReadmeContent] — Full text of the `README.txt` written into every `.triplens` archive.
 *   Intentionally always English so the desktop import tool can parse it regardless of locale.
 * - [gpxCreatorTag] — Product name embedded in the GPX `creator` attribute. Never translated.
 *
 * ## Platform access
 * The `actual` object in `androidMain` holds a static Application context so it can call
 * `Context.getString(R.string.*)`. Call [com.cooldog.triplens.i18n.Strings] (via `actual`) —
 * specifically `Strings.init(context)` — once in `Application.onCreate()` before any
 * `commonMain` code that reads these values runs.
 */
expect object Strings {
    /** `SimpleDateFormat` pattern used to auto-name new TripGroups (e.g. "2026-04-11"). */
    val defaultTripNameFormat: String

    /** Default Session name shown before the user renames it (e.g. "Session" / "记录"). */
    val sessionDefaultName: String

    /**
     * Content of `README.txt` written into every `.triplens` export archive.
     * Always English — the desktop import tool expects English regardless of the user's locale.
     */
    val exportReadmeContent: String

    /**
     * Value of the `creator` attribute in the GPX `<gpx>` element.
     * Product name — never localized.
     */
    val gpxCreatorTag: String
}
