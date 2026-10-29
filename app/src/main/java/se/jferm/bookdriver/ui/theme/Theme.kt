package se.jferm.bookdriver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.R

/** "Night Drive": near-black ink, one amber accent, condensed Archivo display over Inter. */
object BD {
    val Ink = Color(0xFF0E1116)
    val Deep = Color(0xFF07090C)
    val Surface = Color(0xFF171B22)
    val Raised = Color(0xFF1F252E)
    val Line = Color(0xFF2A313C)
    val LineStrong = Color(0xFF3A4250)
    val Text = Color(0xFFEEF1F5)
    val TextSoft = Color(0xFFC9D0D9)
    val Muted = Color(0xFF9AA4B2)
    val Dim = Color(0xFF8B95A3)
    val Faint = Color(0xFF6B7584)
    val Amber = Color(0xFFF5A524)
    val OnAmber = Color(0xFF1A1200)
    val AmberTint = Color(0xFF1E1A12)
    val AmberLine = Color(0xFF3A3220)
    val Cream = Color(0xFFFFE7B8)
    val Danger = Color(0xFFF2845C)
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Headings: Archivo at 75% width. */
val Condensed = FontFamily(archivo(700, 75f), archivo(800, 75f), archivo(900, 75f))

/** Covers and the wordmark: Archivo squeezed to 68%. */
val Compressed = FontFamily(archivo(800, 68f), archivo(900, 68f))

/** Book titles: Archivo at 85% width. */
val Titling = FontFamily(archivo(700, 85f), archivo(800, 85f))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

object Type {
    fun display(size: TextUnit, weight: Int = 800) =
        TextStyle(fontFamily = Condensed, fontWeight = FontWeight(weight), fontSize = size, lineHeight = size * 1.0f)

    fun title(size: TextUnit, weight: Int = 800) =
        TextStyle(fontFamily = Titling, fontWeight = FontWeight(weight), fontSize = size, lineHeight = size * 1.1f)

    val overline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.4.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun BookDriverTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = BD.Amber,
        onPrimary = BD.OnAmber,
        secondary = BD.Amber,
        onSecondary = BD.OnAmber,
        background = BD.Ink,
        onBackground = BD.Text,
        surface = BD.Surface,
        onSurface = BD.Text,
        surfaceVariant = BD.Raised,
        onSurfaceVariant = BD.Muted,
        surfaceContainer = BD.Surface,
        surfaceContainerHigh = BD.Raised,
        surfaceContainerLow = BD.Surface,
        outline = BD.Line,
        outlineVariant = BD.Line,
        error = BD.Danger,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.run {
            copy(
                bodyLarge = Type.body, bodyMedium = Type.small, bodySmall = Type.caption,
                labelLarge = Type.bodyStrong, titleMedium = Type.bodyStrong,
            )
        },
        content = content,
    )
}
