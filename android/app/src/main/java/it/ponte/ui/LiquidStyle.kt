package it.ponte.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Parti interattive "liquid": stesso linguaggio della barra delle schede (velo, riflesso dall'alto, alone interno, bordo speculare)
 * ma disegnate in un solo passaggio con gradienti già pronti, senza ombre, senza livelli grafici in più e senza copiare lo sfondo:
 * così costano quasi niente anche in una lista piena di pulsanti. La rifrazione vera (shader) resta solo sulla barra.
 *
 * [press] è letto solo in fase di disegno (0..1): il pulsante si illumina senza ricomporre nulla.
 */
fun Modifier.glassControl(
    dark: Boolean,
    corner: Dp? = null,
    fill: Brush? = null,
    tint: Color = Color.Unspecified,
    press: () -> Float = { 0f },
): Modifier = this.drawWithCache {
    val r = corner?.toPx() ?: (size.minDimension / 2f)
    val cr = CornerRadius(r)
    val hair = 1.dp.toPx()
    val white = Color.White
    val base = when {
        tint != Color.Unspecified -> tint
        dark -> white.copy(alpha = 0.10f)
        else -> Color(0xFF20306A).copy(alpha = 0.07f)
    }
    val sheen = Brush.verticalGradient(
        0f to white.copy(alpha = if (dark) 0.18f else 0.75f),
        0.5f to Color.Transparent,
        1f to white.copy(alpha = if (dark) 0.05f else 0.28f),
        endY = size.height,
    )
    val diag = Offset(size.width, size.height)
    val glow = Brush.linearGradient(listOf(white.copy(alpha = if (dark) 0.10f else 0.40f), Color.Transparent, white.copy(alpha = if (dark) 0.05f else 0.20f)), Offset.Zero, diag)
    val rim = Brush.linearGradient(
        0f to white.copy(alpha = if (dark) 0.70f else 1f),
        0.35f to white.copy(alpha = if (dark) 0.10f else 0.26f),
        0.65f to white.copy(alpha = if (dark) 0.06f else 0.18f),
        1f to white.copy(alpha = if (dark) 0.55f else 0.95f),
        start = Offset.Zero, end = diag,
    )
    val outline = if (dark) white.copy(alpha = 0.12f) else Color(0xFF20306A).copy(alpha = 0.14f)
    val inner = 3.dp.toPx()
    onDrawBehind {
        if (fill != null) drawRoundRect(fill, cornerRadius = cr) else drawRoundRect(base, cornerRadius = cr)
        drawRoundRect(sheen, cornerRadius = cr)
        val p = press()
        if (p > 0.01f) drawRoundRect(white.copy(alpha = (if (dark) 0.16f else 0.30f) * p), cornerRadius = cr)
        // alone interno e bordo di luce; il filo scuro, fuori, dà contorno sul chiaro
        drawRoundRect(glow, Offset(inner / 2, inner / 2), Size(size.width - inner, size.height - inner), CornerRadius((r - inner / 2).coerceAtLeast(0f)), style = Stroke(inner))
        drawRoundRect(outline, Offset(hair / 2, hair / 2), Size(size.width - hair, size.height - hair), CornerRadius((r - hair / 2).coerceAtLeast(0f)), style = Stroke(hair))
        drawRoundRect(rim, Offset(hair, hair), Size(size.width - 2 * hair, size.height - 2 * hair), CornerRadius((r - hair).coerceAtLeast(0f)), style = Stroke(hair * 1.2f))
    }
}

@Composable
fun isDark() = isSystemInDarkTheme()
