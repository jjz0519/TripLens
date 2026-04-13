package com.cooldog.triplens.ui.tripdetail

import com.cooldog.triplens.export.ExportResult
import com.cooldog.triplens.model.Session
import com.cooldog.triplens.model.SessionStatus
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.model.TripGroup
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
 * Unit tests for [TripDetailViewModel].
 *
 * Tests verify:
 * - Loaded state has correct transport breakdown from [SegmentSmoother]
 * - Session rename calls the repository
 *
 * Uses [StandardTestDispatcher] + lambda fakes, same as [TripListViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp()    { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private val FIXED_EPOCH = 0L
    private val GROUP_ID = "g1"

    // ── Captured call arguments ──────────────────────────────────────────────────
    private var lastRenamedSessionId: String? = null
    private var lastRenamedSessionName: String? = null

    // ── Export fake controls ─────────────────────────────────────────────────────
    private var exportShouldThrow = false
    /** When non-null, export suspends until this deferred is resolved. */
    private var exportGate: CompletableDeferred<Unit>? = null
    private val exportedPath = "/data/user/0/com.cooldog.triplens/files/exports/trip.triplens"

    // ── Mutable fake data ────────────────────────────────────────────────────────

    private val fakeGroup = TripGroup(
        id = GROUP_ID,
        name = "Trip Paris",
        createdAt = FIXED_EPOCH,
        updatedAt = FIXED_EPOCH,
    )

    private var fakeSessions: List<Session> = listOf(
        Session(
            id = "s1",
            groupId = GROUP_ID,
            name = "Day 1",
            startTime = FIXED_EPOCH,
            endTime = FIXED_EPOCH + 3_600_000,  // 1 hour
            status = SessionStatus.COMPLETED,
        ),
    )

    /**
     * 5 walking points at 10s intervals — should produce a single WALKING segment.
     * Points are spread ~100m apart to generate a measurable distance.
     */
    private var fakeTrackPoints: List<TrackPoint> = (0..4).map { i ->
        TrackPoint(
            id = i.toLong(),
            sessionId = "s1",
            timestamp = FIXED_EPOCH + i * 10_000L,
            latitude = 48.8566 + i * 0.001,
            longitude = 2.3522,
            altitude = null,
            accuracy = 5f,
            speed = 1.4f,  // ~5 km/h walking
            transportMode = TransportMode.WALKING,
            isAutoPaused = false,
        )
    }

    // ── Builder ──────────────────────────────────────────────────────────────────

    private fun buildViewModel() = TripDetailViewModel(
        deps = TripDetailDeps(
            groupId = GROUP_ID,
            getGroupByIdFn = { id -> if (id == GROUP_ID) fakeGroup else null },
            getSessionsByGroupFn = { fakeSessions },
            getTrackPointsFn = { fakeTrackPoints },
            renameSessionFn = { id, name ->
                lastRenamedSessionId = id
                lastRenamedSessionName = name
                fakeSessions = fakeSessions.map {
                    if (it.id == id) it.copy(name = name) else it
                }
            },
            exportFn = { _, _ ->
                exportGate?.await()
                if (exportShouldThrow) throw RuntimeException("disk full")
                ExportResult(path = exportedPath, sizeBytes = 2048)
            },
            clock = { FIXED_EPOCH },
            ioDispatcher = testDispatcher,
        )
    )

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    fun loadedState_hasCorrectTransportBreakdown() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        val state = assertIs<TripDetailViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals(1, state.sessions.size)

        val session = state.sessions[0]
        // All 5 points are WALKING — SegmentSmoother should produce a single WALKING segment.
        assertEquals(1, session.transportBreakdown.size)
        assertEquals(TransportMode.WALKING, session.transportBreakdown[0].mode)
        // Distance should be > 0 (points are 0.001° apart ≈ 111m each, 4 pairs ≈ 444m)
        assertTrue(session.transportBreakdown[0].distanceMeters > 100.0,
            "Walking distance must be > 100m for 5 points spread 0.001° apart")
    }

    @Test
    fun loadedState_groupNameCorrect() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        val state = assertIs<TripDetailViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals("Trip Paris", state.groupName)
    }

    @Test
    fun loadedState_sessionCountCorrect() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        val state = assertIs<TripDetailViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals(1, state.sessionCount)
    }

    @Test
    fun onRenameSession_callsRepoAndReloads() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onRenameSession("s1", "Morning Walk")
        advanceUntilIdle()

        assertEquals("s1", lastRenamedSessionId)
        assertEquals("Morning Walk", lastRenamedSessionName)

        val state = assertIs<TripDetailViewModel.UiState.Loaded>(vm.uiState.value)
        assertEquals("Morning Walk", state.sessions[0].name)
    }

    @Test
    fun onExportGroup_success_emitsShareFileEvent() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        val events = mutableListOf<TripDetailViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onExportGroup()
        advanceUntilIdle()

        assertTrue(
            events.any { it is TripDetailViewModel.Event.ShareFile && it.path == exportedPath },
            "Successful export must emit ShareFile with the archive path",
        )
        job.cancel()
    }

    @Test
    fun onExportGroup_success_exportStateResetsToIdle() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onExportGroup()
        advanceUntilIdle()

        assertIs<ExportState.Idle>(vm.exportState.value)
    }

    @Test
    fun onExportGroup_whileRunning_exportStateIsInProgress() = runTest(testDispatcher) {
        // Install a gate so the export suspends mid-flight while we check the state.
        exportGate = CompletableDeferred()
        val vm = buildViewModel()
        advanceUntilIdle()

        launch { vm.onExportGroup() }
        // Allow the coroutine to reach the suspended exportFn call.
        testDispatcher.scheduler.runCurrent()

        assertIs<ExportState.InProgress>(
            vm.exportState.value,
            "exportState must be InProgress while export is running",
        )

        // Unblock and drain so the VM is in a clean state after the test.
        exportGate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun onExportGroup_failure_exportStateResetsToIdle() = runTest(testDispatcher) {
        // The ViewModel transitions through Error and immediately resets to Idle so the
        // error state does not linger after the snackbar event has been sent.
        exportShouldThrow = true
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.onExportGroup()
        advanceUntilIdle()

        assertIs<ExportState.Idle>(vm.exportState.value)
    }

    @Test
    fun onExportGroup_failure_emitsSnackbarEvent() = runTest(testDispatcher) {
        exportShouldThrow = true
        val vm = buildViewModel()
        advanceUntilIdle()

        val events = mutableListOf<TripDetailViewModel.Event>()
        val job = launch { vm.events.collect { events.add(it) } }

        vm.onExportGroup()
        advanceUntilIdle()

        assertTrue(
            events.any {
                it is TripDetailViewModel.Event.ShowSnackbar && it.message.contains("Export failed")
            },
            "Failed export must emit a ShowSnackbar event containing 'Export failed'",
        )
        job.cancel()
    }

    @Test
    fun onExportGroup_doubleCall_secondCallIsNoOp() = runTest(testDispatcher) {
        // Gate ensures the first export is still in progress when the second call arrives.
        exportGate = CompletableDeferred()
        val vm = buildViewModel()
        advanceUntilIdle()

        val events = mutableListOf<TripDetailViewModel.Event>()
        val collectJob = launch { vm.events.collect { events.add(it) } }

        launch { vm.onExportGroup() }
        testDispatcher.scheduler.runCurrent()

        // Second call while InProgress — must be a no-op.
        vm.onExportGroup()

        exportGate!!.complete(Unit)
        advanceUntilIdle()

        // Only one ShareFile event — the second call was swallowed.
        assertEquals(1, events.filterIsInstance<TripDetailViewModel.Event.ShareFile>().size,
            "Double-tap must not trigger two exports")
        collectJob.cancel()
    }

    @Test
    fun groupNotFound_showsError() = runTest(testDispatcher) {
        // Build with a non-existent group ID.
        val vm = TripDetailViewModel(
            deps = TripDetailDeps(
                groupId = "non-existent",
                getGroupByIdFn = { null },
                getSessionsByGroupFn = { emptyList() },
                getTrackPointsFn = { emptyList() },
                renameSessionFn = { _, _ -> },
                exportFn = { _, _ ->
                    ExportResult(path = "", sizeBytes = 0)
                },
                clock = { FIXED_EPOCH },
                ioDispatcher = testDispatcher,
            )
        )
        advanceUntilIdle()

        val state = assertIs<TripDetailViewModel.UiState.Error>(vm.uiState.value)
        assertTrue(state.message.contains("not found"))
    }
}
