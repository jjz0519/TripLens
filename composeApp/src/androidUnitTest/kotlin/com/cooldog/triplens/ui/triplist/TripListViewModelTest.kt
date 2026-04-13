package com.cooldog.triplens.ui.triplist

import com.cooldog.triplens.export.ExportResult
import com.cooldog.triplens.model.TripGroup
import com.cooldog.triplens.repository.TripGroupWithStats
import com.cooldog.triplens.ui.common.ExportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [TripListViewModel].
 *
 * Follows the same patterns as [com.cooldog.triplens.ui.AppViewModelTest]:
 * - [StandardTestDispatcher] injected into deps
 * - Lambda-based fakes (no mocking library)
 * - [advanceUntilIdle] to drain coroutines
 *
 * [FIXED_EPOCH] = 0L avoids timezone-dependent test results.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private val FIXED_EPOCH = 0L

    // ── Mutable fake data ────────────────────────────────────────────────────────

    private var fakeGroups: List<TripGroupWithStats> = emptyList()
    private var deleteCallCount = 0
    private var lastDeletedId: String? = null
    private var lastRenamedId: String? = null
    private var lastRenamedName: String? = null
    private var exportCallCount = 0
    private var exportShouldThrow = false
    /** When non-null, export suspends until this deferred is resolved. */
    private var exportGate: CompletableDeferred<Unit>? = null
    private val exportedPath = "/data/user/0/com.cooldog.triplens/files/exports/trip.triplens"

    // ── Builder ──────────────────────────────────────────────────────────────────

    private fun buildViewModel() = TripListViewModel(
        deps = TripListDeps(
            getAllGroupsWithStatsFn = { fakeGroups },
            getTrackPointsByGroupFn = { emptyList() },  // No trajectory points in unit tests
            deleteGroupFn = { id ->
                deleteCallCount++
                lastDeletedId = id
                // Remove from fake list so reload sees the change.
                fakeGroups = fakeGroups.filter { it.group.id != id }
            },
            renameGroupFn = { id, name, _ ->
                lastRenamedId = id
                lastRenamedName = name
                fakeGroups = fakeGroups.map {
                    if (it.group.id == id) {
                        it.copy(group = it.group.copy(name = name))
                    } else it
                }
            },
            exportFn = { _, _ ->
                exportCallCount++
                exportGate?.await()
                if (exportShouldThrow) throw RuntimeException("disk full")
                ExportResult(path = exportedPath, sizeBytes = 2048)
            },
            clock = { FIXED_EPOCH },
            ioDispatcher = testDispatcher,
        )
    )

    // ── Factory helpers ──────────────────────────────────────────────────────────

    private fun makeGroupWithStats(
        id: String,
        name: String,
        sessionCount: Long = 0,
        distance: Double = 0.0,
    ) = TripGroupWithStats(
        group = TripGroup(id = id, name = name, createdAt = FIXED_EPOCH, updatedAt = FIXED_EPOCH),
        sessionCount = sessionCount,
        earliestStart = if (sessionCount > 0) FIXED_EPOCH else null,
        latestEnd = if (sessionCount > 0) FIXED_EPOCH + 3_600_000 else null,
        totalDistanceMeters = distance,
        photoCount = 0,
        videoCount = 0,
        noteCount = 0,
    )

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    fun emptyDb_producesEmptyGroupsList() = runTest(testDispatcher) {
        fakeGroups = emptyList()
        val vm = buildViewModel()
        advanceUntilIdle()

        val state = assertIs<TripListViewModel.UiState.Loaded>(vm.uiState.value)
        assertTrue(state.groups.isEmpty(), "Empty DB must produce empty groups list")
    }

    @Test
    fun twoGroups_twoItemsWithCorrectStats() = runTest(testDispatcher) {
        fakeGroups = listOf(
            makeGroupWithStats("g1", "Trip Paris", sessionCount = 3, distance = 12345.0),
            makeGroupWithStats("g2", "Trip Tokyo", sessionCount = 1, distance = 500.0),
        )
        val vm = buildViewModel()
        advanceUntilIdle()

        val state = assertIs<TripListViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals(2, state.groups.size, "Two groups must produce two items")
        assertEquals("Trip Paris", state.groups[0].name)
        assertEquals("Trip Tokyo", state.groups[1].name)
        assertEquals(3L, state.groups[0].sessionCount)
        assertEquals(1L, state.groups[1].sessionCount)
    }

    @Test
    fun onDeleteGroup_removesFromState() = runTest(testDispatcher) {
        fakeGroups = listOf(
            makeGroupWithStats("g1", "Trip A"),
            makeGroupWithStats("g2", "Trip B"),
        )
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        assertEquals(1, deleteCallCount)
        assertEquals("g1", lastDeletedId)
        val state = assertIs<TripListViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals(1, state.groups.size)
        assertEquals("Trip B", state.groups[0].name)
    }

    @Test
    fun onRenameGroup_updatesName() = runTest(testDispatcher) {
        fakeGroups = listOf(makeGroupWithStats("g1", "Old Name"))
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onRenameGroup("g1", "New Name")
        advanceUntilIdle()

        assertEquals("g1", lastRenamedId)
        assertEquals("New Name", lastRenamedName)
        val state = assertIs<TripListViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals("New Name", state.groups[0].name)
    }

    @Test
    fun onExportGroup_success_emitsShareFileEvent() = runTest(testDispatcher) {
        fakeGroups = listOf(makeGroupWithStats("g1", "My Trip"))
        val vm = buildViewModel()
        advanceUntilIdle()

        val events = mutableListOf<TripListViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onExportGroup("g1")
        advanceUntilIdle()

        assertEquals(1, exportCallCount)
        assertTrue(
            events.any { it is TripListViewModel.Event.ShareFile && it.path == exportedPath },
            "Successful export must emit ShareFile with the archive path",
        )
        job.cancel()
    }

    @Test
    fun onExportGroup_success_exportStateResetsToIdle() = runTest(testDispatcher) {
        fakeGroups = listOf(makeGroupWithStats("g1", "My Trip"))
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onExportGroup("g1")
        advanceUntilIdle()

        assertIs<ExportState.Idle>(vm.exportState.value)
    }

    @Test
    fun onExportGroup_whileRunning_exportStateIsInProgress() = runTest(testDispatcher) {
        fakeGroups = listOf(makeGroupWithStats("g1", "My Trip"))
        exportGate = CompletableDeferred()
        val vm = buildViewModel()
        advanceUntilIdle()

        launch { vm.onExportGroup("g1") }
        testDispatcher.scheduler.runCurrent()

        assertIs<ExportState.InProgress>(
            vm.exportState.value,
            "exportState must be InProgress while export is running",
        )

        exportGate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun onExportGroup_failure_exportStateResetsToIdle() = runTest(testDispatcher) {
        // The ViewModel transitions through Error and immediately resets to Idle so the
        // error state does not linger after the snackbar event has been sent.
        fakeGroups = listOf(makeGroupWithStats("g1", "My Trip"))
        exportShouldThrow = true
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onExportGroup("g1")
        advanceUntilIdle()

        assertIs<ExportState.Idle>(vm.exportState.value)
    }

    @Test
    fun onExportGroup_failure_emitsSnackbarEvent() = runTest(testDispatcher) {
        fakeGroups = listOf(makeGroupWithStats("g1", "My Trip"))
        exportShouldThrow = true
        val vm = buildViewModel()
        advanceUntilIdle()

        val events = mutableListOf<TripListViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onExportGroup("g1")
        advanceUntilIdle()

        assertTrue(
            events.any {
                it is TripListViewModel.Event.ShowSnackbar && it.message.contains("Export failed")
            },
            "Failed export must emit a ShowSnackbar event containing 'Export failed'",
        )
        job.cancel()
    }

    @Test
    fun onExportGroup_doubleCall_secondCallIsNoOp() = runTest(testDispatcher) {
        fakeGroups = listOf(makeGroupWithStats("g1", "My Trip"))
        exportGate = CompletableDeferred()
        val vm = buildViewModel()
        advanceUntilIdle()

        val events = mutableListOf<TripListViewModel.Event>()
        val collectJob = launch { vm.events.collect { events.add(it) } }

        launch { vm.onExportGroup("g1") }
        testDispatcher.scheduler.runCurrent()

        // Second call while InProgress — must be a no-op.
        vm.onExportGroup("g1")

        exportGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, exportCallCount, "Only one export must run despite the double call")
        assertEquals(
            1, events.filterIsInstance<TripListViewModel.Event.ShareFile>().size,
            "Double-tap must not trigger two ShareFile events",
        )
        collectJob.cancel()
    }
}
