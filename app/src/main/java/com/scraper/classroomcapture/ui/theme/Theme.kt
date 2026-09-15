package com.scraper.classroomcapture.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// scrappy tokens (ui.readme §3): warm ivory canvas, charcoal text, ochre primary.
// Light mode only for the initial field release; Android dynamic color is disabled
// so the ochre identity stays consistent. No untested automatic dark inversion.
private val ScrappyColors =
    lightColorScheme(
        primary = Color(0xFFC69A35),
        onPrimary = Color(0xFF28251F),
        primaryContainer = Color(0xFFF1E9D6),
        onPrimaryContainer = Color(0xFF28251F),
        secondary = Color(0xFF81755E),
        onSecondary = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF28251F),
        background = Color(0xFFFAF8F1),
        onBackground = Color(0xFF28251F),
        surfaceVariant = Color(0xFFF1E9D6),
        onSurfaceVariant = Color(0xFF655E50),
        outline = Color(0xFFDDD7C9),
        outlineVariant = Color(0xFF81755E),
        error = Color(0xFFA3342D),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF8E8E4),
        onErrorContainer = Color(0xFFA3342D),
    )

@Composable
fun ScraperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScrappyColors,
        typography = ScrappyTypography,
        content = content,
    )
}
