package com.cooldog.triplens.ui.common

/**
 * Represents the current state of a trip export operation.
 *
 * Shared by [com.cooldog.triplens.ui.tripdetail.TripDetailViewModel] and
 * [com.cooldog.triplens.ui.triplist.TripListViewModel] to avoid duplicating
 * an identical sealed interface in both ViewModels.
 *
 * ## State transitions
 * ```
 * Idle ──onExportGroup()──► InProgress
 * InProgress ──success──► Idle   (Event.ShareFile is also emitted)
 * InProgress ──failure──► Error ──(immediately)──► Idle
 *                                (Event.ShowSnackbar is also emitted)
 * ```
 * [Error] is transient — the ViewModel resets to [Idle] immediately after emitting the
 * [ShowSnackbar] event, so callers observing [exportState] will see a brief [Error] value
 * in the flow but the terminal state after a failure is always [Idle].
 */
sealed interface ExportState {
    /** No export in progress; the export button is enabled. */
    data object Idle : ExportState

    /** Export pipeline is running; the export button shows a spinner. */
    data object InProgress : ExportState

    /**
     * Export failed. [message] is shown in a snackbar by the ViewModel.
     * The UI uses this state to keep the spinner hidden and re-enable the button.
     */
    data class Error(val message: String) : ExportState
}
