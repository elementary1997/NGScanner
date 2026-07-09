package ru.ngscanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Фиксированная тёмная схема «cockpit». Динамические цвета Material You
 * намеренно не подключаются: приборная панель должна выглядеть одинаково на
 * любом телефоне, а не перекрашиваться под обои.
 */
private val CockpitScheme = darkColorScheme(
    primary = CockpitAccent,
    onPrimary = CockpitAccentInk,
    primaryContainer = CockpitAccentContainer,
    onPrimaryContainer = CockpitOnAccentContainer,
    secondary = CockpitAccent,
    onSecondary = CockpitAccentInk,
    secondaryContainer = CockpitAccentContainer,
    onSecondaryContainer = CockpitOnAccentContainer,
    tertiary = StatusWarn,
    onTertiary = StatusWarnInk,
    tertiaryContainer = StatusWarnContainer,
    onTertiaryContainer = StatusWarn,
    error = StatusCrit,
    onError = StatusCritInk,
    errorContainer = StatusCritContainer,
    onErrorContainer = StatusOnCritContainer,
    background = CockpitBg,
    onBackground = CockpitText,
    surface = CockpitSurface,
    onSurface = CockpitText,
    surfaceVariant = CockpitVariant,
    onSurfaceVariant = CockpitDim,
    surfaceContainerLowest = CockpitBg,
    surfaceContainerLow = CockpitCardLow,
    surfaceContainer = CockpitCard,
    surfaceContainerHigh = CockpitCardHigh,
    surfaceContainerHighest = CockpitCardHighest,
    outline = CockpitLine,
    outlineVariant = CockpitLine,
    scrim = Color(0xCC04060A),
)

@Composable
fun NgScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CockpitScheme, typography = Typography, content = content)
}
