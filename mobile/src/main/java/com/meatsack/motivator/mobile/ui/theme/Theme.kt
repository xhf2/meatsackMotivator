package com.meatsack.motivator.mobile.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Theme 1 — Vitals Console. Dark, mono, ember.
private val VitalsColorScheme = darkColorScheme(
    primary = VitalsEmber,
    onPrimary = VitalsBase,
    secondary = VitalsSubtle,
    onSecondary = VitalsBase,
    background = VitalsBase,
    onBackground = VitalsOnDark,
    surface = VitalsPanel,
    onSurface = VitalsOnDark,
    surfaceVariant = VitalsPanel,
    onSurfaceVariant = VitalsSubtle,
    outline = VitalsLine,
    outlineVariant = VitalsLine,
    error = VitalsEmber,
    onError = VitalsBase,
)

// Theme 2 — Bubblegum. Light, pink, cutesy.
private val BubblegumColorScheme = lightColorScheme(
    primary = BubblegumPrimary,
    onPrimary = BubblegumSurface,
    secondary = BubblegumKiss,
    onSecondary = BubblegumSurface,
    background = BubblegumBase,
    onBackground = BubblegumInk,
    surface = BubblegumSurface,
    onSurface = BubblegumInk,
    surfaceVariant = BubblegumBase,
    onSurfaceVariant = BubblegumMuted,
    outline = BubblegumLine,
    outlineVariant = BubblegumLine,
    error = BubblegumKiss,
    onError = BubblegumSurface,
)

/** The active theme, readable inside any composable under [MeatsackTheme]. */
val LocalThemeChoice = staticCompositionLocalOf { ThemeChoice.VITALS }

@Composable
fun MeatsackTheme(
    choice: ThemeChoice = ThemeChoice.VITALS,
    content: @Composable () -> Unit,
) {
    val bubblegum = choice == ThemeChoice.BUBBLEGUM
    val colorScheme = if (bubblegum) BubblegumColorScheme else VitalsColorScheme
    val typography = if (bubblegum) BubblegumTypography else VitalsTypography

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val barColor = colorScheme.background.toArgb()
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            // Bubblegum is a light theme → dark system icons; Vitals is dark → light icons.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = bubblegum
                isAppearanceLightNavigationBars = bubblegum
            }
        }
    }

    CompositionLocalProvider(LocalThemeChoice provides choice) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
