package com.meatsack.motivator.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.runtime.Composable

private val MeatsackColors = Colors(
    primary = Color(0xFFFF3B30),      // Angry red
    onPrimary = Color.White,
    secondary = Color(0xFFFF9500),    // Warning orange
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    error = Color(0xFFFF453A),
    onError = Color.White,
)

@Composable
fun MeatsackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MeatsackColors,
        content = content
    )
}

object MeatsackTypography {
    val insultText = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        lineHeight = 20.sp,
    )
    val statsText = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        color = Color(0xFF8E8E93),
        lineHeight = 14.sp,
    )
    val brandText = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Light,
        color = Color(0xFF3A3A3C),
    )
}
