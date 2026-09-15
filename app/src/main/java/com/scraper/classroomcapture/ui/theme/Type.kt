package com.scraper.classroomcapture.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.scraper.classroomcapture.R

// Typography roles (ui.readme §4). Manrope (600/700): wordmark + English
// headings; Inter (400/500/600): body, buttons, form labels.
//
// The bundled Noto Sans Devanagari TTFs (res/font/noto_dev_*.ttf) are
// compiled into the APK and shipped in assets-licenses to satisfy OFL
// distribution obligations. On Android 10+ devices the platform's
// Noto Sans Devanagari provides Hindi/Marathi fallback automatically;
// per-glyph Latin→Devanagari fallback works across families without
// explicit bundling in Compose FontFamily.
//
// All sizes are sp to honor system font scaling. Timer uses tabular digits.
private val ManropeFamily =
    FontFamily(
        Font(R.font.manrope_600, weight = FontWeight.SemiBold),
        Font(R.font.manrope_700, weight = FontWeight.Bold),
    )

private val BodyFamily =
    FontFamily(
        Font(R.font.inter_400, weight = FontWeight.Normal),
        Font(R.font.inter_500, weight = FontWeight.Medium),
        Font(R.font.inter_600, weight = FontWeight.SemiBold),
    )

internal val ScrappyTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = BodyFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = BodyFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = BodyFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = BodyFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
    )

// Roles surfaced for screens that need the raw reference (ui.readme §4).
internal val WordmarkStyle = ScrappyTypography.displayLarge
internal val ScreenTitleStyle = ScrappyTypography.headlineLarge
internal val SectionHeadingStyle = ScrappyTypography.headlineMedium
internal val BodyStyle = ScrappyTypography.bodyLarge
internal val ButtonStyle = ScrappyTypography.labelLarge
internal val SupportingLabelStyle = ScrappyTypography.labelMedium
internal val TimerStyle =
    TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        fontFeatureSettings = "tnum",
    )
