package com.cooldog.triplens.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cooldog.triplens.R
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.data.ScanInterval
import com.cooldog.triplens.platform.AccuracyProfile

/**
 * Settings screen — a scrollable column of three preference sections (Task 16).
 *
 * Language, GPS Accuracy, and Gallery Scan Interval each render as a [FlowRow] of
 * [FilterChip]s so the options wrap gracefully on narrow screens (e.g. portrait phone
 * with large font). The selected chip reflects the current [StateFlow] value; tapping
 * any chip calls the corresponding ViewModel method, which persists the value and applies
 * any immediate side effects (locale change, service notification).
 *
 * This composable is a bottom-nav tab destination and is hosted inside the root [Scaffold]
 * in [AppNavGraph], so no nested Scaffold or bottom bar is added here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val language        by viewModel.language.collectAsState()
    val accuracyProfile by viewModel.accuracyProfile.collectAsState()
    val scanInterval    by viewModel.scanInterval.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // ── Language ──────────────────────────────────────────────────────────
        SettingsSection(title = stringResource(R.string.settings_language_title)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.entries.forEach { lang ->
                    FilterChip(
                        selected = language == lang,
                        onClick  = { viewModel.onLanguageSelected(lang) },
                        label    = {
                            Text(
                                when (lang) {
                                    Language.SYSTEM -> stringResource(R.string.settings_language_system)
                                    Language.EN     -> stringResource(R.string.settings_language_en)
                                    Language.ZH_CN  -> stringResource(R.string.settings_language_zh_cn)
                                }
                            )
                        },
                    )
                }
            }
        }

        // ── GPS Accuracy ──────────────────────────────────────────────────────
        SettingsSection(title = stringResource(R.string.settings_accuracy_title)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccuracyProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = accuracyProfile == profile,
                        onClick  = { viewModel.onAccuracyProfileSelected(profile) },
                        label    = {
                            Text(
                                when (profile) {
                                    AccuracyProfile.STANDARD     -> stringResource(R.string.settings_accuracy_standard)
                                    AccuracyProfile.HIGH         -> stringResource(R.string.settings_accuracy_high)
                                    AccuracyProfile.BATTERY_SAVER -> stringResource(R.string.settings_accuracy_battery_saver)
                                }
                            )
                        },
                    )
                }
            }
        }

        // ── Gallery Scan Interval ─────────────────────────────────────────────
        SettingsSection(title = stringResource(R.string.settings_scan_interval_title)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanInterval.entries.forEach { interval ->
                    FilterChip(
                        selected = scanInterval == interval,
                        onClick  = { viewModel.onScanIntervalSelected(interval) },
                        label    = { Text(stringResource(R.string.settings_scan_interval_seconds, interval.seconds)) },
                    )
                }
            }
        }
    }
}

/**
 * A titled preference section.
 *
 * The title is rendered in [MaterialTheme.typography.titleMedium] with primary colour to
 * create a visual hierarchy between section headings and chip content.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text  = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}
