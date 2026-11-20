package com.portalpad.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PhoneColorScheme = darkColorScheme(
    primary = PhonePrimary,
    onPrimary = PhoneOnSurface,
    primaryContainer = PhonePrimaryDim,
    onPrimaryContainer = PhoneOnSurface,
    secondary = PhoneAccent,
    onSecondary = PhoneOnSurface,
    background = PhoneBackground,
    onBackground = PhoneOnSurface,
    surface = PhoneSurface,
    onSurface = PhoneOnSurface,
    surfaceVariant = PhoneSurfaceVariant,
    onSurfaceVariant = PhoneOnSurfaceMuted,
    error = PhoneDanger,
)

private val OverlayColorScheme = darkColorScheme(
    primary = AbPrimary,
    onPrimary = AbOnSurface,
    primaryContainer = AbPrimaryDim,
    onPrimaryContainer = AbOnSurface,
    secondary = AbAccent,
    onSecondary = AbOnSurface,
    background = AbBackground,
    onBackground = AbOnSurface,
    surface = AbSurface,
    onSurface = AbOnSurface,
    surfaceVariant = AbSurfaceVariant,
    onSurfaceVariant = AbOnSurfaceMuted,
    error = AbDanger,
)

@Composable
fun PortalPadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    phoneTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (phoneTheme) PhoneColorScheme else OverlayColorScheme,
        typography = PortalPadTypography,
        content = content,
    )
}
