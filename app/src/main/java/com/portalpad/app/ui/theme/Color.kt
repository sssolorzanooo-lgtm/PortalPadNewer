package com.portalpad.app.ui.theme

import androidx.compose.ui.graphics.Color

// PortalPad's palette: warm-dark base with vibrant violet primary and amber accent.
// Distinct from the reference implementation (which is blue-dark with teal primary). Names keep the
// "Ab" prefix purely as a stable internal identifier — no relation to color hue.

val AbBackground = Color(0xFF0E0E12)        // deep obsidian charcoal
val AbSurface = Color(0xFF16161B)           // surface (cards, top bar)
val AbSurfaceVariant = Color(0xFF1E1E25)    // alt surface
val AbSurfaceElevated = Color(0xFF282832)   // elevated tiles, buttons

val AbPrimary = Color(0xFFDC2626)           // deep crimson red
val AbPrimaryBright = Color(0xFFDC2626)     // crimson highlight matching primary buttons
val AbPrimaryDim = Color(0xFF7F1D1D)        // darker crimson border / subtle container

val AbAccent = Color(0xFFFFB547)            // warm amber — complementary
val AbAccentSoft = Color(0xFFFFCB7A)        // soft amber

val AbOnSurface = Color(0xFFFFFFFF)         // crisp white primary text
val AbOnSurfaceMuted = Color(0xFFB4B4C0)    // neutral readable secondary text
val AbOnSurfaceDim = Color(0xFF787886)      // tertiary text / inactive

val AbGridLine = Color(0xFF24242E)          // subtle grid lines
val AbDanger = Color(0xFFFF5A5A)            // alert red
val AbMicTint = Color(0xFF3A1C21)           // muted crimson — mic button at rest
val AbClearTint = Color(0xFF3D2632)         // muted red — clear button at rest (destructive)
val AbClearIcon = Color(0xFFE89A94)         // soft red — trash glyph
val AbWarning = Color(0xFFE6B85C)           // soft amber — caveats / "heads up" notes
val AbSuccess = Color(0xFF5EE89F)           // cool mint green
val AbTeal = Color(0xFF34C79E)              // cool teal — Quick Wheel activity slots (deeper entry point)
val AbTabActive = AbPrimary
val AbTabInactive = AbOnSurfaceDim

// Phone App Palette: High-contrast Crimson Red accents on Deep Charcoal Dark.
// Applied strictly to phone UI surfaces; external display overlays retain neutral Ab* chrome.
val PhoneBackground = Color(0xFF0E0E12)        // deep obsidian charcoal
val PhoneSurface = Color(0xFF16161B)           // primary card / top bar / sheet surface
val PhoneSurfaceVariant = Color(0xFF1E1E25)    // secondary container / input well
val PhoneSurfaceElevated = Color(0xFF282832)   // elevated tiles, paddles, buttons

val PhonePrimary = Color(0xFFDC2626)           // high-contrast crimson red
val PhonePrimaryBright = Color(0xFFF87171)     // legible bright crimson-coral for dark backgrounds
val PhonePrimaryDim = Color(0xFF7F1D1D)        // deep crimson container / subtle borders

val PhoneAccent = Color(0xFFEF4444)            // vivid crimson accent
val PhoneAccentSoft = Color(0xFFFCA5A5)        // soft light crimson highlight

val PhoneOnSurface = Color(0xFFF4F4F6)         // crisp off-white primary text
val PhoneOnSurfaceMuted = Color(0xFFB4B4C0)    // readable secondary text
val PhoneOnSurfaceDim = Color(0xFF787886)      // tertiary text / inactive controls

val PhoneGridLine = Color(0xFF24242E)          // subtle trackpad grid lines
val PhoneDanger = Color(0xFFFF5A5A)            // alert / destructive crimson
val PhoneMicTint = Color(0xFF3A1C21)           // muted crimson mic button at rest
val PhoneClearTint = Color(0xFF3F1D24)         // muted crimson clear button at rest
val PhoneClearIcon = Color(0xFFFCA5A5)         // soft red glyph
val PhoneWarning = Color(0xFFF59E0B)           // crisp amber warning
val PhoneSuccess = Color(0xFF4ADE80)           // bright emerald green
val PhoneTeal = Color(0xFF38BDF8)              // sky accent for Quick Wheel activity slots
val PhoneNavPill = Color(0xFF641B22)           // deep crimson nav pill (high contrast with PhoneOnSurface)
val PhoneTabActive = PhonePrimary
val PhoneTabInactive = PhoneOnSurfaceDim
