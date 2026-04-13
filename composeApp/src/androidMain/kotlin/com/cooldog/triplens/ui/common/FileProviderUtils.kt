package com.cooldog.triplens.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cooldog.triplens.R
import java.io.File

/**
 * FileProvider authority for sharing exported .triplens archives.
 *
 * Must match the android:authorities value in AndroidManifest.xml's <provider> block and
 * the path prefix declared in res/xml/file_provider_paths.xml.
 */
internal const val FILE_PROVIDER_AUTHORITY = "com.cooldog.triplens.fileprovider"

/**
 * Converts [path] to a `content://` URI via [FileProvider] and fires the Android share
 * chooser for the exported `.triplens` archive.
 *
 * ## Why both flags are on the chooser intent
 * `Intent.createChooser()` wraps the `ACTION_SEND` intent in a new chooser intent. Android
 * reads URI permission grants from the top-level intent passed to `startActivity()` — the
 * chooser intent. Setting [Intent.FLAG_GRANT_READ_URI_PERMISSION] only on the inner intent
 * causes a `SecurityException` on the receiving app when it tries to open the URI.
 * [Intent.FLAG_ACTIVITY_NEW_TASK] is required when starting an Activity from a non-Activity
 * context (which Compose's `LocalContext.current` may be in certain hosts).
 *
 * @param path Absolute filesystem path to the exported archive, as returned by [ExportResult.path].
 */
internal fun Context.startShareFileIntent(path: String) {
    val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, File(path))
    val chooser = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Inner intent flag for recipients that directly inspect the wrapped intent.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        getString(R.string.export_chooser_title),
    ).apply {
        // Required on the chooser (top-level) intent: grants the target app URI read access
        // and allows startActivity() from a non-Activity context.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}
