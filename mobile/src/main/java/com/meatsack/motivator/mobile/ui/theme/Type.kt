package com.meatsack.motivator.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Vitals Console type system. Two roles, both from Android's built-in families so we
// ship no font files (offline-clean):
//   Mono  = instrument readout — headers, labels, tags, buttons, nav.
//   Sans  = the insult text itself — proportional, because long insults in pure mono
//           get hard to read. This split is deliberate (see docs/design notes).
private val Mono = FontFamily.Monospace
private val Sans = FontFamily.SansSerif

val VitalsTypography = Typography(
    // Screen titles / section heads (mono, wide tracking — reads like a console label).
    headlineMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = 2.5.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 2.sp,
    ),
    // Settings control labels.
    titleMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.2.sp,
    ),
    // Insult body — the one proportional style.
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Hints, status lines, vote counts.
    bodySmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        lineHeight = 16.sp,
    ),
    // Readout tags (LVL n, trigger type).
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
    ),
    // Button / action text.
    labelLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
    ),
)
