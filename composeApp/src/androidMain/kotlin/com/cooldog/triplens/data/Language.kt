package com.cooldog.triplens.data

/**
 * Per-app language preference (Task 16 — Settings screen).
 *
 * [SYSTEM] defers to the OS locale; [EN] forces English; [ZH_CN] forces Simplified Chinese.
 * Applied via [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales] so the change
 * takes effect immediately in-process on API 33+ and via the AppCompat compat path on older
 * versions (which recreates the top Activity to pick up the new locale resources).
 */
enum class Language(
    /**
     * BCP 47 language tag passed to [AppCompatDelegate.setApplicationLocales].
     * Null for [SYSTEM] → passes an empty [androidx.core.os.LocaleListCompat] so Android
     * falls back to the device locale.
     */
    val bcp47Tag: String?,
) {
    SYSTEM(null),
    EN("en"),
    ZH_CN("zh-CN"),
}
