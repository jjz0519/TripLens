package com.cooldog.triplens.di

import com.cooldog.triplens.export.ExportUseCase
import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TrackPointRepository
import com.cooldog.triplens.repository.TripRepository
import org.koin.dsl.module

/**
 * Koin module for all platform-independent bindings: repositories and use cases.
 *
 * ## What belongs here vs AndroidModule
 * SharedModule contains only [commonMain] types — no Context, no Android SDK.
 * Platform-specific bindings (AppDatabase, LocationProvider, GalleryScanner, AudioRecorder,
 * PlatformFileSystem) live in AndroidModule so this module can be verified in JVM unit tests
 * without any Android runtime.
 *
 * ## Singleton vs factory
 * - Repositories are singletons: they are stateless wrappers around AppDatabase and are
 *   safe to share across the entire app lifecycle.
 * - ExportUseCase is a singleton: it has no mutable state; its dependencies are all singletons.
 *
 * ## AppDatabase dependency
 * All repositories depend on AppDatabase via [get()]. AppDatabase is registered in AndroidModule
 * (because creating it requires a platform Context). In production, both modules are loaded
 * together. In unit tests, a test module provides AppDatabase via the JVM SQLite driver.
 */
val sharedModule = module {

    // --- Repositories ---
    // Each takes a single AppDatabase parameter provided by the platform module (or test module).
    single { TripRepository(get()) }
    single { SessionRepository(get()) }
    single { TrackPointRepository(get()) }
    single { MediaRefRepository(get()) }
    single { NoteRepository(get()) }

    // --- Use cases ---
    // ExportUseCase depends on all four data repositories plus PlatformFileSystem.
    // PlatformFileSystem is registered in AndroidModule (requires Context).
    single {
        ExportUseCase(
            tripRepo       = get(),
            sessionRepo    = get(),
            trackPointRepo = get(),
            noteRepo       = get(),
            mediaRefRepo   = get(),
            fileSystem     = get()
        )
    }
}
