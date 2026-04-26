package com.cooldog.triplens.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooldog.triplens.data.Language
import com.cooldog.triplens.data.ScanInterval
import com.cooldog.triplens.platform.AccuracyProfile
import com.cooldog.triplens.ui.theme.BiophilicColors
import com.cooldog.triplens.ui.theme.InstrumentSerifFamily
import com.cooldog.triplens.ui.theme.LocalBiophilicColors
import com.cooldog.triplens.ui.theme.Palette
import java.util.Locale

/**
 * Settings screen — biophilic redesign (Task 27).
 *
 * Replaces the original flat chip list with a card-based layout grouped into three sections:
 * Appearance (palette picker), GPS & Capture (accuracy + scan interval), and General (language).
 *
 * The palette row uses a 2×2 grid of swatch chips showing overlapping colour circles so the
 * user can preview each theme without applying it first. Selecting any option immediately
 * persists and applies the new value via [SettingsViewModel].
 *
 * This composable is a bottom-nav tab destination hosted inside the root Scaffold in
 * AppNavGraph — no nested Scaffold or bottom bar is added here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val bio = LocalBiophilicColors.current

    val language        by viewModel.language.collectAsState()
    val accuracyProfile by viewModel.accuracyProfile.collectAsState()
    val scanInterval    by viewModel.scanInterval.collectAsState()
    val palette         by viewModel.palette.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bio.bg),
    ) {
        // ── Screen title ──────────────────────────────────────────────────────
        Text(
            text       = "Settings",
            fontFamily = InstrumentSerifFamily,
            fontSize   = 34.sp,
            color      = bio.ink,
            modifier   = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )

        LazyColumn(
            modifier            = Modifier.weight(1f),
            contentPadding      = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // ── Appearance ────────────────────────────────────────────────────
            item { SettingsSectionTitle(bio, "Appearance") }
            item {
                SettingsGroup(bio) {
                    PaletteRow(bio, palette, viewModel::onPaletteSelected)
                }
            }

            // ── GPS & Capture ─────────────────────────────────────────────────
            item { SettingsSectionTitle(bio, "GPS & Capture") }
            item {
                SettingsGroup(bio) {
                    AccuracyRow(bio, accuracyProfile, viewModel::onAccuracyProfileSelected)
                    SettingsDivider(bio)
                    ScanIntervalRow(bio, scanInterval, viewModel::onScanIntervalSelected)
                }
            }

            // ── General ───────────────────────────────────────────────────────
            item { SettingsSectionTitle(bio, "General") }
            item {
                SettingsGroup(bio) {
                    LanguageRow(bio, language, viewModel::onLanguageSelected)
                }
            }

            // ── Footer ────────────────────────────────────────────────────────
            item {
                Text(
                    text          = "TripLens · Local-first travel recording",
                    fontSize      = 11.sp,
                    color         = bio.ink3,
                    textAlign     = TextAlign.Center,
                    letterSpacing = 0.3.sp,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                )
            }
        }
    }
}

// ── Section chrome ────────────────────────────────────────────────────────────

/**
 * All-caps section label rendered above each [SettingsGroup].
 * Small caps + wide letter-spacing follows the biophilic design token convention
 * used across the recording and trip-list screens.
 */
@Composable
private fun SettingsSectionTitle(bio: BiophilicColors, title: String) {
    Text(
        text          = title.uppercase(Locale.getDefault()),
        fontSize      = 11.sp,
        color         = bio.ink3,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier      = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
    )
}

/**
 * Rounded card container that groups one or more settings rows.
 * Uses [bio.surface] background with a subtle [bio.line2] border to lift the card
 * off the [bio.bg] page background without casting a hard shadow.
 */
@Composable
private fun SettingsGroup(bio: BiophilicColors, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(20.dp)),
    ) {
        content()
    }
}

/** Inset horizontal rule that separates rows inside a [SettingsGroup]. */
@Composable
private fun SettingsDivider(bio: BiophilicColors) {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        color    = bio.line2,
    )
}

// ── Row header ────────────────────────────────────────────────────────────────

/**
 * Icon + title/subtitle header placed at the top of each settings row.
 *
 * The icon is rendered inside a 28 dp pill with [bio.mossPale] background so it
 * stays consistent across palette variants (the accent colour always derives from
 * the active palette's mossPale token).
 */
@Composable
private fun SettingsRowHeader(
    bio:      BiophilicColors,
    icon:     ImageVector,
    title:    String,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier        = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(bio.mossPale),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector         = icon,
                contentDescription  = null,
                tint                = bio.mossDeep,
                modifier            = Modifier.size(18.dp),
            )
        }
        Column {
            Text(title, fontSize = 14.sp, color = bio.ink, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color    = bio.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ── Palette row ───────────────────────────────────────────────────────────────

/**
 * 2×2 grid of palette options.
 *
 * Each cell shows three overlapping colour circles (30 dp wide, 8 dp step offset) that
 * preview the palette's bg, moss, and mossDeep tokens without requiring the user to apply
 * the theme first. The selected cell gets a [bio.mossPale] background and a [bio.moss] border.
 *
 * Hard-coded swatch triples use the same hex values that [biophilicColors] produces for each
 * palette so the preview is always accurate regardless of the active palette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteRow(
    bio:      BiophilicColors,
    current:  Palette,
    onChange: (Palette) -> Unit,
) {
    // Swatch triples: (bg-shade, moss, mossDeep) per palette — mirrors BiophilicColors.kt values.
    val palettes = listOf(
        Palette.MOSS    to Triple(Color(0xFFD2EDD8), Color(0xFF5A9A64), Color(0xFF376E41)),
        Palette.FOREST  to Triple(Color(0xFFBEE8C9), Color(0xFF2E7A3B), Color(0xFF1B5227)),
        Palette.COASTAL to Triple(Color(0xFFBDD9E8), Color(0xFF4B94B5), Color(0xFF2D6D90)),
        Palette.DESERT  to Triple(Color(0xFFD8E5B8), Color(0xFF7D9148), Color(0xFF5C6B2C)),
    )
    val labels = mapOf(
        Palette.MOSS    to "Soft moss",
        Palette.FOREST  to "Forest",
        Palette.COASTAL to "Coastal",
        Palette.DESERT  to "Desert",
    )

    Column(Modifier.padding(16.dp)) {
        SettingsRowHeader(bio, Icons.Outlined.ColorLens, "Palette")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (pal, swatches) ->
                        val selected = current == pal
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) bio.mossPale else bio.bg2)
                                .border(
                                    width  = 1.5.dp,
                                    color  = if (selected) bio.moss else Color.Transparent,
                                    shape  = RoundedCornerShape(14.dp),
                                )
                                .clickable { onChange(pal) }
                                .padding(10.dp),
                            verticalAlignment      = Alignment.CenterVertically,
                            horizontalArrangement  = Arrangement.spacedBy(10.dp),
                        ) {
                            // Three overlapping circles that preview the palette colours.
                            // Each circle is 14 dp; they overlap by 6 dp (step = 8 dp).
                            // Container width = 14 + 8 + 8 = 30 dp.
                            Box(Modifier.width(30.dp).height(14.dp)) {
                                listOf(swatches.first, swatches.second, swatches.third)
                                    .forEachIndexed { i, c ->
                                        Box(
                                            Modifier
                                                .size(14.dp)
                                                .offset(x = (i * 8).dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .border(1.5.dp, bio.surface, CircleShape),
                                        )
                                    }
                            }
                            Text(
                                text       = labels[pal] ?: "",
                                fontSize   = 12.sp,
                                color      = bio.ink,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── GPS accuracy row ──────────────────────────────────────────────────────────

/**
 * Filter chip row for [AccuracyProfile].
 *
 * STANDARD / HIGH / BATTERY_SAVER are shown as horizontal [FlowRow] chips so they
 * wrap gracefully on narrow screens. The selected chip uses [bio.mossPale2] background
 * and [bio.mossDeep] label colour to stay coherent with the active palette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccuracyRow(
    bio:      BiophilicColors,
    current:  AccuracyProfile,
    onChange: (AccuracyProfile) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        SettingsRowHeader(bio, Icons.Outlined.MyLocation, "GPS Accuracy")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccuracyProfile.entries.forEach { profile ->
                val label = when (profile) {
                    AccuracyProfile.STANDARD      -> "Standard"
                    AccuracyProfile.HIGH          -> "High"
                    AccuracyProfile.BATTERY_SAVER -> "Battery saver"
                }
                FilterChip(
                    selected = current == profile,
                    onClick  = { onChange(profile) },
                    label    = { Text(label) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = bio.mossPale2,
                        selectedLabelColor     = bio.mossDeep,
                    ),
                )
            }
        }
    }
}

// ── Gallery scan interval row ─────────────────────────────────────────────────

/**
 * Filter chip row for [ScanInterval].
 *
 * Each chip label shows the interval in seconds (e.g. "30s", "60s").
 * Uses the same chip styling as [AccuracyRow] for visual consistency.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanIntervalRow(
    bio:      BiophilicColors,
    current:  ScanInterval,
    onChange: (ScanInterval) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        SettingsRowHeader(bio, Icons.Outlined.Timer, "Gallery scan")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanInterval.entries.forEach { interval ->
                FilterChip(
                    selected = current == interval,
                    onClick  = { onChange(interval) },
                    label    = { Text("${interval.seconds}s") },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = bio.mossPale2,
                        selectedLabelColor     = bio.mossDeep,
                    ),
                )
            }
        }
    }
}

// ── Language row ──────────────────────────────────────────────────────────────

/**
 * Filter chip row for [Language].
 *
 * SYSTEM / EN / ZH_CN are shown inline. "System" follows the device locale;
 * the other two force English or Simplified Chinese via AppCompatDelegate.
 * Same chip styling as the other rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageRow(
    bio:      BiophilicColors,
    current:  Language,
    onChange: (Language) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        SettingsRowHeader(bio, Icons.Outlined.Language, "Language")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { lang ->
                val label = when (lang) {
                    Language.SYSTEM -> "System"
                    Language.EN     -> "English"
                    Language.ZH_CN  -> "中文"
                }
                FilterChip(
                    selected = current == lang,
                    onClick  = { onChange(lang) },
                    label    = { Text(label) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = bio.mossPale2,
                        selectedLabelColor     = bio.mossDeep,
                    ),
                )
            }
        }
    }
}
