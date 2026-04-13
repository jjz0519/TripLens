package com.cooldog.triplens.export

// Platform-specific logging for the export pipeline.
// Kept in the `export` package so it is internal to the module and not part of the public API.
// Android actual uses android.util.Log; future platforms (desktop) can use println or slf4j.

internal expect fun exportLogI(tag: String, message: String)

internal expect fun exportLogE(tag: String, message: String, cause: Throwable)
