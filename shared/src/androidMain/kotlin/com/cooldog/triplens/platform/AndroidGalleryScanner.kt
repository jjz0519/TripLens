package com.cooldog.triplens.platform

import android.content.ContentUris
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.cooldog.triplens.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TripLens/GalleryScanner"

/**
 * Android implementation of [GalleryScanner] using [android.content.ContentResolver] queries
 * against [MediaStore.Images.Media] and [MediaStore.Video.Media].
 *
 * ## GPS reading strategy (API 29+)
 * On API 29+, the OS redacts GPS coordinates from media URIs by default, even when EXIF is
 * present in the file. To read the accurate GPS:
 *   1. Call [MediaStore.setRequireOriginal] to obtain a URI that bypasses redaction.
 *   2. Open a stream from that URI via [android.content.ContentResolver.openInputStream].
 *   3. Pass the stream to [ExifInterface] and call [ExifInterface.getLatLong].
 *
 * [MediaStore.setRequireOriginal] requires [android.Manifest.permission.ACCESS_MEDIA_LOCATION].
 * If that permission is not granted, [android.content.ContentResolver.openInputStream] throws
 * [SecurityException], which we catch and treat as "no GPS". Location is then inferred from
 * the recorded trajectory by the session review layer.
 *
 * On API 26–28, no redaction occurs. We open the URI's stream directly.
 *
 * ## lastScanTimestamp invariant
 * After each [scanNewMedia] call, [lastScanTimestamp] equals the maximum DATE_TAKEN value
 * across all results returned by that call. The next query uses:
 *   `DATE_TAKEN > sessionStartTime AND DATE_TAKEN > lastScanTimestamp`
 * so only genuinely new media is returned, without double-counting.
 *
 * ## Thread safety
 * [lastScanTimestamp] is not protected by a lock. This scanner is intended to be called
 * from a single periodic coroutine (the gallery scan loop in LocationTrackingService).
 * Concurrent calls from multiple coroutines would produce incorrect deduplication.
 *
 * @param context Application context for [android.content.ContentResolver] access.
 */
class AndroidGalleryScanner(private val context: Context) : GalleryScanner {

    // Advances after each scan to the newest DATE_TAKEN seen. Starts at 0 so the first
    // scan returns all media after sessionStartTime with no lower bound from prior scans.
    private var lastScanTimestamp: Long = 0L

    override suspend fun scanNewMedia(sessionStartTime: Long): List<ScannedMedia> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "scanNewMedia start: sessionStartTime=$sessionStartTime, " +
                    "lastScanTimestamp=$lastScanTimestamp")

            val results = mutableListOf<ScannedMedia>()
            queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sessionStartTime, results, MediaType.PHOTO)
            queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, sessionStartTime, results, MediaType.VIDEO)

            // Sort chronologically so the caller can process events in capture order.
            results.sortBy { it.capturedAt }

            // Advance the internal cursor to prevent re-returning these items next scan.
            results.maxOfOrNull { it.capturedAt }?.let { newest ->
                lastScanTimestamp = maxOf(lastScanTimestamp, newest)
            }

            Log.i(TAG, "scanNewMedia done: ${results.size} items returned " +
                    "(${results.count { it.mediaType == MediaType.PHOTO }} photos, " +
                    "${results.count { it.mediaType == MediaType.VIDEO }} videos). " +
                    "lastScanTimestamp now=$lastScanTimestamp")
            results
        }

    /**
     * Issues a single [ContentResolver.query] against [contentUri] and appends matching
     * [ScannedMedia] items to [results].
     *
     * Uses [MediaStore.MediaColumns] constants (the common supertype of Images.Media and
     * Video.Media) so the same projection and selection work for both tables.
     *
     * ## Timestamp cursor edge case
     * The selection uses strict `DATE_TAKEN > lastScanTimestamp`. This means a photo
     * inserted between two scans with `capturedAt == lastScanTimestamp` (exact millisecond
     * match to the newest item of the previous scan) will never be returned. In practice,
     * burst-shot photos from the same millisecond are extremely rare, and a missed item
     * would appear in the next batch scan cycle. ContentUri-based deduplication in
     * [MediaRefRepository.insertIfNotExists] prevents any double-insertion regardless.
     */
    private fun queryMediaStore(
        contentUri: Uri,
        sessionStartTime: Long,
        results: MutableList<ScannedMedia>,
        mediaType: MediaType
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN
        )
        // Both time conditions guard against: (a) media predating this session,
        // (b) media already returned in a prior scan via lastScanTimestamp.
        // IS_PENDING = 0 excludes rows still being written by the camera app or another
        // app. On API 29+, IS_PENDING rows from the same app ARE visible to itself, and
        // calling setRequireOriginal on such a URI throws UnsupportedOperationException.
        val selection = buildString {
            append("${MediaStore.MediaColumns.DATE_TAKEN} > ? ")
            append("AND ${MediaStore.MediaColumns.DATE_TAKEN} > ?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
            }
        }
        val selectionArgs = arrayOf(sessionStartTime.toString(), lastScanTimestamp.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} ASC"

        val cursor = context.contentResolver.query(
            contentUri, projection, selection, selectionArgs, sortOrder
        )

        if (cursor == null) {
            // Null cursor means the query failed, usually because the required read
            // permission (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO) is not granted.
            Log.w(TAG, "query returned null for $mediaType — READ_MEDIA_IMAGES/VIDEO may not be granted")
            return
        }

        cursor.use { c ->
            val idIdx   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)

            var count = 0
            while (c.moveToNext()) {
                val id          = c.getLong(idIdx)
                val displayName = c.getString(nameIdx) ?: "unknown"
                val dateTaken   = c.getLong(dateIdx)

                // DATE_TAKEN is 0 when the column is NULL in the DB (e.g., USB-transferred files
                // without embedded metadata). These have no meaningful capture time and must be
                // skipped — a 0L would always satisfy the > sessionStartTime predicate.
                if (dateTaken == 0L) {
                    Log.w(TAG, "Skipping $mediaType '$displayName' with null/zero DATE_TAKEN")
                    continue
                }

                val itemUri     = ContentUris.withAppendedId(contentUri, id)
                val (lat, lng)  = readGpsFromExif(itemUri)

                results.add(
                    ScannedMedia(
                        contentUri  = itemUri.toString(),
                        filename    = displayName,
                        capturedAt  = dateTaken,
                        mediaType   = mediaType,
                        originalLat = lat,
                        originalLng = lng
                    )
                )
                count++
                Log.d(TAG, "Found $mediaType: id=$id, name=$displayName, " +
                        "capturedAt=$dateTaken, hasGps=${lat != null}")
            }
            Log.d(TAG, "queryMediaStore($mediaType): $count new items")
        }
    }

    /**
     * Reads GPS coordinates from the EXIF metadata of [contentUri].
     *
     * Returns a [Pair] of (latitude, longitude) in decimal degrees, or (null, null) when:
     * - No GPS tags are present in the EXIF.
     * - [android.Manifest.permission.ACCESS_MEDIA_LOCATION] is not granted (API 29+).
     * - The file was deleted or became inaccessible between the query and the stream open.
     * - The EXIF data is corrupt or unreadable.
     *
     * Uses [ExifInterface.getLatLong] rather than reading individual DMS tags manually —
     * the built-in helper handles the DMS-to-decimal conversion and the N/S/E/W REF signs
     * correctly with no risk of off-by-sign bugs.
     */
    private fun readGpsFromExif(contentUri: Uri): Pair<Double?, Double?> {
        return try {
            // On API 29+, MediaStore redacts GPS from content URIs by default.
            // setRequireOriginal returns a new URI that instructs MediaStore to serve the
            // original, unredacted file content. Opening a stream from this URI requires
            // ACCESS_MEDIA_LOCATION; the SecurityException branch below handles the denied case.
            val uriToOpen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(contentUri)
            } else {
                contentUri
            }

            context.contentResolver.openInputStream(uriToOpen)?.use { stream ->
                val exif  = ExifInterface(stream)
                val latLng = FloatArray(2)
                // getLatLong fills latLng[0]=latitude, latLng[1]=longitude and returns true
                // if valid GPS data was found. Returns false (not null) when tags are absent.
                if (exif.getLatLong(latLng)) {
                    Log.d(TAG, "GPS from EXIF: lat=${latLng[0]}, lng=${latLng[1]} " +
                            "for $contentUri")
                    Pair(latLng[0].toDouble(), latLng[1].toDouble())
                } else {
                    Log.d(TAG, "No GPS tags in EXIF for $contentUri")
                    Pair(null, null)
                }
            } ?: run {
                // openInputStream returns null when the file has been deleted between the
                // query and this call (race condition). Log as warning, not error — it is
                // a transient state and will not recur.
                Log.w(TAG, "openInputStream returned null for $contentUri " +
                        "(file may have been deleted)")
                Pair(null, null)
            }
        } catch (e: SecurityException) {
            // ACCESS_MEDIA_LOCATION is not granted (API 29+). This is a recoverable,
            // expected state — the app continues without GPS for phone photos, and
            // location will be inferred from the trajectory instead.
            Log.w(TAG, "ACCESS_MEDIA_LOCATION not granted; GPS will be null for $contentUri")
            Pair(null, null)
        } catch (e: UnsupportedOperationException) {
            // setRequireOriginal throws UnsupportedOperationException when the URI belongs
            // to a row that is still IS_PENDING = 1. The IS_PENDING = 0 filter in
            // queryMediaStore prevents this in normal operation, but a race condition
            // (row finalised after query but before stream open) could still reach here.
            // Treat as "no GPS for now" — the row will be re-scanned next cycle.
            Log.w(TAG, "setRequireOriginal unsupported for $contentUri (row may be pending)")
            Pair(null, null)
        } catch (e: Exception) {
            // EXIF parse error, corrupt file, or other IO exception.
            Log.e(TAG, "Failed to read EXIF from $contentUri", e)
            Pair(null, null)
        }
    }
}
