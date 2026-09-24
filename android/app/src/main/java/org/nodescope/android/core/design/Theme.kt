package org.nodescope.android.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nodescope.android.core.storage.Appearance

val SignalBlue = Color(0xFF299EFF)
val HealthyGreen = Color(0xFF3DC77A)
val ActivityAmber = Color(0xFFFFA833)

@Composable
fun NodeScopeTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF81C3FF), onPrimary = Color(0xFF00294A), primaryContainer = Color(0xFF123956), onPrimaryContainer = Color(0xFFD5EAFF),
        secondary = Color(0xFFA6C7E6), secondaryContainer = Color(0xFF22384E), onSecondaryContainer = Color(0xFFD6E9FB),
        background = Color(0xFF0B1014), onBackground = Color(0xFFE6EDF5),
        surface = Color(0xFF131B21), onSurface = Color(0xFFE6EDF5), surfaceVariant = Color(0xFF27333C), onSurfaceVariant = Color(0xFFA4B3BE),
        surfaceContainer = Color(0xFF19242C), surfaceContainerLow = Color(0xFF131B21), surfaceContainerHigh = Color(0xFF22313B),
        surfaceContainerHighest = Color(0xFF2B3B47), surfaceContainerLowest = Color(0xFF0B1014),
        outline = Color(0xFF62758B), outlineVariant = Color(0xFF30404B), surfaceTint = SignalBlue,
    ) else lightColorScheme(
        primary = Color(0xFF0063A6), onPrimary = Color.White, primaryContainer = Color(0xFFD8EBFF), onPrimaryContainer = Color(0xFF003354),
        secondary = Color(0xFF456784), secondaryContainer = Color(0xFFE1EDFA), onSecondaryContainer = Color(0xFF213E58),
        background = Color(0xFFF5F7F9), onBackground = Color(0xFF14243A),
        surface = Color.White, onSurface = Color(0xFF14243A), surfaceVariant = Color(0xFFE5EDF6), onSurfaceVariant = Color(0xFF52677E),
        surfaceContainer = Color(0xFFEDF3FA), surfaceContainerLow = Color(0xFFF8FAFE), surfaceContainerHigh = Color(0xFFE5EDF6),
        surfaceContainerHighest = Color(0xFFDFE8F3), surfaceContainerLowest = Color.White,
        outline = Color(0xFF71859A), outlineVariant = Color(0xFFD6E1ED), surfaceTint = SignalBlue,
    )
    val typography = Typography().let { base -> base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1.2).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    ) }
    MaterialTheme(colorScheme = colors, typography = typography, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)
    ), content = content)
}
