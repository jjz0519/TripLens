package com.cooldog.triplens.export

/**
 * Returned by [ExportUseCase.export] on success.
 *
 * @param path     Absolute path to the exported `.triplens` zip archive.
 * @param sizeBytes File size in bytes; useful for displaying "Export complete (4.2 MB)" in the UI.
 */
data class ExportResult(val path: String, val sizeBytes: Long)
