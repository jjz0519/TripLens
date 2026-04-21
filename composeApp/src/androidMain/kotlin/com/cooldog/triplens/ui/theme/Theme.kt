package com.cooldog.triplens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun TripLensTheme(
    palette: Palette = Palette.MOSS,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val bio = biophilicColors(palette, darkTheme)

    val m3Colors = if (darkTheme) {
        darkColorScheme(
            primary             = bio.moss,
            onPrimary           = bio.bg,
            primaryContainer    = bio.mossPale,
            onPrimaryContainer  = bio.mossDeep,
            secondary           = bio.sun,
            onSecondary         = bio.bg,
            background          = bio.bg,
            onBackground        = bio.ink,
            surface             = bio.surface,
            onSurface           = bio.ink,
            surfaceVariant      = bio.bg2,
            onSurfaceVariant    = bio.ink2,
            outline             = bio.line,
            outlineVariant      = bio.line2,
            error               = Color(0xFFD89B9B),
            onError             = Color(0xFF3B1414),
        )
    } else {
        lightColorScheme(
            primary             = bio.mossDeep,
            onPrimary           = Color(0xFFFFFFFF),
            primaryContainer    = bio.mossPale,
            onPrimaryContainer  = bio.mossDeep,
            secondary           = bio.clay,
            onSecondary         = Color(0xFFFFFFFF),
            background          = bio.bg,
            onBackground        = bio.ink,
            surface             = bio.surface,
            onSurface           = bio.ink,
            surfaceVariant      = bio.bg2,
            onSurfaceVariant    = bio.ink2,
            outline             = bio.line,
            outlineVariant      = bio.line2,
            error               = Color(0xFFBA6B6B),
            onError             = Color(0xFFFFFFFF),
        )
    }

    CompositionLocalProvider(LocalBiophilicColors provides bio) {
        MaterialTheme(colorScheme = m3Colors, content = content)
    }
}
