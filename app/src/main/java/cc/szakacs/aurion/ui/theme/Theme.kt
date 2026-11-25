package cc.szakacs.aurion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AurionColors {
    val Black = Color(0xFF05070D)
    val Surface = Color(0xFF0D1220)
    val SurfaceHigh = Color(0xFF151C2E)
    val Glass = Color(0x2EFFFFFF)
    val GlassBorder = Color(0x33FFFFFF)
    val Blue = Color(0xFF2F7BFF)
    val Cyan = Color(0xFF4FD8FF)
    val Indigo = Color(0xFF5B5BF0)
    val Silver = Color(0xFFC9D3E0)
    val TextHigh = Color(0xFFF2F5FA)
    val TextMid = Color(0xFF9AA6BA)
    val TextLow = Color(0xFF5E6A80)
    // bewusst ohne Rot – Warnungen in Bernstein
    val Amber = Color(0xFFFFB547)
}

private val scheme = darkColorScheme(
    primary = AurionColors.Blue,
    onPrimary = Color.White,
    secondary = AurionColors.Cyan,
    tertiary = AurionColors.Indigo,
    background = AurionColors.Black,
    onBackground = AurionColors.TextHigh,
    surface = AurionColors.Surface,
    onSurface = AurionColors.TextHigh,
    surfaceVariant = AurionColors.SurfaceHigh,
    onSurfaceVariant = AurionColors.TextMid,
    surfaceContainer = AurionColors.Surface,
    surfaceContainerHigh = AurionColors.SurfaceHigh,
    surfaceContainerHighest = AurionColors.SurfaceHigh,
    outline = AurionColors.GlassBorder,
    error = AurionColors.Amber,
    onError = Color.Black,
)

private val typography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 32.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun AurionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
