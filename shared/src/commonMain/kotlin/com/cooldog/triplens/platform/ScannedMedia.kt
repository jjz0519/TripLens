package com.cooldog.triplens.platform

import com.cooldog.triplens.model.MediaType

/**
 * Represents one photo or video discovered by [GalleryScanner.scanNewMedia].
 *
 * This is the raw output of a MediaStore scan. The caller maps it to a
 * [com.cooldog.triplens.model.MediaReference] via [MediaRefRepository.insertIfNotExists].
 *
 * @param contentUri    Android content:// URI string (e.g. "content://media/external/images/media/42").
 *                      Used as the deduplication key in [MediaRefRepository] — two items with the
 *                      same contentUri are the same file and must not be double-inserted.
 * @param filename      DISPLAY_NAME from MediaStore (e.g. "IMG_20260329_141500.jpg").
 * @param capturedAt    DATE_TAKEN epoch milliseconds UTC. Maps to [MediaReference.capturedAt].
 * @param mediaType     Whether this is a PHOTO or VIDEO — determines which MediaStore table it
 *                      came from. Passed through to [MediaReference.type].
 * @param originalLat   GPS latitude from EXIF in decimal degrees, or null if absent or if
 *                      [ACCESS_MEDIA_LOCATION] permission is not granted. Will be null for most
 *                      videos (MP4 rarely carries GPS EXIF).
 * @param originalLng   GPS longitude from EXIF, same nullability rules as [originalLat].
 */
data class ScannedMedia(
    val contentUri: String,
    val filename: String,
    val capturedAt: Long,
    val mediaType: MediaType,
    val originalLat: Double?,
    val originalLng: Double?
)
