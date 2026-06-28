package com.meatsack.motivator.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Direction C — "Vitals Console". Dark, mono-forward, single ember accent.
// These are the canonical tokens; the design mock lives in docs/design/phone-app-directions.html.
val VitalsBase = Color(0xFF0A0B0D) // app background — near-black
val VitalsPanel = Color(0xFF14161A) // card / readout-panel surface
val VitalsLine = Color(0xFF1C1F24) // hairline dividers, borders, slider tracks
val VitalsEmber = Color(0xFFFF2D2D) // the one accent: actions, level, on-state, pulse
val VitalsOnDark = Color(0xFFE6E8EA) // primary text
val VitalsSubtle = Color(0xFFAEB3BA) // secondary/muted text: votes, values, labels, hints, off-state

// Theme 2 — "Bubblegum". Light, cutesy, pink — the sweet wrapper around savage insults.
val BubblegumBase = Color(0xFFFFE3F1) // app background — soft bubblegum
val BubblegumSurface = Color(0xFFFFFFFF) // cards
val BubblegumPrimary = Color(0xFFFF4FA3) // hot-pink primary: actions, fills, on-state
val BubblegumKiss = Color(0xFFD6336C) // deep lipstick accent: hero, hearts, emphasis
val BubblegumInk = Color(0xFF5A2A43) // primary text — dark plum (readable on pink)
val BubblegumMuted = Color(0xFFB06A8C) // secondary/muted text
val BubblegumLine = Color(0xFFFFC2DE) // borders, slider tracks, dividers
