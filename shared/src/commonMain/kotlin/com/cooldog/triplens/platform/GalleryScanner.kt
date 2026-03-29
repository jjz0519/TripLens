package com.cooldog.triplens.platform

/**
 * Platform interface for scanning device media that was captured since session start.
 *
 * The Android implementation ([AndroidGalleryScanner] in androidMain) queries
 * MediaStore.Images and MediaStore.Video. A future iOS implementation would query
 * PHPhotoLibrary via the PhotoKit framework.
 *
 * ## Deduplication contract
 * The implementation maintains an internal `lastScanTimestamp`. Each call to
 * [scanNewMedia] advances the cursor to the most recent DATE_TAKEN seen, so a
 * subsequent call with no new media returns an empty list. This removes the need
 * for the caller to track what has already been seen.
 *
 * ## Permission requirements
 * - `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`: required for any results to be returned.
 * - `ACCESS_MEDIA_LOCATION`: required for [ScannedMedia.originalLat]/[ScannedMedia.originalLng]
 *   to be non-null on API 29+. Without it, GPS is redacted by the OS and the fields are null.
 *   Location will then be inferred from the recorded GPS trajectory.
 *
 * ## Lifecycle
 * Create one instance per session. The internal timestamp cursor is reset when a new
 * instance is created. Inject via Koin (Task 9) — Koin should scope this to the session.
 */
interface GalleryScanner {

    /**
     * Returns all photos and videos with DATE_TAKEN > [sessionStartTime] that have not
     * been returned by a previous call to this method on this scanner instance.
     *
     * The implementation dispatches to [kotlinx.coroutines.Dispatchers.IO] internally,
     * so this is safe to call from any coroutine dispatcher.
     *
     * @param sessionStartTime Epoch milliseconds — only media captured after this time is
     *                         returned, regardless of the internal timestamp cursor.
     * @return List of [ScannedMedia] sorted by [ScannedMedia.capturedAt] ascending.
     *         Empty list if no new media was found.
     */
    suspend fun scanNewMedia(sessionStartTime: Long): List<ScannedMedia>
}
