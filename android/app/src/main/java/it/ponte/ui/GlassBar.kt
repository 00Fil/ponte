package it.ponte.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spazio che il contenuto deve lasciare in basso perché la barra di vetro non copra l'ultima riga. */
val LocalBarInset = compositionLocalOf { 0.dp }

/**
 * Barra delle schede in stile Liquid Glass (iOS 26/27): capsula fluttuante, sempre visibile, che sfoca e satura ciò che le scorre sotto.
 *  - lo sfondo è una copia sfocata del contenuto, registrata in [layer] (Android 12+; prima, un vetro opaco)
 *  - bordo definito con luce speculare in alto, niente ombre pesanti
 *  - la scheda attiva è una lente di vetro più chiara che scivola seguendo lo swipe e si ingrandisce quando la trascini
 *  - si può trascinare il dito sulla barra per passare da una scheda all'altra
 */
@Composable
fun GlassTabBar(
    layer: GraphicsLayer,
    labels: List<String>,
    glyphs: List<Int>,
    pos: () -> Float,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Canone.colors
    val dark = isSystemInDarkTheme()
    val shape = CircleShape
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 380f), label = "lente")
    val blurPx = with(density) { 18.dp.toPx() }
    val effect = remember(blurPx) {
        if (Build.VERSION.SDK_INT >= 31) {
            val sat = android.graphics.ColorMatrix().apply { setSaturation(1.6f) }
            android.graphics.RenderEffect.createColorFilterEffect(
                android.graphics.ColorMatrixColorFilter(sat),
                android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP),
            ).asComposeRenderEffect()
        } else null
    }
    val white = Color.White
    val n = labels.size
    val currentNow by rememberUpdatedState(current)
    val select by rememberUpdatedState(onSelect)

    Box(
        modifier.onGloballyPositioned { origin = it.positionInParent() }
            .widthIn(max = 440.dp).fillMaxWidth().height(66.dp)
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f)),
    ) {
        // 1) il contenuto sotto, sfocato e più saturo
        Box(
            Modifier.fillMaxSize().clip(shape)
                .graphicsLayer { renderEffect = effect }
                .drawBehind { translate(-origin.x, -origin.y) { drawLayer(layer) } },
        )
        // 2) velo di vetro: più chiaro in tema chiaro, scuro in tema scuro; senza sfocatura è quasi opaco
        val veil = if (effect != null) (if (dark) Color(0xFF12161D).copy(alpha = 0.42f) else white.copy(alpha = 0.34f))
        else (if (dark) Color(0xFF181C24).copy(alpha = 0.96f) else Color(0xFFF4F6FB).copy(alpha = 0.96f))
        Box(Modifier.fillMaxSize().clip(shape).background(veil))
        // 3) riflesso: luce che scende dall'alto
        Box(
            Modifier.fillMaxSize().clip(shape).background(
                Brush.verticalGradient(
                    0f to white.copy(alpha = if (dark) 0.14f else 0.38f),
                    0.5f to Color.Transparent,
                    1f to white.copy(alpha = if (dark) 0.04f else 0.10f),
                ),
            ),
        )
        // 4) bordo definito con riflesso speculare (in iOS 27 il bordo sostituisce le ombre)
        Box(
            Modifier.fillMaxSize().border(
                1.dp,
                Brush.verticalGradient(
                    0f to white.copy(alpha = if (dark) 0.50f else 0.95f),
                    0.45f to white.copy(alpha = if (dark) 0.10f else 0.30f),
                    1f to white.copy(alpha = if (dark) 0.28f else 0.60f),
                ),
                shape,
            ),
        )

        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp)) {
            val itemW = maxWidth / n
            // 5) la lente della scheda attiva
            Box(
                Modifier.size(itemW, maxHeight)
                    .graphicsLayer {
                        translationX = pos() * itemW.toPx()
                        val s = 1f + 0.10f * grow
                        scaleX = s; scaleY = 1f + 0.14f * grow
                    }
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(white.copy(alpha = if (dark) 0.26f else 0.70f), white.copy(alpha = if (dark) 0.10f else 0.34f)),
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(white.copy(alpha = if (dark) 0.55f else 0.95f), white.copy(alpha = if (dark) 0.12f else 0.35f))),
                        shape,
                    ),
            )
            // 6) icone ed etichette; il dito che scorre sulla barra cambia scheda
            Row(
                Modifier.fillMaxSize().pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; val i = (o.x / (size.width / n)).toInt().coerceIn(0, n - 1); if (i != currentNow) select(i) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        val i = (change.position.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                        if (i != currentNow) select(i)
                    }
                },
            ) {
                labels.forEachIndexed { i, label ->
                    val near = (1f - kotlin.math.abs(i - pos())).coerceIn(0f, 1f)
                    val tint = lerp(c.labelSecondary, c.accent, near)
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .semantics { contentDescription = label }
                            .clickable(
                                role = Role.Tab, onClick = { onSelect(i) }, indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        TabGlyph(glyphs[i], tint, current == i, Modifier.size(24.dp))
                        Text(
                            label, color = tint, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false,
                            fontWeight = if (near > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
