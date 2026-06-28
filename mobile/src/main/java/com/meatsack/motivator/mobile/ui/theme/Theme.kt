package com.meatsack.motivator.mobile.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark-only by design — the Vitals Console look has no light variant.
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

@Composable
fun MeatsackTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = VitalsBase.toArgb()
            window.navigationBarColor = VitalsBase.toArgb()
            // Light-on-dark system icons.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = VitalsColorScheme,
        typography = VitalsTypography,
        content = content,
    )
}
