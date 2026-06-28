package com.meatsack.motivator.mobile.ui.theme

/**
 * The phone app's selectable visual identity. Persisted in DataStore and exposed to
 * composables via [LocalThemeChoice] so each screen can pick its theme-specific
 * signature (EKG pulse vs. kiss mark), data encoding (severity bar vs. hearts), and
 * copy voice. Phone-only — it does not sync to the watch.
 */
enum class ThemeChoice(val label: String) {
    VITALS("Vitals"),
    BUBBLEGUM("Bubblegum"),
}
