package com.cooldog.triplens.export

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "TripLens/FileSystem"

/**
 * Android implementation of [PlatformFileSystem].
 *
 * ## Storage layout
 * - Temp directories:   `context.cacheDir/{name}` — the OS may purge these when storage is low.
 * - Permanent outputs:  `context.filesDir/{subdir}/{filename}` — survives across app restarts.
 * - App private files:  `context.filesDir/{segments}` — where voice notes are stored.
 *
 * ## Zip format
 * Each entry in the zip archive is prefixed with the source directory's own name, producing
 * a single named root folder when the archive is unzipped. For example:
 * ```
 * source: .../cache/triplens-Tokyo-2024-01-15/
 * entries: triplens-Tokyo-2024-01-15/index.json
 *          triplens-Tokyo-2024-01-15/tracks/session_s1.gpx
 * ```
 *
 * @param context Application context; must remain valid for the lifetime of this instance.
 *                Always pass the application context (not an Activity) to avoid leaks.
 */
class AndroidFileSystem(private val context: Context) : PlatformFileSystem {

    override fun createTempDir(name: String): String {
        val dir = File(context.cacheDir, name)
        // Clean up any leftover temp dir from a previous failed export with the same name.
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        Log.d(TAG, "createTempDir: ${dir.absolutePath}")
        return dir.absolutePath
    }

    override fun createOutputPath(subdir: String, filename: String): String {
        val dir = File(context.filesDir, subdir)
        dir.mkdirs()
        val file = File(dir, filename)
        Log.d(TAG, "createOutputPath: ${file.absolutePath}")
        return file.absolutePath
    }

    override fun appPrivatePath(vararg segments: String): String {
        // Build the path under filesDir by joining segments with the system separator.
        var file = context.filesDir
        for (segment in segments) {
            file = File(file, segment)
        }
        return file.absolutePath
    }

    override fun writeText(filePath: String, text: String) {
        val file = File(filePath)
        // writeText uses UTF-8 by default; explicit Charsets.UTF_8 for documentation clarity.
        file.writeText(text, Charsets.UTF_8)
        Log.d(TAG, "writeText: ${file.absolutePath} (${file.length()} bytes)")
    }

    override fun createDir(dirPath: String) {
        val dir = File(dirPath)
        dir.mkdirs()
        Log.d(TAG, "createDir: $dirPath")
    }

    override fun copy(sourcePath: String, destPath: String) {
        val src  = File(sourcePath)
        val dest = File(destPath)
        // copyTo throws NoSuchFileException if source doesn't exist, CopyFailException on error.
        // Neither is swallowed — the caller (ExportUseCase) handles cleanup on any exception.
        src.copyTo(dest, overwrite = false)
        Log.d(TAG, "copy: $sourcePath → $destPath (${dest.length()} bytes)")
    }

    override fun zip(sourceDirPath: String, destZipPath: String) {
        val sourceDir    = File(sourceDirPath)
        val rootFolderName = sourceDir.name

        Log.i(TAG, "zip: $sourceDirPath → $destZipPath")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(destZipPath))).use { zos ->
            // walkTopDown() yields the root dir itself first; filter to files only.
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    // Relative path from sourceDir, with forward slashes for cross-platform compat.
                    val relativePath = file.relativeTo(sourceDir).path.replace("\\", "/")
                    val entryName    = "$rootFolderName/$relativePath"
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    Log.d(TAG, "zip: added entry $entryName")
                }
        }

        Log.i(TAG, "zip done: ${File(destZipPath).length()} bytes")
    }

    override fun deleteRecursive(dirPath: String) {
        val target = File(dirPath)
        if (!target.exists()) {
            Log.d(TAG, "deleteRecursive: already gone — $dirPath")
            return
        }
        val deleted = target.deleteRecursively()
        Log.d(TAG, "deleteRecursive: $dirPath (success=$deleted)")
    }

    override fun size(filePath: String): Long = File(filePath).length()

    override fun joinPath(parent: String, child: String): String =
        // Use forward slash explicitly — Android paths always use forward slashes,
        // and this prevents subtle bugs on JVM-based tests where File.separator is '\'.
        if (parent.endsWith("/")) "$parent$child" else "$parent/$child"
}
