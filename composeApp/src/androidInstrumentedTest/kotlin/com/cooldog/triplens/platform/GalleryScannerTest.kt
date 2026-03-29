package com.cooldog.triplens.platform

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.cooldog.triplens.MainActivity
import com.cooldog.triplens.model.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TripLens/GalleryScannerTest"

/**
 * Instrumented tests for [AndroidGalleryScanner].
 *
 * ## Test JPEG generation
 * Rather than committing binary test assets, each test helper creates a real JPEG
 * in-process using [Bitmap.compress] and, where needed, writes GPS EXIF via
 * [ExifInterface]. The JPEG is written to [MediaStore] using the [IS_PENDING]
 * pattern (API 29+) which makes the row invisible during write and visible only
 * after finalisation.
 *
 * ## MediaStore cleanup
 * All URIs inserted during a test are tracked in [insertedUris] and deleted
 * in [tearDown] to avoid polluting subsequent runs and the emulator gallery.
 *
 * ## Android 14+ requirements
 * 1. READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION granted at runtime → [permissionRule]
 * 2. App in the foreground for ACCESS_MEDIA_LOCATION to be honoured → [activityRule]
 */
@RunWith(AndroidJUnit4::class)
class GalleryScannerTest {

    // Rule order: permissions granted first (order=0), then activity launched (order=1).
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var context: Context

    // A fresh scanner is created in @Before so lastScanTimestamp starts at 0L for every test.
    private lateinit var scanner: AndroidGalleryScanner

    // All MediaStore rows inserted by this test run — deleted in @After.
    private val insertedUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scanner = AndroidGalleryScanner(context)
    }

    @After
    fun tearDown() {
        // Best-effort cleanup — don't let a delete failure mask a real test failure.
        insertedUris.forEach { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete test URI $uri: ${e.message}")
            }
        }
        insertedUris.clear()
    }

    // -------------------------------------------------------------------------
    // Test 1 — basic photo detection
    // -------------------------------------------------------------------------

    /**
     * A photo inserted into MediaStore after the session start time is returned
     * by [scanNewMedia] with the correct content URI, filename, and capturedAt.
     */
    @Test
    fun scanNewMedia_returnsPhotoInsertedAfterSessionStart() = runTest {
        val sessionStart = System.currentTimeMillis() - 10_000L
        val photoTaken   = System.currentTimeMillis() - 5_000L
        insertTestPhoto(dateTaken = photoTaken, displayName = "test_basic.jpg", withGps = false)

        val results = scanner.scanNewMedia(sessionStart)

        // Filter by filename to avoid accidentally matching a pre-existing photo on the
        // emulator that also falls within the [sessionStart, now] window.
        val photo = results.firstOrNull { it.filename == "test_basic.jpg" }
        assertNotNull("Expected 'test_basic.jpg' in scan results", photo)
        assertTrue("contentUri should start with content://",
            photo!!.contentUri.startsWith("content://"))
        // EXIF DateTimeOriginal has only second precision. Samsung re-reads it from the
        // JPEG when IS_PENDING is finalised, truncating sub-second millis. Allow 1s delta.
        assertTrue("capturedAt should be within 1s of DATE_TAKEN",
            kotlin.math.abs(photo.capturedAt - photoTaken) < 1_000L)
        assertEquals(MediaType.PHOTO, photo.mediaType)
    }

    // -------------------------------------------------------------------------
    // Test 2 — sessionStartTime filter
    // -------------------------------------------------------------------------

    /**
     * A photo whose DATE_TAKEN is before [sessionStartTime] must never be returned,
     * even if it was inserted into MediaStore after the scanner was created.
     */
    @Test
    fun scanNewMedia_ignoresPhotoTakenBeforeSessionStart() = runTest {
        val sessionStart = System.currentTimeMillis()
        val photoTaken   = sessionStart - 60_000L   // 1 minute before session start

        insertTestPhoto(dateTaken = photoTaken, displayName = "test_old.jpg")

        val results = scanner.scanNewMedia(sessionStart)

        assertTrue(
            "Photo with DATE_TAKEN before sessionStartTime must not be returned",
            results.none { it.capturedAt == photoTaken }
        )
    }

    // -------------------------------------------------------------------------
    // Test 3 — deduplication via lastScanTimestamp
    // -------------------------------------------------------------------------

    /**
     * A second call to [scanNewMedia] with no new photos since the first call must
     * return an empty list. The scanner advances its internal cursor after each scan.
     */
    @Test
    fun scanNewMedia_secondCallWithNoNewPhotosReturnsEmpty() = runTest {
        val sessionStart = System.currentTimeMillis() - 10_000L
        insertTestPhoto(dateTaken = System.currentTimeMillis() - 5_000L,
            displayName = "test_dedup.jpg")

        val firstResults  = scanner.scanNewMedia(sessionStart)
        val secondResults = scanner.scanNewMedia(sessionStart)

        assertTrue("First scan should return 'test_dedup.jpg'",
            firstResults.any { it.filename == "test_dedup.jpg" })
        // Assert specifically that the already-seen photo is not re-returned, rather than
        // asserting the entire list is empty. Pre-existing emulator media finalized between
        // the two scans could legitimately appear in the second results.
        assertTrue("Second scan must not re-return the already-seen photo",
            secondResults.none { it.filename == "test_dedup.jpg" })
    }

    // -------------------------------------------------------------------------
    // Test 4 — GPS present in EXIF
    // -------------------------------------------------------------------------

    /**
     * When [ACCESS_MEDIA_LOCATION] is granted and the JPEG has GPS EXIF tags,
     * [ScannedMedia.originalLat] and [ScannedMedia.originalLng] are non-null and
     * within a small delta of the embedded coordinates.
     *
     * Embedded GPS: 22.3193°N, 114.1694°E (Hong Kong — consistent with service tests).
     */
    @Test
    fun scanNewMedia_photoWithGpsExif_populatesLatLng() = runTest {
        val sessionStart = System.currentTimeMillis() - 10_000L
        insertTestPhoto(dateTaken = System.currentTimeMillis() - 5_000L,
            displayName = "test_gps.jpg", withGps = true)

        val results = scanner.scanNewMedia(sessionStart)

        // Filter by filename to avoid accidentally asserting GPS on a pre-existing emulator
        // photo that happens to fall within the session window.
        val photo = results.firstOrNull { it.filename == "test_gps.jpg" }
        assertNotNull("Expected 'test_gps.jpg' in scan results", photo)
        assertNotNull("originalLat should be populated when GPS EXIF is present",
            photo!!.originalLat)
        assertNotNull("originalLng should be populated when GPS EXIF is present",
            photo.originalLng)
        // Allow ±0.001° tolerance for DMS rational-to-decimal conversion rounding.
        assertEquals("Latitude should match embedded GPS within tolerance",
            22.3193, photo.originalLat!!, 0.001)
        assertEquals("Longitude should match embedded GPS within tolerance",
            114.1694, photo.originalLng!!, 0.001)
    }

    // -------------------------------------------------------------------------
    // Test 5 — no GPS in EXIF
    // -------------------------------------------------------------------------

    /**
     * When a JPEG has no GPS EXIF tags, [ScannedMedia.originalLat] and
     * [ScannedMedia.originalLng] must both be null. Location will later be
     * inferred from the GPS trajectory.
     */
    @Test
    fun scanNewMedia_photoWithoutGpsExif_nullLatLng() = runTest {
        val sessionStart = System.currentTimeMillis() - 10_000L
        insertTestPhoto(dateTaken = System.currentTimeMillis() - 5_000L,
            displayName = "test_no_gps.jpg", withGps = false)

        val results = scanner.scanNewMedia(sessionStart)

        val photo = results.firstOrNull { it.mediaType == MediaType.PHOTO }
        assertNotNull("Expected a PHOTO in results", photo)
        assertNull("originalLat must be null when no GPS tags are in EXIF",
            photo!!.originalLat)
        assertNull("originalLng must be null when no GPS tags are in EXIF",
            photo.originalLng)
    }

    // -------------------------------------------------------------------------
    // Test 6 — video files are returned
    // -------------------------------------------------------------------------

    /**
     * Videos inserted into MediaStore are returned alongside photos. The [mediaType]
     * field identifies them as [MediaType.VIDEO].
     */
    @Test
    fun scanNewMedia_videoFilesAreReturned() = runTest {
        val sessionStart = System.currentTimeMillis() - 10_000L
        insertTestVideo(dateTaken = System.currentTimeMillis() - 5_000L,
            displayName = "test_video.mp4")

        val results = scanner.scanNewMedia(sessionStart)

        assertTrue(
            "At least one VIDEO should be returned by scanNewMedia",
            results.any { it.mediaType == MediaType.VIDEO }
        )
        val video = results.first { it.mediaType == MediaType.VIDEO }
        assertTrue("Video contentUri should start with content://",
            video.contentUri.startsWith("content://"))
        assertEquals("test_video.mp4", video.filename)
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Inserts a test photo into MediaStore and registers its URI for cleanup in [tearDown].
     *
     * The JPEG is generated in-process via [createJpeg]. Crucially, [TAG_DATETIME_ORIGINAL]
     * is always written into the EXIF. On Samsung One UI and other OEM MediaProviders, when
     * an IS_PENDING row is finalised (IS_PENDING 1→0), the provider re-reads the file's EXIF
     * to update MediaStore metadata columns. Without a [TAG_DATETIME_ORIGINAL] tag, it sets
     * DATE_TAKEN = 0 — which the scanner's null-DATE_TAKEN guard skips. With the tag, the
     * provider reads the correct date and the scanner finds the row.
     */
    private fun insertTestPhoto(
        dateTaken: Long,
        displayName: String = "triplens_test_${dateTaken}.jpg",
        withGps: Boolean = false
    ): Uri {
        val jpegBytes = createJpeg(dateTaken, withGps)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTaken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = checkNotNull(
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ) { "ContentResolver.insert returned null for test photo '$displayName'" }
        insertedUris.add(uri)

        context.contentResolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
            ?: Log.w(TAG, "openOutputStream returned null for $uri — bytes not written")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
        }

        Log.d(TAG, "Inserted test photo: uri=$uri, displayName=$displayName, " +
                "dateTaken=$dateTaken, withGps=$withGps")
        return uri
    }

    /**
     * Inserts a test video into MediaStore and registers its URI for cleanup.
     *
     * IS_PENDING is intentionally NOT used here. MP4 containers embed creation time inside
     * the moov/mvhd box, which we cannot write without a full MP4 encoder. On Samsung One UI,
     * finalising an IS_PENDING row triggers a re-scan of the file's MP4 metadata; when the
     * file contains no valid moov box, Samsung zeros out DATE_TAKEN — causing the scanner to
     * skip it. Inserting without IS_PENDING leaves DATE_TAKEN from ContentValues intact.
     */
    private fun insertTestVideo(
        dateTaken: Long,
        displayName: String = "triplens_test_${dateTaken}.mp4"
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, dateTaken)
            // IS_PENDING deliberately omitted — see KDoc above.
        }

        val uri = checkNotNull(
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ) { "ContentResolver.insert returned null for test video '$displayName'" }
        insertedUris.add(uri)

        Log.d(TAG, "Inserted test video: uri=$uri, displayName=$displayName, dateTaken=$dateTaken")
        return uri
    }

    /**
     * Creates a 1×1 white JPEG with [TAG_DATETIME_ORIGINAL] always set, and optionally
     * GPS EXIF tags.
     *
     * ## Why DateTimeOriginal is required on all JPEGs
     * On Samsung One UI (and some other OEM MediaProviders), finalising an IS_PENDING row
     * triggers a re-scan of the file's EXIF. If DateTimeOriginal is absent, the provider
     * resets DATE_TAKEN to 0, causing the scanner's zero-timestamp guard to skip the row.
     * Embedding the tag ensures DATE_TAKEN is correctly preserved after finalisation.
     *
     * ## GPS encoding
     * Uses DMS rational strings compatible with [ExifInterface.setAttribute] (API 24+),
     * not [ExifInterface.setLatLong] (API 33+), for minSdk 26 compatibility.
     * - 22.3193° → "22/1,19/1,948/100" N
     * - 114.1694° → "114/1,10/1,984/100" E
     * [ExifInterface.getLatLong] decodes these to decimal degrees with < 0.001° error.
     */
    private fun createJpeg(dateTaken: Long, withGps: Boolean): ByteArray {
        val tempFile = File.createTempFile("triplens_test_", ".jpg", context.cacheDir)
        return try {
            // Step 1: Write a baseline 1×1 JPEG via Bitmap.compress.
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
            bitmap.setPixel(0, 0, Color.WHITE)
            FileOutputStream(tempFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()

            // Step 2: Write EXIF. ExifInterface's write API requires a file path.
            val exif = ExifInterface(tempFile.absolutePath)
            // DateTimeOriginal must always be present so OEM MediaProviders do not zero DATE_TAKEN.
            val exifDate = epochToExifDateTime(dateTaken)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
            if (withGps) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,      "22/1,19/1,948/100")
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,  "N")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,     "114/1,10/1,984/100")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            }
            exif.saveAttributes()

            tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Formats epoch milliseconds as an EXIF datetime string ("yyyy:MM:dd HH:mm:ss")
     * in the device's local timezone — the format expected by [ExifInterface.TAG_DATETIME_ORIGINAL].
     */
    private fun epochToExifDateTime(epochMs: Long): String =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(epochMs))
}
