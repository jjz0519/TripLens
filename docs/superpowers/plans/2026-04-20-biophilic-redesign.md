# Biophilic UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing Morandi green UI with the Organic Biophilic design from the Claude Design prototype — pale sage + warm off-whites, Instrument Serif display font, organic shapes, progress ring, map-forward idle recording, and connected vertical timeline.

**Architecture:** A new `BiophilicColors` data class holds semantic design tokens beyond M3's ColorScheme roles (moss, moss-deep, moss-pale, sun, clay, surface, line, etc.). `TripLensTheme` provides this via `CompositionLocal` alongside the existing M3 theme. All screen composables replace Material component styling with biophilic token lookups. No business logic changes.

**Tech Stack:** Compose Multiplatform, Material 3, Compose Canvas (custom progress ring + mini-map), Google Fonts Downloadable Fonts API (Instrument Serif), existing SQLDelight/Koin/MapLibre stack unchanged.

**Design source:** `triplens/project/` — `TripLens.html`, `styles.css`, `components/*.jsx`

**Color reference (OKLCh → sRGB):**
| Token | OKLCh | Color hex |
|---|---|---|
| bg | 97.2% 0.012 110 | #F7FAF4 |
| bg-2 | 95% 0.018 115 | #EFF5EB |
| surface | 99% 0.008 110 | #FBFEFA |
| ink | 22% 0.02 155 | #2B3A2E |
| ink-2 | 38% 0.025 150 | #4B5F4E |
| ink-3 | 55% 0.02 145 | #737E74 |
| line | 88% 0.015 130 | #D2E1CF |
| line-2 | 92% 0.012 130 | #E3EEE1 |
| moss | 58% 0.08 150 | #5A9A64 |
| moss-deep | 42% 0.08 155 | #376E41 |
| moss-pale | 90% 0.035 145 | #D2EDD8 |
| moss-pale-2 | 85% 0.045 145 | #BDE4C6 |
| sun | 78% 0.12 75 | #CDA84A |
| clay | 62% 0.11 45 | #AF7350 |
| record-red | 55% 0.22 25 | #C0412A |

**Dark mode overrides:**
| Token | hex |
|---|---|
| bg | #1C2920 |
| surface | #273829 |
| ink | #EDF5EE |
| ink-2 | #C4D6C7 |
| ink-3 | #8CA98E |
| moss | #7AB885 |
| moss-deep | #9FCBA7 |
| moss-pale | #2A4230 |
| moss-pale-2 | #304E37 |
| line-2 | #2E3D2F |

---

## Task 20: Design Token System

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Color.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Theme.kt`
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/BiophilicColors.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/data/AppPreferences.kt`
- Modify: `composeApp/src/androidMain/res/values/strings.xml` (palette string keys)
- Test: `composeApp/src/androidInstrumentedTest/kotlin/com/cooldog/triplens/data/AppPreferencesTest.kt`

- [ ] **Step 1: Write failing test for palette preference**

```kotlin
// In AppPreferencesTest.kt — add to existing test class:
@Test
fun palette_defaultIsMoss() = runTest {
    val prefs = AppPreferences(context)
    assertEquals(Palette.MOSS, prefs.palette.first())
}

@Test
fun palette_writeAndReadBack() = runTest {
    val prefs = AppPreferences(context)
    prefs.setPalette(Palette.COASTAL)
    assertEquals(Palette.COASTAL, prefs.palette.first())
}
```

- [ ] **Step 2: Run test — expect compile failure** (`Palette` type missing)

```
./gradlew :composeApp:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cooldog.triplens.data.AppPreferencesTest
```

- [ ] **Step 3: Create `BiophilicColors.kt`**

```kotlin
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
    error("No BiophilicColors provided")
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
```

- [ ] **Step 4: Add `Palette` to `AppPreferences.kt`**

Open `AppPreferences.kt`. It already has `accuracyProfile` and `language` keys. Add palette:

```kotlin
// At top of file, add import:
import com.cooldog.triplens.ui.theme.Palette

// Add to companion object / keys:
private val PALETTE_KEY = stringPreferencesKey("palette")

// Add property:
val palette: Flow<Palette> = dataStore.data
    .map { prefs -> Palette.valueOf(prefs[PALETTE_KEY] ?: Palette.MOSS.name) }
    .catch { emit(Palette.MOSS) }

suspend fun setPalette(palette: Palette) {
    dataStore.edit { it[PALETTE_KEY] = palette.name }
}
```

- [ ] **Step 5: Update `Theme.kt` — wrap M3 + provide BiophilicColors**

Replace the entire `Theme.kt` content:

```kotlin
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
```

- [ ] **Step 6: Update `Color.kt` — keep only constants still needed by legacy code**

The existing `Color.kt` constants (LightPrimary, DarkPrimary, etc.) are now superseded by `BiophilicColors`. Replace the entire file with a single comment noting that colors are in `BiophilicColors.kt`, keeping only the file so existing imports don't break:

```kotlin
package com.cooldog.triplens.ui.theme

// Color tokens are defined in BiophilicColors.kt.
// This file is kept to avoid breaking any remaining import references.
```

- [ ] **Step 7: Pass palette from AppViewModel/AppPreferences down to TripLensTheme**

In `MainActivity.kt` or wherever `TripLensTheme` is called, collect the palette preference:

```kotlin
// In MainActivity or App composable:
val prefs = remember { AppPreferences(context) }
val palette by prefs.palette.collectAsState(initial = Palette.MOSS)
val darkTheme = isSystemInDarkTheme()

TripLensTheme(palette = palette, darkTheme = darkTheme) {
    // existing nav graph content
}
```

- [ ] **Step 8: Run tests**

```
./gradlew :composeApp:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cooldog.triplens.data.AppPreferencesTest
```

Expected: all palette tests PASS.

- [ ] **Step 9: Add Instrument Serif via Google Fonts Downloadable Fonts**

In `composeApp/build.gradle.kts`, confirm `androidx.compose.ui:ui-text-google-fonts` is present (it should be via the existing Compose BOM). Then create a typography helper:

Create `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Typography.kt`:

```kotlin
package com.cooldog.triplens.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = com.cooldog.triplens.R.array.com_google_android_gms_fonts_certs,
)

val InstrumentSerifFamily = FontFamily(
    Font(GoogleFont("Instrument Serif"), provider, FontWeight.Normal, FontStyle.Normal),
    Font(GoogleFont("Instrument Serif"), provider, FontWeight.Normal, FontStyle.Italic),
)

val InterFamily = FontFamily(
    Font(GoogleFont("Inter"), provider, FontWeight.Normal),
    Font(GoogleFont("Inter"), provider, FontWeight.Medium),
    Font(GoogleFont("Inter"), provider, FontWeight.SemiBold),
    Font(GoogleFont("Inter"), provider, FontWeight.Bold),
)
```

Add the GMS certs array resource. Create `composeApp/src/androidMain/res/values/font_certs.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="com_google_android_gms_fonts_certs">
        <item>@array/com_google_android_gms_fonts_certs_dev</item>
        <item>@array/com_google_android_gms_fonts_certs_prod</item>
    </array>
    <string-array name="com_google_android_gms_fonts_certs_dev">
        <item>MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNU2FuIEZyYW5jaXNjbzETMBEGA1UEChMKR29vZ2xlIEluYzEUMBIGA1UECxMLRW5naW5lZXJpbmcxFTATBgNVBAMTDGNsaWVudC5nb29nbGUwHhcNMDgwMTExMDMwOTAyWhcNMTcxMDA0MTQwOTAyWjCBkDELMAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNhbiBGcmFuY2lzY28xEzARBgNVBAoTCkdvb2dsZSBJbmMxFDASBgNVBAsTC0VuZ2luZWVyaW5nMRcwFQYDVQQDEw5jbGllbnQuZ29vZ2xlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2a2rwplBQLF29amygykEMmYz0+Kcj3bKBp29Li2B0EZBa3tMOTOhBFN/0YQPZ8PtOGIXFi51VSWJiCNnEMlFbZmBPHMsVGPCVjsGB7LyAqrVjzFzRCkJiDmgWaHRXS0A0MjJy1lpfR5DtD4c8B36Pu5KuQS5RKEFuF3mNfWcJBs0rL7wv/h8/YPLi9dfvzk0S3OGSz+6TsOy7YrXM/TSXM3Ae5Xd6MipM6kG2SnBjkuGQ8KhP+c/ALkWqR8OJ8hN5Bw/DV9a+W2YBiX8V7oaE/GvmJzCCekBX4I3VJe3V7R5YKUhf3dDMwU8KXMFJkfJJzaZcVBhYeBvHbQIDAQABo4HwMIHtMB0GA1UdDgQWBBSGDKD3hM6JLc1vEFujGU3EDKBBCjCBvQYDVR0jBIG1MIGygBSGDKD3hM6JLc1vEFujGU3EDKBBCqGBmKSBlTCBkjELMAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNhbiBGcmFuY2lzY28xEzARBgNVBAoTCkdvb2dsZSBJbmMxFDASBgNVBAsTC0VuZ2luZWVyaW5nMRkwFwYDVQQDExBjbGllbnQuZ29vZ2xlLmNvbYIJANWFuGx90071MAwGA1UdEwEB/wQCMAAwDQYJKoZIhvcNAQEEBQADggEBAC/s/7a0mOKniQKLFfSgqoiJcCbDFf7+LbpHfkC5VVrERKxjB0RlQhLQhXIZkNMfGf0N6SUJMKNT3KRLQM5H/A2MYl8jbFuoUwuKt3oEzFgAGPDWpRvbEkQv9NnX9GFHJnz/xUf6t7bQJjXJn+RVj0T0OsH+N+jfFWxv2aibHhXWzuWMf+aT0wH5R8J7vNJSuGj3kbm6bIpBrk5F7n7SdI0lXbKJ9+rXk+i2/Y1d8Kg2L1djYqmHCfv6ylD2m6Xpd1ZexYTFNww7XRK9hHeTcpAjW1YCMaKDJh9eTf0MSTC8E2GbDiIhS+QaL6kLqF/y0pjH0Ep0Cw3rOmBUXGWMVxcXK4WqCUSb8=</item>
    </string-array>
    <string-array name="com_google_android_gms_fonts_certs_prod">
        <item>MIIEQzCCAiugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHMxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjERMA8GA1UECxMIQW5kcm9pZHMxEDAOBgNVBAMTB2FuZHJvaWQwHhcNMDgwOTAwMDAwMDAwWhcNMzYwMTAxMDAwMDAwWjBzMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xETAPBgNVBAsTCEFuZHJvaWRzMRAwDgYDVQQDEwdhbmRyb2lkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1kMtAI1OAZF7BTxPJBe6oMMQiCMi7nYIe4MkVFiB9k8YDdirfBH2mfO9gJK0CKj6VXcajb1AHEpD9x1WjS0V8bV1Wq1SrHLHO4tHPH4e1xvJi5U5KPVElpz7MaFaKKo1AhpJIBs0JAz7UX6VkMDQjQqBQsChFD0F3jOQqS65Xjio3ykB2wfREJYzSB4K9qGEa2LMRNf3a2EKX5KKj9vB1WqIPJ0cWpgIQOaD/XT+bHwCbexKKp8RNNEQiQQYFOBLUGpbh0U1FMgm1w+ZzJJm6BEXKK7vLiWL1d5KUFaJJT8IUqUr9LGOJhfakw+lJVT38D2bH14YQsCIqlwIDAQABMA0GCSqGSIb3DQEBBAUAA4IBAQCOfhDUBnMHlhFMNRGE+zt3H3UtJj0QUMlAaZmQGXpCm/FBbH+IHKQi/+7BUbLIMQYaUVLJE7QL+tLFqxEH0ioKGFmGsX5JuqSmSb9mDrGJp4aPTsHCSmVx9c+PefJWGKNQGKgwC2lxUzJKlYlvPVF5SN5B4oQPkExeMPrMOTY9XvQJXCsP4y8mYPkHb+n0BSGMQ1Y+Pf3cP0pnjj4D3kIFvOjnnxZEiX1gNW5cMcVX5bDVHbK7R0F2lWkiKJi6oVtMNIbKVmM=</item>
    </string-array>
</resources>
```

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/BiophilicColors.kt
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Typography.kt
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Theme.kt
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/theme/Color.kt
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/data/AppPreferences.kt
git add composeApp/src/androidMain/res/values/font_certs.xml
git commit -m "feat: implement biophilic design token system (Task 20)"
```

---

## Task 21: Bottom Navigation Bar Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt`

Design spec from `bottom-nav.jsx`:
- 3 tabs: Trips (map icon), Record (mic icon), Settings (gear icon)
- Active tab: pill background `mossPale2`, text `mossDeep`
- Record tab during recording: pill tinted `recordRed/14%`, pulsing red dot (8×8dp animated alpha)
- Tab label: 11sp, weight 500
- Bottom nav surface: `surface`, 1dp top border `line2`

- [ ] **Step 1: Find where BottomNavigationBar is rendered**

In `AppNavGraph.kt` grep for `BottomNavigation` or `NavigationBar`. The existing code uses M3 `NavigationBar`.

- [ ] **Step 2: Replace `NavigationBar` block with biophilic design**

```kotlin
@Composable
private fun TripLensBottomNav(
    currentRoute: String?,
    isRecording: Boolean,
    onNavigate: (String) -> Unit,
) {
    val bio = LocalBiophilicColors.current
    val tabs = listOf(
        Triple(AppRoutes.TripList, "Trips", Icons.Outlined.Map),
        Triple(AppRoutes.Recording, "Record", Icons.Outlined.Mic),
        Triple(AppRoutes.Settings, "Settings", Icons.Outlined.Settings),
    )

    Surface(
        color = bio.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(color = bio.line2, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { (route, label, icon) ->
                val active = currentRoute == route
                val isRecord = route == AppRoutes.Recording

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onNavigate(route) }
                        .padding(vertical = 4.dp),
                ) {
                    // Pill indicator
                    val pillBg = when {
                        active && isRecord && isRecording -> bio.recordRed.copy(alpha = 0.14f)
                        active -> bio.mossPale2
                        else -> Color.Transparent
                    }
                    val contentColor = when {
                        active && isRecord && isRecording -> bio.recordRed
                        active -> bio.mossDeep
                        else -> bio.ink2
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(pillBg)
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(22.dp),
                        )
                        // Recording pulse dot
                        if (isRecord && isRecording) {
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 1f, targetValue = 0.3f,
                                animationSpec = infiniteRepeatable(
                                    tween(700, easing = FastOutSlowInEasing),
                                    RepeatMode.Reverse,
                                ),
                                label = "pulseAlpha",
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(bio.recordRed.copy(alpha = alpha)),
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                        letterSpacing = 0.3.sp,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire `isRecording` into nav graph**

In the composable that calls the bottom nav, collect `SessionRepository.getActiveSession()` or observe the `RecordingViewModel` state exposed via a shared `AppViewModel`:

```kotlin
val isRecording by appViewModel.isRecording.collectAsState()
TripLensBottomNav(
    currentRoute = currentRoute,
    isRecording  = isRecording,
    onNavigate   = { navController.navigate(it) { launchSingleTop = true } },
)
```

- [ ] **Step 4: Verify on emulator** — all 3 tabs navigate correctly, Record tab pulses during active session.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/AppNavGraph.kt
git commit -m "feat: biophilic bottom navigation bar with pill indicator (Task 21)"
```

---

## Task 22: Trip List Screen Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/TripListScreen.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/TripGroupItem.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/TrajectoryThumbnail.kt`

Design spec from `trips-screen.jsx`:
- **Header**: date string (13sp, `ink3`, UPPERCASE), "Your trips" in Instrument Serif 38sp, search icon button top-right; organic SVG blob behind header (Canvas)
- **Stat pills**: 3 equal-width cards (`surface`, `line2` border, 18dp radius): count number (Instrument Serif 22sp), LABEL 11sp
- **Filter chips**: "Recent" label + "All"/"This week" chips (`mossPale2` active, `line` border inactive)
- **Trip cards**: horizontal layout — 110×110dp mini-map on left, content on right; card has `surface` bg, `line2` border, 26dp radius
- **Mini-map**: Canvas drawing — `mapLand` rect, `mapWater` bezier path (top-left corner), `mossDeep` route polyline (strokeWidth=2.5dp), `moss` start dot, `clay` end dot, `sun` moment dots
- **Card content**: title (Instrument Serif 20sp, `ink`), location row (12sp, `ink3`, location pin icon), meta tags (11sp): distance, duration, "N moments" in `mossDeep`

- [ ] **Step 1: Write failing snapshot or assertion test for stat aggregation**

```kotlin
// In TripListViewModelTest.kt — add:
@Test
fun statPills_aggregateCorrectly() = runTest {
    // Insert 2 groups with known session counts
    val groupId1 = repo.createGroup("Trip A")
    val groupId2 = repo.createGroup("Trip B")
    sessionRepo.createSession(groupId1, startTime = 1000L)
    sessionRepo.createSession(groupId2, startTime = 2000L)
    sessionRepo.createSession(groupId2, startTime = 3000L)

    val vm = TripListViewModel(tripRepo, sessionRepo, trackRepo, mediaRepo, noteRepo, exportUseCase)
    val state = vm.uiState.first()

    assertEquals(2, state.groups.size)
    // group2 has 2 sessions
    assertEquals(2, state.groups.first { it.name == "Trip B" }.sessionCount)
}
```

- [ ] **Step 2: Run test — expect PASS** (logic is existing, just verifying it works)

- [ ] **Step 3: Rewrite `TripListScreen.kt` header + stat section**

```kotlin
@Composable
fun TripListScreen(
    viewModel: TripListViewModel,
    onGroupClick: (groupId: String) -> Unit,
) {
    val bio = LocalBiophilicColors.current
    val uiState by viewModel.uiState.collectAsState()
    // ... existing dialog state vars ...

    Scaffold(
        containerColor = bio.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { TripListHeader(bio, uiState) }
            item { TripListStatPills(bio, uiState) }
            item { TripListFilterRow(bio) }
            items(uiState.groups, key = { it.id }) { group ->
                TripGroupCard(
                    group    = group,
                    bio      = bio,
                    onClick  = { onGroupClick(group.id) },
                    onRename = { renameTarget = group },
                    onDelete = { deleteTarget = group },
                    onExport = { viewModel.onExportGroup(group.id) },
                    exportInProgress = exportState is ExportState.InProgress,
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun TripListHeader(bio: BiophilicColors, uiState: TripListUiState) {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        // Organic blob background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val path = Path().apply {
                moveTo(-20f, 100.dp.toPx())
                cubicTo(60.dp.toPx(), 60.dp.toPx(), 140.dp.toPx(), 130.dp.toPx(), 220.dp.toPx(), 80.dp.toPx())
                cubicTo(290.dp.toPx(), 40.dp.toPx(), 360.dp.toPx(), 90.dp.toPx(), w + 20, 60.dp.toPx())
                lineTo(w + 20, -20f)
                lineTo(-20f, -20f)
                close()
            }
            drawPath(path, color = bio.mossPale2.copy(alpha = 0.35f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                val today = remember {
                    java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault())
                        .format(java.util.Date())
                }
                Text(
                    text = today.uppercase(),
                    fontSize = 12.sp,
                    color = bio.ink3,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = "Your trips",
                    fontFamily = InstrumentSerifFamily,
                    fontSize = 38.sp,
                    color = bio.ink,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.02).em,
                )
            }
            IconButton(
                onClick = { /* search — future */ },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(bio.surface)
                    .border(1.dp, bio.line2, CircleShape),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = "Search", tint = bio.ink2)
            }
        }
    }
}

@Composable
private fun TripListStatPills(bio: BiophilicColors, uiState: TripListUiState) {
    Row(
        modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatPill(bio, label = "TRIPS",    value = uiState.groups.size.toString())
        StatPill(bio, label = "DISTANCE", value = "${uiState.totalDistanceKm.roundTo(1)} km")
        StatPill(bio, label = "MOMENTS",  value = uiState.totalMoments.toString())
    }
}

@Composable
private fun RowScope.StatPill(bio: BiophilicColors, label: String, value: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(value, fontFamily = InstrumentSerifFamily, fontSize = 22.sp, color = bio.ink, lineHeight = 22.sp)
        Text(label, fontSize = 10.sp, color = bio.ink3, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium)
    }
}
```

- [ ] **Step 4: Rewrite `TripGroupItem` / `TripGroupCard` composable**

```kotlin
@Composable
fun TripGroupCard(
    group: TripGroupItem,
    bio: BiophilicColors,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    exportInProgress: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = bio.surface),
        border = BorderStroke(1.dp, bio.line2),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Mini-map thumbnail (110×110dp)
            BiophilicMiniMap(
                bio = bio,
                trackPoints = group.trajectoryPoints,
                momentCount = group.momentCount,
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)),
            )

            // Content
            Column(
                modifier = Modifier.weight(1f).padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = group.name,
                        fontFamily = InstrumentSerifFamily,
                        fontSize = 20.sp,
                        color = bio.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                            if (exportInProgress) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = bio.moss, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.MoreVert, null, tint = bio.ink3, modifier = Modifier.size(18.dp))
                            }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) })
                            DropdownMenuItem(text = { Text("Export") }, onClick = { menuOpen = false; onExport() },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, null) }, enabled = !exportInProgress)
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                }

                if (group.location.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Outlined.LocationOn, null, tint = bio.ink3, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(group.location, fontSize = 12.sp, color = bio.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetaTag("${group.totalDistanceKm.roundTo(1)} km", bio)
                    MetaTag(group.durationLabel, bio)
                    MetaTag("${group.momentCount} moments", bio, highlight = true)
                }
            }
        }
    }
}

@Composable
private fun MetaTag(text: String, bio: BiophilicColors, highlight: Boolean = false) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (highlight) bio.mossDeep else bio.ink3)
}
```

- [ ] **Step 5: Rewrite `TrajectoryThumbnail.kt` → `BiophilicMiniMap`**

```kotlin
@Composable
fun BiophilicMiniMap(
    bio: BiophilicColors,
    trackPoints: List<LatLng>,  // simplified coords from TripGroup bounding box
    momentCount: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.background(bio.mapLand)) {
        val w = size.width; val h = size.height

        // Water blob (top-left)
        val waterPath = Path().apply {
            moveTo(0f, h * 0.27f)
            quadraticBezierTo(w * 0.36f, h * 0.09f, w * 0.64f, h * 0.36f)
            quadraticBezierTo(w * 0.82f, h * 0.5f, w, h * 0.45f)
            lineTo(w, 0f); lineTo(0f, 0f); close()
        }
        drawPath(waterPath, color = bio.mapWater.copy(alpha = 0.6f))

        // Route polyline
        if (trackPoints.size >= 2) {
            val bounds = computeBounds(trackPoints)
            val path = Path()
            trackPoints.forEachIndexed { i, pt ->
                val x = ((pt.longitude - bounds.left) / (bounds.right - bounds.left)).toFloat() * w * 0.8f + w * 0.1f
                val y = (1f - ((pt.latitude - bounds.bottom) / (bounds.top - bounds.bottom)).toFloat()) * h * 0.8f + h * 0.1f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = bio.mossDeep, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Start dot
            val startPt = trackPoints.first().toCanvasXY(bounds, w, h)
            drawCircle(bio.moss, radius = 4.dp.toPx(), center = startPt)
            // End dot
            val endPt = trackPoints.last().toCanvasXY(bounds, w, h)
            drawCircle(bio.clay, radius = 4.dp.toPx(), center = endPt)
        }

        // Moment dots (up to 4, spread along route)
        val dotPositions = listOf(
            Offset(w * 0.45f, h * 0.36f),
            Offset(w * 0.65f, h * 0.56f),
            Offset(w * 0.35f, h * 0.64f),
            Offset(w * 0.55f, h * 0.73f),
        )
        for (i in 0 until minOf(momentCount, 4)) {
            drawCircle(bio.sun, radius = 2.5.dp.toPx(), center = dotPositions[i])
            drawCircle(Color.White, radius = 2.5.dp.toPx(), center = dotPositions[i], style = Stroke(1.dp.toPx()))
        }
    }
}

private fun LatLng.toCanvasXY(bounds: LatLngBounds, w: Float, h: Float): Offset {
    val x = ((longitude - bounds.left) / (bounds.right - bounds.left)).toFloat() * w * 0.8f + w * 0.1f
    val y = (1f - ((latitude - bounds.bottom) / (bounds.top - bounds.bottom)).toFloat()) * h * 0.8f + h * 0.1f
    return Offset(x, y)
}
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/triplist/
git commit -m "feat: biophilic trip list screen — editorial header, stat pills, mini-map cards (Task 22)"
```

---

## Task 23: Recording Idle Screen Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingIdleContent.kt`

Design spec from `record-screen.jsx` → `IdleRecord`:
- Full-height MapLibre map (no route, user dot visible)
- Top overlay card: 16dp inset, `surface` bg, 16dp radius, location pin icon in `mossPale` square (32×32dp, 10dp radius), title 13sp bold, GPS status row with 6×6dp `moss` dot + "GPS locked · ±3m" text, layer button right
- Re-center button: right side 40×40dp, `surface` bg, 12dp radius, `mossDeep` icon
- Bottom glass panel: `surface` bg, 22dp radius, inset 12dp, `shadow-m` — MiniOrb (44×44dp animated) + description + "Start" button (`mossDeep` bg, `bg` text, 999dp radius, red dot prefix)
- Footer tip: 26×26dp `mossPale` box (8dp radius) with leaf icon + tip text

- [ ] **Step 1: Rewrite `RecordingIdleContent.kt`**

```kotlin
@Composable
internal fun RecordingIdleContent(
    uiState: RecordingViewModel.UiState,
    onStartTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bio = LocalBiophilicColors.current

    Column(modifier = modifier.fillMaxSize().background(bio.bg)) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(
                "READY WHEN YOU ARE",
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp, color = bio.ink3,
            )
            Text(
                "Begin a new trip",
                fontFamily = InstrumentSerifFamily,
                fontSize = 28.sp, color = bio.ink,
                letterSpacing = (-0.015).em, lineHeight = 30.sp,
            )
        }

        // Map with overlays
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, bio.line2, RoundedCornerShape(24.dp)),
        ) {
            // MapLibre map (existing MapView integration, no route, user dot only)
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply { /* existing MapLibre setup, showRoute=false */ }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // GPS card (top overlay)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bio.surface)
                    .border(1.dp, bio.line2, RoundedCornerShape(16.dp))
                    .padding(10.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(bio.mossPale),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.LocationOn, null, tint = bio.mossDeep, modifier = Modifier.size(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Current location", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bio.ink)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(bio.moss))
                        Text("GPS locked · ±3 m", fontSize = 11.sp, color = bio.ink3)
                    }
                }
                IconButton(
                    onClick = { /* map style */ },
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(bio.bg2),
                ) {
                    Icon(Icons.Outlined.Layers, null, tint = bio.ink2, modifier = Modifier.size(14.dp))
                }
            }

            // Re-center button
            IconButton(
                onClick = { /* re-center map */ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 130.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bio.surface)
                    .border(1.dp, bio.line2, RoundedCornerShape(12.dp)),
            ) {
                Icon(Icons.Outlined.MyLocation, null, tint = bio.mossDeep, modifier = Modifier.size(18.dp))
            }

            // Start panel (bottom glass)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(bio.surface)
                    .border(1.dp, bio.line2, RoundedCornerShape(22.dp))
                    .padding(14.dp, 14.dp, 14.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniOrb(bio)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tap to start", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = bio.ink)
                    Text(
                        "Route, photos, notes & voice — woven together.",
                        fontSize = 11.sp, color = bio.ink3, lineHeight = 15.sp,
                    )
                }
                Button(
                    onClick = onStartTapped,
                    enabled = uiState == RecordingViewModel.UiState.Idle,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = bio.mossDeep,
                        contentColor   = bio.bg,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (uiState == RecordingViewModel.UiState.StartingSession) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = bio.bg, strokeWidth = 2.dp)
                    } else {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(bio.recordRed))
                        Spacer(Modifier.width(8.dp))
                        Text("Start", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Footer tip
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(bio.mossPale),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.EnergySavingsLeaf, null, tint = bio.mossDeep, modifier = Modifier.size(14.dp))
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = bio.ink2, fontWeight = FontWeight.Medium)) { append("Auto-pause") }
                    withStyle(SpanStyle(color = bio.ink3)) { append(" when you stand still — saves battery, preserves moments.") }
                },
                fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun MiniOrb(bio: BiophilicColors) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val r1 by infiniteTransition.animateFloat(18f, 21f,
        infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Reverse), "r1")
    val r2 by infiniteTransition.animateFloat(14f, 16f,
        infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse), "r2")

    Canvas(modifier = Modifier.size(44.dp)) {
        val c = center
        drawCircle(bio.mossPale.copy(alpha = 0.5f), radius = r1.dp.toPx(), center = c)
        drawCircle(bio.mossPale2, radius = r2.dp.toPx(), center = c)
        drawCircle(bio.moss.copy(alpha = 0.95f), radius = 10.dp.toPx(), center = c)
        drawCircle(bio.bg, radius = 4.dp.toPx(), center = c)
    }
}
```

- [ ] **Step 2: Verify on emulator** — idle recording screen shows map with overlays.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingIdleContent.kt
git commit -m "feat: biophilic recording idle — map-forward with GPS card and start panel (Task 23)"
```

---

## Task 24: Recording Active Screen Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/RecordingActiveContent.kt`
- Create: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/ProgressRing.kt`

Design spec from `record-screen.jsx` → `ActiveHeader`, `ProgressRing`, `CaptureBtn`, `MomentRow`, `RecordDock`:

**Header**: 60×60dp progress ring (left) + elapsed/distance metrics (right). Ring: 24dp radius circle, 3dp stroke, arc from top showing elapsed/3600, moment petal dots (1.8dp) at ring+6dp radius. Pulsing red dot 7dp for recording indicator.

**Capture bar**: 3 equal-weight buttons (16dp radius): Text=`mossPale`/`mossDeep`, Voice=`clay/10%`/`clay-deep`, Photo=`sun/10%`/`sun-deep`.

**Timeline**: Reversed chronological moments list. Each row: 28dp circle icon (type-colored) on left + `surface` card on right (14dp radius, `line2` border), title+timestamp row, preview text.

**Bottom dock**: gradient from `bg` (transparent top to solid bottom). Center: 72dp stop button (`recordRed`, white stop icon, outer ring). Flanked by 52dp `surface` circle buttons (pause left, re-center right).

- [ ] **Step 1: Create `ProgressRing.kt`**

```kotlin
package com.cooldog.triplens.ui.recording

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.ui.theme.BiophilicColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ProgressRing(
    elapsedSeconds: Int,
    momentCount: Int,
    paused: Boolean,
    bio: BiophilicColors,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val size = 60.dp.toPx()
        val center = Offset(size / 2, size / 2)
        val radius = 24.dp.toPx()
        val strokeW = 3.dp.toPx()

        // Track ring background
        drawCircle(bio.line2, radius = radius, center = center, style = Stroke(strokeW))

        // Elapsed arc (clockwise from top)
        val fraction = (elapsedSeconds / 3600f).coerceIn(0f, 1f)
        val sweepAngle = fraction * 360f
        val arcColor = if (paused) bio.sun else bio.mossDeep
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(strokeW, cap = StrokeCap.Round),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        )

        // Moment petals (up to 12 dots orbiting ring)
        val petals = minOf(momentCount, 12)
        val petalRadius = 1.8.dp.toPx()
        val orbitR = radius + 6.dp.toPx()
        for (i in 0 until petals) {
            val angle = (i.toDouble() / 12.0) * 2 * PI - PI / 2
            val px = (center.x + cos(angle) * orbitR).toFloat()
            val py = (center.y + sin(angle) * orbitR).toFloat()
            drawCircle(bio.clay, radius = petalRadius, center = Offset(px, py))
        }

        // Inner dot (pulsing is handled via animateFloat outside, pass alpha)
        val dotColor = if (paused) bio.sun else bio.recordRed
        drawCircle(dotColor.copy(alpha = 0.12f), radius = 14.dp.toPx(), center = center)
        drawCircle(dotColor, radius = 6.dp.toPx(), center = center)
    }
}
```

- [ ] **Step 2: Rewrite active recording header in `RecordingActiveContent.kt`**

```kotlin
@Composable
private fun ActiveRecordingHeader(
    elapsedSeconds: Int,
    distanceKm: Float,
    momentCount: Int,
    paused: Boolean,
    bio: BiophilicColors,
) {
    val infiniteTransition = rememberInfiniteTransition("pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        1f, 0.3f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), "dotAlpha",
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProgressRing(
            elapsedSeconds = elapsedSeconds,
            momentCount    = momentCount,
            paused         = paused,
            bio            = bio,
            modifier       = Modifier.size(60.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (paused) {
                    Icon(Icons.Outlined.Pause, null, tint = bio.sun, modifier = Modifier.size(10.dp))
                    Text("PAUSED", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = bio.sun, letterSpacing = 0.6.sp)
                } else {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(bio.recordRed.copy(alpha = dotAlpha)))
                    Text("RECORDING", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = bio.recordRed, letterSpacing = 0.6.sp)
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = formatElapsed(elapsedSeconds),
                    fontFamily = InstrumentSerifFamily,
                    fontSize = 26.sp, color = bio.ink, letterSpacing = (-0.02).em,
                )
                Box(Modifier.width(1.dp).height(16.dp).background(bio.line).align(Alignment.CenterVertically))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.2f".format(distanceKm),
                        fontFamily = InstrumentSerifFamily,
                        fontSize = 19.sp, color = bio.ink2,
                    )
                    Text(" km", fontSize = 12.sp, color = bio.ink3)
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
```

- [ ] **Step 3: Rewrite capture bar and moment timeline**

```kotlin
@Composable
private fun CaptureBar(bio: BiophilicColors, onText: () -> Unit, onVoice: () -> Unit, onPhoto: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CaptureButton("Text",  bio.mossPale,            bio.mossDeep, Icons.Outlined.TextFields, onText,  Modifier.weight(1f))
        CaptureButton("Voice", bio.clay.copy(alpha=0.1f), bio.clay,    Icons.Outlined.Mic,         onVoice, Modifier.weight(1f))
        CaptureButton("Photo", bio.sun.copy(alpha=0.1f),  bio.sun,     Icons.Outlined.CameraAlt,   onPhoto, Modifier.weight(1f))
    }
}

@Composable
private fun CaptureButton(label: String, bg: Color, fg: Color, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val bio = LocalBiophilicColors.current
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        elevation = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MomentTimelineRow(moment: RecordedMoment, bio: BiophilicColors) {
    val (iconBg, iconFg, icon) = when (moment.type) {
        MomentType.TEXT  -> Triple(bio.mossPale,              bio.mossDeep, Icons.Outlined.TextFields)
        MomentType.PHOTO -> Triple(bio.sun.copy(alpha=0.12f), bio.sun,      Icons.Outlined.Photo)
        MomentType.VOICE -> Triple(bio.clay.copy(alpha=0.1f), bio.clay,     Icons.Outlined.Mic)
        MomentType.PAUSE -> Triple(bio.bg2,                   bio.ink3,     Icons.Outlined.Pause)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconFg, modifier = Modifier.size(14.dp))
        }
        Column(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(bio.surface).border(1.dp, bio.line2, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(moment.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = bio.ink)
                Text(moment.timeLabel, fontSize = 11.sp, color = bio.ink3, fontWeight = FontWeight.Medium)
            }
            if (moment.preview.isNotBlank()) {
                Text(moment.preview, fontSize = 12.sp, color = bio.ink3, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
```

- [ ] **Step 4: Rewrite bottom dock**

```kotlin
@Composable
private fun RecordingBottomDock(paused: Boolean, onStop: () -> Unit, onPause: () -> Unit, bio: BiophilicColors) {
    Box(
        modifier = Modifier.fillMaxWidth().height(100.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, bio.bg))),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pause button
            IconButton(
                onClick = onPause,
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(bio.surface).border(1.dp, bio.line2, CircleShape),
            ) {
                val icon = if (paused) Icons.Filled.PlayArrow else Icons.Outlined.Pause
                Icon(icon, null, tint = bio.ink2, modifier = Modifier.size(20.dp))
            }

            // Stop button (large, red)
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape)
                        .border(2.dp, bio.recordRed.copy(alpha = 0.25f), CircleShape)
                )
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(bio.recordRed),
                ) {
                    Icon(Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }

            // Re-center button
            IconButton(
                onClick = { /* re-center map */ },
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(bio.surface).border(1.dp, bio.line2, CircleShape),
            ) {
                Icon(Icons.Outlined.MyLocation, null, tint = bio.ink2, modifier = Modifier.size(20.dp))
            }
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/recording/
git commit -m "feat: biophilic recording active — progress ring, capture bar, moment timeline, bottom dock (Task 24)"
```

---

## Task 25: Session Review Screen Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/sessionreview/SessionReviewScreen.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/sessionreview/TimelineItem.kt`

Design spec from `session-screen.jsx`:
- **Top bar**: back button (40dp circle) + title block (11sp uppercase date, 22sp Instrument Serif session name) + share + more icons
- **Stat ribbon**: 4 equal cards (16dp radius): Distance (primary=`mossPale` bg), Duration, Moments, Pace
- **Map**: 16dp horizontal margin, 24dp radius, 280dp height, mode pill overlays top-left, zoom buttons bottom-right
- **Activity strip**: 8dp height segmented bar (walking=`moss`, stationary=`sun`), legend below with dot + label + dist/time
- **Timeline**: "Timeline" 13sp bold header; connected vertical line (`mossPale2`, 2dp) at x=19dp; each item = 40dp circle icon + `surface` card (16dp radius), active highlight `mossPale` bg

- [ ] **Step 1: Rewrite `SessionReviewScreen.kt` top bar and stat ribbon**

```kotlin
@Composable
fun SessionReviewScreen(
    viewModel: SessionReviewViewModel,
    onBack: () -> Unit,
) {
    val bio = LocalBiophilicColors.current
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(containerColor = bio.bg) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = bio.ink2)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(uiState.dateLabel.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp, color = bio.ink3)
                    Text(uiState.sessionName, fontFamily = InstrumentSerifFamily, fontSize = 22.sp,
                        color = bio.ink, lineHeight = 24.sp, letterSpacing = (-0.01).em)
                }
                IconButton(onClick = { viewModel.onExport() }, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                    Icon(Icons.Outlined.Share, null, tint = bio.ink2)
                }
            }

            // Stat ribbon
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionStat(bio, "km",      uiState.distanceKm.roundTo(1).toString(), "DISTANCE", primary = true, modifier = Modifier.weight(1f))
                SessionStat(bio, null,      uiState.durationLabel,                    "DURATION",                 modifier = Modifier.weight(1f))
                SessionStat(bio, null,      uiState.momentCount.toString(),            "MOMENTS",                  modifier = Modifier.weight(1f))
                SessionStat(bio, "min/km",  uiState.paceLabel,                        "PACE",                     modifier = Modifier.weight(1f))
            }

            // Scrollable content
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                // Map
                item { SessionMap(bio, uiState) }
                // Activity strip
                item { ActivityStrip(bio, uiState.segments) }
                // Timeline
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Timeline", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bio.ink2)
                        Text("${uiState.momentCount} moments", fontSize = 11.sp, color = bio.ink3)
                    }
                }
                itemsIndexed(uiState.timeline) { index, moment ->
                    BiophilicTimelineItem(
                        moment     = moment,
                        bio        = bio,
                        isLast     = index == uiState.timeline.lastIndex,
                        isActive   = uiState.selectedMomentIndex == index,
                        onClick    = { viewModel.onMomentSelected(index) },
                    )
                }
                item {
                    Text(
                        "— end of session —",
                        fontSize = 12.sp, color = bio.ink3,
                        modifier = Modifier.padding(start = 70.dp, top = 10.dp, bottom = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionStat(bio: BiophilicColors, unit: String?, value: String, label: String, primary: Boolean = false, modifier: Modifier) {
    val bg = if (primary) bio.mossPale else bio.surface
    val border = if (primary) Color.Transparent else bio.line2
    val textColor = if (primary) bio.mossDeep else bio.ink

    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = InstrumentSerifFamily, fontSize = 20.sp, color = textColor, lineHeight = 20.sp)
            if (unit != null) Text(" $unit", fontSize = 10.sp, color = if (primary) bio.mossDeep else bio.ink3, fontWeight = FontWeight.Medium)
        }
        Text(label, fontSize = 10.sp, color = if (primary) bio.mossDeep else bio.ink3,
            letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ActivityStrip(bio: BiophilicColors, segments: List<TransportSegmentDisplay>) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.forEach { seg ->
                Box(
                    modifier = Modifier.weight(seg.percentOfTotal).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(segmentColor(seg.mode, bio).copy(alpha = 0.85f)),
                )
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            segments.forEach { seg ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(segmentColor(seg.mode, bio)))
                    Text(seg.label, fontSize = 11.sp, color = bio.ink2)
                    Text(listOfNotNull(seg.distanceLabel, seg.durationLabel).joinToString(" · "), fontSize = 11.sp, color = bio.ink3)
                }
            }
        }
    }
}

private fun segmentColor(mode: TransportMode, bio: BiophilicColors) = when (mode) {
    TransportMode.WALKING    -> bio.moss
    TransportMode.STATIONARY -> bio.sun
    TransportMode.CYCLING    -> bio.clay
    TransportMode.DRIVING    -> bio.mossDeep
    TransportMode.FAST_TRANSIT -> bio.ink2
}
```

- [ ] **Step 2: Rewrite `TimelineItem.kt` → `BiophilicTimelineItem`**

```kotlin
@Composable
fun BiophilicTimelineItem(
    moment: TimelineEvent,
    bio: BiophilicColors,
    isLast: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val (iconBg, iconFg) = timelineItemColors(moment.type, bio)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 0.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Left: connector line + circle icon
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(timelineIcon(moment.type), null, tint = iconFg, modifier = Modifier.size(18.dp))
            }
            if (!isLast) {
                Box(Modifier.width(2.dp).height(16.dp).background(bio.mossPale2).padding(vertical = 2.dp))
            }
        }

        // Right: card
        val cardBg = if (isActive) bio.mossPale else bio.surface
        val cardBorder = if (isActive) bio.mossPale2 else bio.line2

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 0.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(moment.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bio.ink)
                Text(moment.timeLabel, fontSize = 11.sp, color = bio.ink3, fontWeight = FontWeight.Medium)
            }
            when (moment) {
                is TimelineEvent.TextNote -> if (moment.body.isNotBlank()) {
                    Text(moment.body, fontSize = 13.sp, color = bio.ink2, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                }
                is TimelineEvent.VoiceNote -> AudioWaveformRow(bio, moment.durationSeconds)
                is TimelineEvent.Photo -> PhotoThumbnailRow(moment.contentUris)
                is TimelineEvent.Pause -> Text("Stayed here ${moment.dwellLabel}", fontSize = 12.sp, color = bio.ink3, modifier = Modifier.padding(top = 4.dp))
                else -> Unit
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AudioWaveformRow(bio: BiophilicColors, durationSec: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(999.dp))
            .background(bio.bg2).padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(bio.clay), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        Canvas(modifier = Modifier.weight(1f).height(20.dp)) {
            val barW = 2.5.dp.toPx(); val gap = 3.dp.toPx()
            val bars = (size.width / (barW + gap)).toInt()
            for (i in 0 until bars) {
                val h = (3 + Math.abs(Math.sin(i * 0.6)) * 10 + (i % 3) * 2).toFloat().dp.toPx()
                val alpha = if (i < bars / 3) 1f else 0.35f
                drawRect(bio.clay.copy(alpha = alpha), topLeft = Offset(i * (barW + gap), (size.height - h) / 2), size = androidx.compose.ui.geometry.Size(barW, h))
            }
        }
        Text("0:%02d".format(durationSec), fontSize = 11.sp, color = bio.ink3, fontWeight = FontWeight.Medium)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/sessionreview/
git commit -m "feat: biophilic session review — stat ribbon, activity strip, connected timeline (Task 25)"
```

---

## Task 26: Trip Detail Screen Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/tripdetail/TripDetailScreen.kt`

Design: same biophilic tokens. Header matches Session Review top-bar style. Session list items: each session gets a card with date+time range, duration+distance, transport breakdown icons (emoji or colored dots). Export FAB becomes a Share icon in the top bar.

- [ ] **Step 1: Rewrite `TripDetailScreen.kt` header**

```kotlin
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    onBack: () -> Unit,
    onSessionClick: (sessionId: String) -> Unit,
) {
    val bio = LocalBiophilicColors.current
    val uiState by viewModel.uiState.collectAsState()
    val exportState by viewModel.exportState.collectAsState()

    Scaffold(containerColor = bio.bg) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Top bar (same pattern as SessionReviewScreen)
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(40.dp).clip(CircleShape)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = bio.ink2)
                }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(uiState.dateRange.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp, color = bio.ink3)
                    Text(uiState.groupName, fontFamily = InstrumentSerifFamily, fontSize = 22.sp,
                        color = bio.ink, lineHeight = 24.sp)
                }
                if (exportState is ExportState.InProgress) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = bio.moss, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.onExportGroup() }, Modifier.size(40.dp).clip(CircleShape)) {
                        Icon(Icons.Outlined.Share, null, tint = bio.ink2)
                    }
                }
            }

            // Stat pills
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPillSmall(bio, uiState.sessionCount.toString(), "SESSIONS", Modifier.weight(1f))
                StatPillSmall(bio, "${uiState.totalDistanceKm.roundTo(1)} km", "DISTANCE", Modifier.weight(1f))
                StatPillSmall(bio, uiState.totalMoments.toString(), "MOMENTS", Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            Text("Sessions", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bio.ink2,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.sessions) { session ->
                    SessionCard(bio, session, onClick = { onSessionClick(session.id) })
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun SessionCard(bio: BiophilicColors, session: SessionDisplayItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(bio.surface).border(1.dp, bio.line2, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(session.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = bio.ink)
            Text(session.timeRange, fontSize = 11.sp, color = bio.ink3)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${session.distanceKm.roundTo(1)} km", fontSize = 12.sp, color = bio.ink2)
            Text(session.durationLabel, fontSize = 12.sp, color = bio.ink2)
        }
        if (session.transportBreakdown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.transportBreakdown.forEach { seg ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(segmentColor(seg.mode, bio)))
                        Text("${seg.distanceKm.roundTo(1)} km", fontSize = 11.sp, color = bio.ink3)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/tripdetail/TripDetailScreen.kt
git commit -m "feat: biophilic trip detail screen — session cards with transport breakdown (Task 26)"
```

---

## Task 27: Settings Screen Redesign

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/settings/SettingsScreen.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/settings/SettingsViewModel.kt`

Design spec from `settings-screen.jsx`:
- **Header**: "Settings" in Instrument Serif 34sp
- **Profile card**: `surface` bg, 24dp radius, organic blob SVG behind (Canvas), 52dp circle avatar (`moss→mossDeep` gradient), name + trip/moment count, Edit chip
- **Sections**: `SectionTitle` (11sp, `ink3`, uppercase 0.8 letterSpacing), `Group` container (`surface` bg, 20dp radius, `line2` border)
- **RowLink**: 28dp icon box (`mossPale` bg, 9dp radius, `mossDeep` icon) + title 14sp + value `ink3` + chevron
- **RowToggle**: same icon box, M3-style custom toggle (`mossDeep` track when on)
- **Palette selector**: 2×2 grid, each cell shows 3 overlapping color circles + label
- **Segmented control**: pill buttons in `bg2` container, active = `surface` + `shadow-s`
- **New row**: Language now routes to system setting; add Palette row at top of Appearance

- [ ] **Step 1: Add `onPaletteSelected` to `SettingsViewModel`**

```kotlin
// In SettingsViewModel.kt, add alongside onLanguageSelected:
fun onPaletteSelected(palette: Palette) {
    viewModelScope.launch {
        appPreferences.setPalette(palette)
    }
}
```

- [ ] **Step 2: Rewrite `SettingsScreen.kt`**

```kotlin
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val bio = LocalBiophilicColors.current
    val uiState by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize().background(bio.bg)) {
        Text("Settings", fontFamily = InstrumentSerifFamily, fontSize = 34.sp, color = bio.ink,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))

        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            item { ProfileCard(bio, uiState) }
            item { SettingsSectionTitle(bio, "Appearance") }
            item {
                SettingsGroup(bio) {
                    PaletteRow(bio, uiState.palette, viewModel::onPaletteSelected)
                    SettingsDivider(bio)
                    ToggleRow(bio, Icons.Outlined.DarkMode, "Dark theme", uiState.darkTheme) { viewModel.onDarkThemeToggled() }
                    SettingsDivider(bio)
                    SegmentedRow(bio, Icons.Outlined.TextFormat, "Typography",
                        options = listOf("Editorial", "Humanist", "Journal"),
                        selected = uiState.typefaceIndex,
                        onSelect = { viewModel.onTypefaceSelected(it) })
                }
            }

            item { SettingsSectionTitle(bio, "Capture") }
            item {
                SettingsGroup(bio) {
                    LinkRow(bio, Icons.Outlined.Mic, "Audio quality", "High · 64 kbps")
                    SettingsDivider(bio)
                    ToggleRow(bio, Icons.Outlined.Pause, "Auto-pause when stationary", uiState.autoPause) { viewModel.onAutoPauseToggled() }
                    SettingsDivider(bio)
                    ToggleRow(bio, Icons.Outlined.LocationOn, "Background tracking", uiState.backgroundTracking) { viewModel.onBackgroundTrackingToggled() }
                }
            }

            item { SettingsSectionTitle(bio, "General") }
            item {
                SettingsGroup(bio) {
                    LinkRow(bio, Icons.Outlined.Language, "Language", uiState.languageLabel)
                    SettingsDivider(bio)
                    LinkRow(bio, Icons.Outlined.HelpOutline, "Help & feedback")
                }
            }

            item {
                Text("TripLens · Local-first travel recording",
                    fontSize = 11.sp, color = bio.ink3, textAlign = TextAlign.Center, letterSpacing = 0.3.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
            }
        }
    }
}

@Composable
private fun ProfileCard(bio: BiophilicColors, uiState: SettingsUiState) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
            .clip(RoundedCornerShape(24.dp)).background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(24.dp)),
    ) {
        // Organic blob background (Canvas)
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.7f, 0f)
                quadraticBezierTo(size.width * 0.85f, size.height * 0.22f, size.width, size.height * 0.14f)
                lineTo(size.width, size.height * 0.57f)
                quadraticBezierTo(size.width * 0.85f, size.height * 0.64f, size.width * 0.7f, size.height * 0.5f)
                close()
            }
            drawPath(path, bio.mossPale2.copy(alpha = 0.3f))
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(bio.moss, bio.mossDeep))),
                contentAlignment = Alignment.Center,
            ) {
                Text(uiState.displayInitial, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(uiState.displayName, fontFamily = InstrumentSerifFamily, fontSize = 18.sp, color = bio.ink)
                Text("${uiState.tripCount} trips · ${uiState.momentCount} moments", fontSize = 12.sp, color = bio.ink3, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun PaletteRow(bio: BiophilicColors, current: Palette, onChange: (Palette) -> Unit) {
    val palettes = listOf(
        Palette.MOSS    to Triple(Color(0xFFD2EDD8), Color(0xFF5A9A64), Color(0xFF376E41)),
        Palette.FOREST  to Triple(Color(0xFFBEE8C9), Color(0xFF2E7A3B), Color(0xFF1B5227)),
        Palette.COASTAL to Triple(Color(0xFFBDD9E8), Color(0xFF4B94B5), Color(0xFF2D6D90)),
        Palette.DESERT  to Triple(Color(0xFFD8E5B8), Color(0xFF7D9148), Color(0xFF5C6B2C)),
    )
    val labels = mapOf(Palette.MOSS to "Soft moss", Palette.FOREST to "Forest", Palette.COASTAL to "Coastal", Palette.DESERT to "Desert")

    Column(Modifier.padding(16.dp)) {
        SettingsRowHeader(bio, Icons.Outlined.Palette, "Palette", "Soft moss, forest, coastal, desert")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (palette, swatches) ->
                        val selected = current == palette
                        Row(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) bio.mossPale else bio.bg2)
                                .border(1.5.dp, if (selected) bio.moss else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable { onChange(palette) }
                                .padding(10.dp, 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 3 overlapping circles
                            Box(Modifier.width(30.dp).height(14.dp)) {
                                listOf(swatches.first, swatches.second, swatches.third).forEachIndexed { i, c ->
                                    Box(Modifier.size(14.dp).offset(x = (i * 8).dp).clip(CircleShape).background(c).border(1.5.dp, bio.surface, CircleShape))
                                }
                            }
                            Text(labels[palette] ?: "", fontSize = 12.sp, color = bio.ink, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(bio: BiophilicColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(bio.surface).border(1.dp, bio.line2, RoundedCornerShape(20.dp)),
        content = content,
    )
}

@Composable
private fun SettingsSectionTitle(bio: BiophilicColors, title: String) {
    Text(title.uppercase(), fontSize = 11.sp, color = bio.ink3, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun SettingsDivider(bio: BiophilicColors) {
    HorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = bio.line2)
}

@Composable
private fun SettingsRowHeader(bio: BiophilicColors, icon: ImageVector, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(bio.mossPale), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = bio.mossDeep, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(title, fontSize = 14.sp, color = bio.ink, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = bio.ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LinkRow(bio: BiophilicColors, icon: ImageVector, title: String, value: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(bio.mossPale), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = bio.mossDeep, modifier = Modifier.size(18.dp))
        }
        Text(title, fontSize = 14.sp, color = bio.ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 12.sp, color = bio.ink3)
        Icon(Icons.Outlined.ChevronRight, null, tint = bio.ink3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToggleRow(bio: BiophilicColors, icon: ImageVector, title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(bio.mossPale), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = bio.mossDeep, modifier = Modifier.size(18.dp))
        }
        Text(title, fontSize = 14.sp, color = bio.ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = bio.mossDeep,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = bio.line,
                uncheckedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun SegmentedRow(bio: BiophilicColors, icon: ImageVector, title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        SettingsRowHeader(bio, icon, title)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bio.bg2).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            options.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                        .background(if (active) bio.surface else Color.Transparent)
                        .clickable { onSelect(i) }.padding(vertical = 7.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) bio.mossDeep else bio.ink2)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/cooldog/triplens/ui/settings/
git commit -m "feat: biophilic settings screen — profile card, palette selector, segmented controls (Task 27)"
```

---

## Self-Review

**Spec coverage:**
- ✅ Soft moss & cream palette (BiophilicColors, Task 20)
- ✅ Multi-palette support (MOSS / FOREST / COASTAL / DESERT, Task 20)
- ✅ Dark mode (darkBiophilicColors, Task 20)
- ✅ Instrument Serif display font (Typography.kt, Task 20)
- ✅ Bottom nav pill indicator + recording pulse (Task 21)
- ✅ Trip list editorial header + organic blob (Task 22)
- ✅ Trip list stat pills (Task 22)
- ✅ Trip card mini-map Canvas thumbnail (Task 22)
- ✅ Recording idle map-forward with GPS card (Task 23)
- ✅ MiniOrb breathing animation (Task 23)
- ✅ Recording active progress ring with moment petals (Task 24)
- ✅ Capture bar (Text/Voice/Photo tonal buttons) (Task 24)
- ✅ Moment timeline (reversed chronological) (Task 24)
- ✅ Bottom dock (pause + stop + recenter) (Task 24)
- ✅ Session stats ribbon (Task 25)
- ✅ Activity strip (transport mode bar) (Task 25)
- ✅ Connected vertical timeline with audio waveform + photo thumbnails (Task 25)
- ✅ Trip detail screen consistent header + session cards (Task 26)
- ✅ Settings profile card + palette selector + segmented controls (Task 27)

**Gaps to watch:**
- `LatLng` / `LatLngBounds` types in `BiophilicMiniMap` must match the existing model (`TrackPoint.latitude/longitude` doubles). Check `TripGroupItem` data class — may need a `trajectoryPoints: List<Pair<Double,Double>>` field added to the aggregated view model state.
- `TransportSegmentDisplay` — needs a display-layer DTO added to `SessionReviewViewModel.UiState`. Confirm it wraps existing `Segment` from `SegmentSmoother`.
- `TimelineEvent` sealed class — confirm it covers `TextNote`, `VoiceNote`, `Photo`, `Pause`, `Start` variants. Add `Start` type if missing.
- Font cert array in `font_certs.xml` — the cert strings above are placeholders; the actual GMS cert file should be copied from the [Android documentation](https://developer.android.com/develop/ui/compose/text/fonts#downloadable-fonts) or the `gms-play-services-fonts-certs.xml` sample in the Google Fonts Compose guide.
- `Icons.Outlined.EnergySavingsLeaf` — verify this icon exists in the Material Icons Extended library. Fallback: `Icons.Outlined.Eco`.

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-20-biophilic-redesign.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks

**2. Inline Execution** — execute tasks in this session using executing-plans

Which approach?
