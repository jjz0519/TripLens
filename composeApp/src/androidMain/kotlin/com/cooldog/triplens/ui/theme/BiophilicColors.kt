package com.cooldog.triplens.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class Palette { MOSS, FOREST, COASTAL, DESERT }

@Immutable
data class BiophilicColors(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surfaceElev: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val line2: Color,
    val moss: Color,
    val mossDeep: Color,
    val mossPale: Color,
    val mossPale2: Color,
    val sun: Color,
    val clay: Color,
    val recordRed: Color,
    val mapBg: Color,
    val mapLand: Color,
    val mapWater: Color,
    val mapRoad: Color,
    val mapRoadMajor: Color,
    val mapPark: Color,
)

val LocalBiophilicColors = staticCompositionLocalOf<BiophilicColors> {
    error("No BiophilicColors provided — wrap your composable in TripLensTheme")
}

fun biophilicColors(palette: Palette = Palette.MOSS, dark: Boolean = false): BiophilicColors {
    return if (dark) darkBiophilicColors() else lightBiophilicColors(palette)
}

private fun lightBiophilicColors(palette: Palette): BiophilicColors = when (palette) {
    Palette.MOSS -> BiophilicColors(
        bg            = Color(0xFFF7FAF4),
        bg2           = Color(0xFFEFF5EB),
        surface       = Color(0xFFFBFEFA),
        surfaceElev   = Color(0xFFFFFFFF),
        ink           = Color(0xFF2B3A2E),
        ink2          = Color(0xFF4B5F4E),
        ink3          = Color(0xFF737E74),
        line          = Color(0xFFD2E1CF),
        line2         = Color(0xFFE3EEE1),
        moss          = Color(0xFF5A9A64),
        mossDeep      = Color(0xFF376E41),
        mossPale      = Color(0xFFD2EDD8),
        mossPale2     = Color(0xFFBDE4C6),
        sun           = Color(0xFFCDA84A),
        clay          = Color(0xFFAF7350),
        recordRed     = Color(0xFFC0412A),
        mapBg         = Color(0xFFF3F8F0),
        mapLand       = Color(0xFFEBF4E5),
        mapWater      = Color(0xFFC4DCE8),
        mapRoad       = Color(0xFFFFFFFF),
        mapRoadMajor  = Color(0xFFD9C97A),
        mapPark       = Color(0xFFCBE8D0),
    )
    Palette.FOREST -> BiophilicColors(
        bg            = Color(0xFFF0F7F2),
        bg2           = Color(0xFFE5F0E8),
        surface       = Color(0xFFF5FAF6),
        surfaceElev   = Color(0xFFFFFFFF),
        ink           = Color(0xFF1A2E1E),
        ink2          = Color(0xFF2E4E33),
        ink3          = Color(0xFF4D6B51),
        line          = Color(0xFFB8D9BE),
        line2         = Color(0xFFCDE5D3),
        moss          = Color(0xFF2E7A3B),
        mossDeep      = Color(0xFF1B5227),
        mossPale      = Color(0xFFBEE8C9),
        mossPale2     = Color(0xFFA8DDB5),
        sun           = Color(0xFFCDA84A),
        clay          = Color(0xFFAF7350),
        recordRed     = Color(0xFFC0412A),
        mapBg         = Color(0xFFECF5EE),
        mapLand       = Color(0xFFE2F0E5),
        mapWater      = Color(0xFFC4DCE8),
        mapRoad       = Color(0xFFFFFFFF),
        mapRoadMajor  = Color(0xFFD9C97A),
        mapPark       = Color(0xFFA8DDB5),
    )
    Palette.COASTAL -> BiophilicColors(
        bg            = Color(0xFFF0F7FB),
        bg2           = Color(0xFFE3EFF6),
        surface       = Color(0xFFF6FBFE),
        surfaceElev   = Color(0xFFFFFFFF),
        ink           = Color(0xFF1E2E3A),
        ink2          = Color(0xFF344E60),
        ink3          = Color(0xFF567080),
        line          = Color(0xFFBDD4E0),
        line2         = Color(0xFFD3E5EF),
        moss          = Color(0xFF4B94B5),
        mossDeep      = Color(0xFF2D6D90),
        mossPale      = Color(0xFFBDD9E8),
        mossPale2     = Color(0xFFA5C9DC),
        sun           = Color(0xFFD0AE52),
        clay          = Color(0xFFB58B5C),
        recordRed     = Color(0xFFC0412A),
        mapBg         = Color(0xFFEBF4F8),
        mapLand       = Color(0xFFE2EFF4),
        mapWater      = Color(0xFFB5D3E4),
        mapRoad       = Color(0xFFFFFFFF),
        mapRoadMajor  = Color(0xFFD9C97A),
        mapPark       = Color(0xFFC8E0D4),
    )
    Palette.DESERT -> BiophilicColors(
        bg            = Color(0xFFF8F4EE),
        bg2           = Color(0xFFF0E9DF),
        surface       = Color(0xFFFCF9F5),
        surfaceElev   = Color(0xFFFFFFFF),
        ink           = Color(0xFF3A2E1E),
        ink2          = Color(0xFF5C4E38),
        ink3          = Color(0xFF7A6E5A),
        line          = Color(0xFFDDD0BD),
        line2         = Color(0xFFEAE0D0),
        moss          = Color(0xFF7D9148),
        mossDeep      = Color(0xFF5C6B2C),
        mossPale      = Color(0xFFD8E5B8),
        mossPale2     = Color(0xFFC8D9A0),
        sun           = Color(0xFFBDA44A),
        clay          = Color(0xFFAA6B40),
        recordRed     = Color(0xFFC0412A),
        mapBg         = Color(0xFFF5EEE4),
        mapLand       = Color(0xFFEDE5D8),
        mapWater      = Color(0xFFC4D4DC),
        mapRoad       = Color(0xFFFFFFFF),
        mapRoadMajor  = Color(0xFFD4C070),
        mapPark       = Color(0xFFC8D9A0),
    )
}

private fun darkBiophilicColors(): BiophilicColors = BiophilicColors(
    bg            = Color(0xFF1C2920),
    bg2           = Color(0xFF233226),
    surface       = Color(0xFF273829),
    surfaceElev   = Color(0xFF2E4031),
    ink           = Color(0xFFEDF5EE),
    ink2          = Color(0xFFC4D6C7),
    ink3          = Color(0xFF8CA98E),
    line          = Color(0xFF354536),
    line2         = Color(0xFF2E3D2F),
    moss          = Color(0xFF7AB885),
    mossDeep      = Color(0xFF9FCBA7),
    mossPale      = Color(0xFF2A4230),
    mossPale2     = Color(0xFF304E37),
    sun           = Color(0xFFCDA84A),
    clay          = Color(0xFFAF7350),
    recordRed     = Color(0xFFE05A40),
    mapBg         = Color(0xFF1E2E22),
    mapLand       = Color(0xFF243228),
    mapWater      = Color(0xFF2A3D52),
    mapRoad       = Color(0xFF3A4A3C),
    mapRoadMajor  = Color(0xFF6A6030),
    mapPark       = Color(0xFF2A4230),
)
