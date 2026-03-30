package com.cooldog.triplens.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cooldog.triplens.db.AppDatabase
import com.cooldog.triplens.export.ExportUseCase
import com.cooldog.triplens.export.PlatformFileSystem
import com.cooldog.triplens.repository.MediaRefRepository
import com.cooldog.triplens.repository.NoteRepository
import com.cooldog.triplens.repository.SessionRepository
import com.cooldog.triplens.repository.TrackPointRepository
import com.cooldog.triplens.repository.TripRepository
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Verifies that [sharedModule] binds all its types correctly.
 *
 * Because [sharedModule] contains only platform-independent bindings, these tests run on the
 * JVM without any Android runtime. A [jvmTestModule] is added alongside [sharedModule] to
 * supply the two platform-specific dependencies that [sharedModule] requires:
 *   1. [AppDatabase]         — created from the JVM in-memory SQLite driver.
 *   2. [PlatformFileSystem]  — a no-op fake; only existence matters for DI graph resolution.
 *
 * Tests do NOT test behaviour — they test that the DI graph resolves without errors and that
 * singleton semantics hold. Behavioural tests live in RepositoryTest and ExportUseCaseTest.
 */
class SharedModuleTest {

    /**
     * Provides the two platform-specific bindings that [sharedModule] depends on, using
     * JVM-compatible implementations so the tests run without an Android emulator.
     */
    private val jvmTestModule = module {
        single {
            // In-memory SQLite driver: produces an isolated DB per test run.
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            AppDatabase.Schema.create(driver)
            AppDatabase(driver)
        }
        single<PlatformFileSystem> { NoOpPlatformFileSystem() }
    }

    @BeforeTest
    fun setUp() {
        startKoin {
            modules(sharedModule, jvmTestModule)
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    // ------------------------------------------------------------------
    // DI graph resolution
    // ------------------------------------------------------------------

    @Test
    fun allRepositoriesResolve() {
        // If any of these throw, the binding is missing or has an unresolved dependency.
        assertNotNull(org.koin.core.context.GlobalContext.get().get<TripRepository>())
        assertNotNull(org.koin.core.context.GlobalContext.get().get<SessionRepository>())
        assertNotNull(org.koin.core.context.GlobalContext.get().get<TrackPointRepository>())
        assertNotNull(org.koin.core.context.GlobalContext.get().get<MediaRefRepository>())
        assertNotNull(org.koin.core.context.GlobalContext.get().get<NoteRepository>())
    }

    @Test
    fun exportUseCaseResolves() {
        assertNotNull(org.koin.core.context.GlobalContext.get().get<ExportUseCase>())
    }

    // ------------------------------------------------------------------
    // Singleton semantics
    // ------------------------------------------------------------------

    /**
     * All repositories must share the same [AppDatabase] instance.
     *
     * This is verified behaviourally: [TripRepository] inserts a group, then
     * [SessionRepository] inserts a session referencing that group. If they used
     * different DB instances, the foreign key constraint on `session.group_id` would
     * fail (the group would not exist in the session repo's DB). The test only passes
     * when both repos operate on the same in-memory database.
     */
    @Test
    fun repositoriesShareSingletonDatabase() {
        val koin       = org.koin.core.context.GlobalContext.get()
        val tripRepo   = koin.get<TripRepository>()
        val sessionRepo = koin.get<SessionRepository>()

        tripRepo.createGroup("g1", "Test Trip", 1_000L)
        // FK constraint: session.group_id must reference an existing trip_group.id.
        // This succeeds only if sessionRepo uses the same AppDatabase as tripRepo.
        sessionRepo.createSession("s1", "g1", "Day 1", 2_000L)
        val session = sessionRepo.getSessionById("s1")
        assertNotNull(session, "Session should be retrievable from the same database instance")
    }

    @Test
    fun tripRepositorySingletonReturnsSameInstance() {
        val koin  = org.koin.core.context.GlobalContext.get()
        val first  = koin.get<TripRepository>()
        val second = koin.get<TripRepository>()
        // single { } must return the same object reference on every get().
        assertSame(first, second, "TripRepository must be a singleton")
    }
}

/**
 * No-op [PlatformFileSystem] for DI graph resolution tests.
 * None of its methods are called — it only satisfies the Koin binding requirement.
 */
private class NoOpPlatformFileSystem : PlatformFileSystem {
    override fun createTempDir(name: String) = "/tmp/$name"
    override fun createOutputPath(subdir: String, filename: String) = "/tmp/$subdir/$filename"
    override fun appPrivatePath(vararg segments: String) = "/tmp/${segments.joinToString("/")}"
    override fun writeText(filePath: String, text: String) = Unit
    override fun createDir(dirPath: String) = Unit
    override fun copy(sourcePath: String, destPath: String) = Unit
    override fun zip(sourceDirPath: String, destZipPath: String) = Unit
    override fun deleteRecursive(dirPath: String) = Unit
    override fun size(filePath: String) = 0L
    override fun joinPath(parent: String, child: String) = "$parent/$child"
}
