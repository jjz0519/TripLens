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
