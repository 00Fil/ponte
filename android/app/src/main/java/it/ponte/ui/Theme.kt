package it.ponte.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.ponte.R

/** Ruoli di colore di Canone. Stessi nomi nei due temi, valori propri. Niente esadecimali fuori da questo file. */
@Immutable
class CanoneColors(
    val bgCanvas: Color, val bgSurface: Color, val bgSunken: Color, val bgInverse: Color, val bgScrim: Color,
    val labelPrimary: Color, val labelSecondary: Color, val labelTertiary: Color, val labelDisabled: Color, val labelInverse: Color,
    val lineSeparator: Color, val lineStrong: Color, val fillControl: Color, val fillSelected: Color,
    val accent: Color, val accentPressed: Color, val accentSubtle: Color, val accentOnSubtle: Color, val accentOnAccent: Color,
    val positive: Color, val positiveSubtle: Color, val positiveOnSubtle: Color,
    val caution: Color, val cautionSubtle: Color, val cautionOnSubtle: Color,
    val critical: Color, val criticalSubtle: Color, val criticalOnSubtle: Color, val criticalOnCritical: Color,
)

private val Neutral0 = Color(0xFFFFFFFF)
private val Neutral50 = Color(0xFFF4F4F2)
private val Neutral100 = Color(0xFFEDEDEA)
private val Neutral200 = Color(0xFFE2E2DE)
private val Neutral300 = Color(0xFFCACAC5)
private val Neutral400 = Color(0xFFA6A6A1)
private val Neutral500 = Color(0xFF85857F)
private val Neutral600 = Color(0xFF6B6B66)
private val Neutral700 = Color(0xFF4A4A47)
private val Neutral750 = Color(0xFF3A3A3C)
private val Neutral800 = Color(0xFF2E2E30)
private val Neutral850 = Color(0xFF242426)
private val Neutral900 = Color(0xFF1A1A1B)
private val Neutral925 = Color(0xFF121212)
private val Neutral950 = Color(0xFF0E0E0F)
private val Neutral1000 = Color(0xFFF2F2F0)
// Accento di Ponte: gli stessi blu del gradiente ASCII (#1e3fd8, #38bdf8, #a5d8ff, #f6f8ff).
private val Blue50 = Color(0xFFEAF0FF)
private val Blue300 = Color(0xFFA9C8FF)
private val Blue400 = Color(0xFF4D8DFF)
private val Blue500 = Color(0xFF2E4BFF)
private val Blue600 = Color(0xFF1D3BD1)
private val Blue900 = Color(0xFF12204A)

/** Il gradiente di Ponte, per i pochi punti di colore: pulsante principale, indicatori, spunte. */
object Brand {
    val royal = Color(0xFF2E4BFF)
    val sky = Color(0xFF4D9BFF)
    val ice = Color(0xFFB8C2FF)
    val gradient = androidx.compose.ui.graphics.Brush.linearGradient(listOf(royal, sky))
}
private val Green50 = Color(0xFFE5F2EA)
private val Green300 = Color(0xFF4CC38A)
private val Green600 = Color(0xFF1F7A4D)
private val Green700 = Color(0xFF17603C)
private val Green900 = Color(0xFF12301F)
private val Amber50 = Color(0xFFFBF0E0)
private val Amber300 = Color(0xFFF0A54A)
private val Amber600 = Color(0xFFA35B00)
private val Amber700 = Color(0xFF7F4600)
private val Amber900 = Color(0xFF3A2508)
private val Red50 = Color(0xFFFBEAEA)
private val Red300 = Color(0xFFFF7A73)
private val Red600 = Color(0xFFC3262B)
private val Red700 = Color(0xFFA11F24)
private val Red900 = Color(0xFF3D1517)

val LightColors = CanoneColors(
    bgCanvas = Neutral50, bgSurface = Neutral0, bgSunken = Neutral100, bgInverse = Neutral925, bgScrim = Color(0x66121212),
    labelPrimary = Neutral925, labelSecondary = Neutral700, labelTertiary = Neutral600, labelDisabled = Neutral500, labelInverse = Neutral0,
    lineSeparator = Neutral200, lineStrong = Neutral300, fillControl = Neutral300, fillSelected = Neutral925,
    accent = Blue500, accentPressed = Blue600, accentSubtle = Blue50, accentOnSubtle = Blue600, accentOnAccent = Neutral0,
    positive = Green600, positiveSubtle = Green50, positiveOnSubtle = Green700,
    caution = Amber600, cautionSubtle = Amber50, cautionOnSubtle = Amber700,
    critical = Red600, criticalSubtle = Red50, criticalOnSubtle = Red700, criticalOnCritical = Neutral0,
)

val DarkColors = CanoneColors(
    bgCanvas = Neutral950, bgSurface = Neutral900, bgSunken = Neutral850, bgInverse = Neutral1000, bgScrim = Color(0x99000000),
    labelPrimary = Neutral1000, labelSecondary = Neutral400, labelTertiary = Neutral500, labelDisabled = Neutral600, labelInverse = Neutral925,
    lineSeparator = Neutral800, lineStrong = Neutral750, fillControl = Neutral750, fillSelected = Neutral1000,
    accent = Blue400, accentPressed = Blue300, accentSubtle = Blue900, accentOnSubtle = Blue300, accentOnAccent = Neutral0,
    positive = Green300, positiveSubtle = Green900, positiveOnSubtle = Green300,
    caution = Amber300, cautionSubtle = Amber900, cautionOnSubtle = Amber300,
    critical = Red300, criticalSubtle = Red900, criticalOnSubtle = Red300, criticalOnCritical = Neutral950,
)

val Geist = FontFamily(Font(R.font.geist_regular, FontWeight.Normal), Font(R.font.geist_medium, FontWeight.Medium))
val GeistMono = FontFamily(Font(R.font.geist_mono_regular, FontWeight.Normal))

/** Scala 1,25 con interlinea sulla griglia di 4. I `sp` seguono le preferenze di sistema. */
object CanoneType {
    val title1 = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 39.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em)
    val title2 = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 31.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em)
    val title3 = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 25.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em)
    val callout = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 28.sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium = body.copy(fontWeight = FontWeight.Medium)
    val footnote = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.01.em)
    val figure = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
}

/** Griglia da 4: space[1]..space[10] = 4, 8, 12, 16, 24, 32, 48, 64, 96, 128. */
object Space {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp; val s5 = 24.dp; val s6 = 32.dp; val s7 = 48.dp
}

/** Raggi: xs 6, sm 10, md 14, lg 20, xl 28, 2xl 36, pill. */
object Radius {
    val xs = 6.dp; val sm = 10.dp; val md = 14.dp; val lg = 20.dp; val xl = 28.dp
}

private val LocalCanone = staticCompositionLocalOf { LightColors }

object Canone {
    val colors: CanoneColors
        @Composable @ReadOnlyComposable get() = LocalCanone.current
}

@Composable
fun PonteTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkColors else LightColors
    val scheme = (if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()).copy(
        background = c.bgCanvas, onBackground = c.labelPrimary,
        surface = c.bgSurface, onSurface = c.labelPrimary, surfaceVariant = c.bgSunken, onSurfaceVariant = c.labelSecondary,
        primary = c.bgInverse, onPrimary = c.labelInverse, outline = c.lineStrong, outlineVariant = c.lineSeparator,
        error = c.critical, onError = c.criticalOnCritical,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalCanone provides c, LocalContentColor provides c.labelPrimary, content = content)
    }
}
