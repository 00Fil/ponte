package it.ponte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Stile "liquid" dell'intera app: uno sfondo azzurro luminoso con aloni morbidi e superfici di vetro sopra
 * (velo traslucido, riflesso in alto, bordo speculare e un alone interno), le stesse regole della barra delle schede.
 */
object Liquid {
    /** Colore di partenza dello sfondo: serve anche alle dissolvenze, per non lasciare bande diverse. */
    fun top(dark: Boolean) = if (dark) Color(0xFF0B1226) else Color(0xFFF1F6FF)
    fun bottom(dark: Boolean) = if (dark) Color(0xFF0C1130) else Color(0xFFE6EBFF)

    fun rim(dark: Boolean, strong: Boolean = false): Brush {
        val a = if (strong) 1f else 0.85f
        return Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.55f * a else a),
            0.35f to Color.White.copy(alpha = if (dark) 0.08f else 0.22f),
            0.65f to Color.White.copy(alpha = if (dark) 0.05f else 0.16f),
            1f to Color.White.copy(alpha = if (dark) 0.40f * a else 0.75f * a),
        )
    }

    fun glow(dark: Boolean): Brush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (dark) 0.08f else 0.30f), Color.Transparent, Color.White.copy(alpha = if (dark) 0.04f else 0.14f)),
    )
}

/** Sfondo dell'app: sfumatura chiara con tre aloni (cielo, reale, cielo) dietro il vetro. */
fun Modifier.liquidCanvas(dark: Boolean): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Liquid.top(dark), Liquid.top(dark), Color(if (dark) 0xFF0A1022 else 0xFFEAF1FF), Liquid.bottom(dark)))) 
    val w = size.width
    val h = size.height
    fun blob(color: Color, cx: Float, cy: Float, r: Float) =
        drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
    blob(Brand.sky.copy(alpha = if (dark) 0.22f else 0.45f), w * 0.10f, h * 0.30f, w * 0.9f)
    blob(Brand.mid.copy(alpha = if (dark) 0.26f else 0.28f), w * 1.0f, h * 0.60f, w * 0.85f)
    blob(Brand.sky.copy(alpha = if (dark) 0.18f else 0.36f), w * 0.15f, h * 0.98f, w * 0.8f)
}

/** Lastra di vetro: velo, riflesso dall'alto, alone interno e bordo speculare. */
fun Modifier.glassPanel(shape: Shape, dark: Boolean, veil: Float = 1f, elevation: Boolean = true): Modifier =
    this.then(if (elevation) Modifier.shadow(10.dp, shape, ambientColor = Color(0x1A1A2A66), spotColor = Color(0x201A2A66)) else Modifier)
        .clip(shape)
        .background(Color.White.copy(alpha = (if (dark) 0.07f else 0.52f) * veil))
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.10f else 0.46f),
                0.45f to Color.Transparent,
                1f to Color.White.copy(alpha = if (dark) 0.03f else 0.10f),
            ),
        )
        .border(3.dp, Liquid.glow(dark), shape)
        .border(1.dp, Liquid.rim(dark), shape)

@Composable
fun isDark() = isSystemInDarkTheme()
