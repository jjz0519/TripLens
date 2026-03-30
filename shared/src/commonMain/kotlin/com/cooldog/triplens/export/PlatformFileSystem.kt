package com.cooldog.triplens.export

/**
 * Platform-specific file system operations needed by [ExportUseCase].
 *
 * Using a plain interface (rather than expect/actual) keeps the commonMain code testable via
 * a [FakePlatformFileSystem] in unit tests, and is just as open to an iOS implementation as
 * expect/actual — the iOS Koin module would bind a SwiftPlatformFileSystem implementation.
 *
 * All paths use forward-slash separators and are absolute.
 */
interface PlatformFileSystem {

    /**
     * Creates a new directory with the given [name] in the platform's temporary storage and
     * returns its absolute path. The caller is responsible for deleting it via [deleteRecursive]
     * when done (on both success and failure paths).
     */
    fun createTempDir(name: String): String

    /**
     * Returns an absolute path for a permanent output file at [subdir]/[filename] within the
     * app's private permanent files storage (e.g. Android's `filesDir`). All parent directories
     * are created automatically. Safe to call before the file exists.
     */
    fun createOutputPath(subdir: String, filename: String): String

    /**
     * Returns the absolute path to a file at [segments] within the app's private files directory
     * (e.g. Android's `filesDir`). Does NOT create the file or its parents.
     * Used to locate existing files such as voice note recordings.
     */
    fun appPrivatePath(vararg segments: String): String

    /** Writes [text] to [filePath], creating the file if it does not exist. */
    fun writeText(filePath: String, text: String)

    /** Creates a directory at [dirPath] and all intermediate directories (idempotent). */
    fun createDir(dirPath: String)

    /**
     * Copies a single file from [sourcePath] to [destPath].
     * Throws if [sourcePath] does not exist or [destPath] already exists.
     */
    fun copy(sourcePath: String, destPath: String)

    /**
     * Zips the entire contents of [sourceDirPath] into [destZipPath]. The zip root folder
     * takes the name of [sourceDirPath]'s directory (not an absolute path), so unzipping
     * produces a single named folder.
     *
     * Example: if sourceDirPath is `.../cache/triplens-Tokyo-2024-01-15`, the zip entries
     * will be `triplens-Tokyo-2024-01-15/index.json`, `triplens-Tokyo-2024-01-15/tracks/...`
     */
    fun zip(sourceDirPath: String, destZipPath: String)

    /** Deletes [dirPath] and all its contents recursively. No-op if [dirPath] does not exist. */
    fun deleteRecursive(dirPath: String)

    /** Returns the size of the file at [filePath] in bytes. */
    fun size(filePath: String): Long

    /** Joins [parent] and [child] with the platform's path separator. */
    fun joinPath(parent: String, child: String): String
}
