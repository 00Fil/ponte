package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stato della barra di conferma dei contanti. FinanceScreen lo aggiorna, MainShell al posto della barra delle schede mostra [CashConfirmBar].
 */
object CashBar {
    var on by mutableStateOf(false)
    var enabled by mutableStateOf(false)
    var label by mutableStateOf("")
    var onConfirm: () -> Unit = {}
    var onBack: () -> Unit = {}
}

/**
 * Nella pagina dei contanti la barra delle schede diventa il tasto di conferma: a sinistra un tasto rotondo di vetro per tornare indietro,
 * nel resto dello spazio una capsula di vetro con una lente da trascinare fino in fondo per registrare.
 */
@Composable
fun CashConfirmBar(layer: GraphicsLayer, modifier: Modifier = Modifier) =
    ConfirmBar(
        layer, modifier, 0f, { Icon24(Ico.BACK, Canone.colors.labelPrimary, Modifier.size(26.dp)) }, { CashBar.onBack() }, CashBar.enabled,
        if (CashBar.enabled) CashBar.label else "Tocca le banconote", listOf(Brand.mid, Brand.sky), { CashBar.onConfirm() },
    )

/** Barra a due parti: a sinistra un tasto di vetro per tornare (quarto della larghezza, o rotondo se [backFrac] è 0), a destra la capsula da trascinare per confermare. */
@Composable
fun ConfirmBar(
    layer: GraphicsLayer, modifier: Modifier, backFrac: Float, backIcon: @Composable () -> Unit, onBack: () -> Unit,
    enabled: Boolean, label: String, fill: List<Color>, onConfirm: () -> Unit,
) {
    val c = Canone.colors
    val dark = isDark()
    val dens = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val barH = 66.dp
    val gap = 10.dp
    val prog = remember { Animatable(0f) }
    val bubble = remember { Animatable(1f) }
    val trackLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val enabledNow by rememberUpdatedState(enabled)
    val confirmNow by rememberUpdatedState(onConfirm)

    BoxWithConstraints(modifier.onGloballyPositioned { origin = it.positionInParent() }.widthIn(max = 440.dp).fillMaxWidth().height(barH)) {
        val backW = if (backFrac > 0f) (maxWidth - gap) * backFrac else barH
        val slideW = maxWidth - backW - gap
        val pad = 5.dp
        val thumb = barH - pad * 2
        val travelPx = with(dens) { (slideW - thumb - pad * 2).toPx() }

        // ---- tasto indietro, rotondo
        val t = rememberDropTouch()
        Box(Modifier.size(backW, barH).dropGestures(t, onBack).graphicsLayer {
            val p = t.press.value; scaleX = 1f + 0.10f * p; scaleY = 1f + 0.10f * p
        }) {
            Glass(layer, { origin }, backW, barH, edge = 12.dp, amount = 5.dp, aberr = 0.18f, sat = 1.5f, magnify = 0f, blur = 14.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgCanvas.copy(alpha = if (dark) 0.42f else 0.50f), 1f to c.bgCanvas.copy(alpha = if (dark) 0.30f else 0.36f)), CircleShape))
            Box(Modifier.fillMaxSize().drop(dark, tint = Color.Transparent, press = { t.press.value }, touch = t))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { backIcon() }
        }

        // ---- capsula di conferma
        Box(Modifier.padding(start = backW + gap).size(slideW, barH)) {
            Box(Modifier.fillMaxSize().drawWithContent { trackLayer.record { this@drawWithContent.drawContent() }; drawLayer(trackLayer) }) {
                Glass(layer, { Offset(origin.x + with(dens) { (backW + gap).toPx() }, origin.y) }, slideW, barH, edge = 12.dp, amount = 5.dp, aberr = 0.18f, sat = 1.5f, magnify = 0f, blur = 14.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgCanvas.copy(alpha = if (dark) 0.42f else 0.50f), 1f to c.bgCanvas.copy(alpha = if (dark) 0.30f else 0.36f)), CircleShape))
                // il percorso già fatto si colora del blu di marca
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).drawBehind {
                        val w = with(dens) { (pad + thumb / 2 + barH / 2).toPx() } + prog.value * travelPx
                        drawRoundRect(Brush.horizontalGradient(listOf(fill.first().copy(alpha = 0.55f), fill.last().copy(alpha = 0.35f))), size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
                    },
                )
                Box(Modifier.fillMaxSize().drop(dark, tint = Color.Transparent))
                Box(Modifier.fillMaxSize().padding(start = thumb + pad, end = Space.s4), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = CanoneType.bodyMedium, color = c.labelPrimary.copy(alpha = if (enabled) 0.85f else 0.45f), maxLines = 1,
                        modifier = Modifier.graphicsLayer { alpha = 1f - prog.value * 0.8f },
                    )
                }
            }
            // la lente da trascinare: rifrange la capsula con la sua scritta
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabledNow) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); return@awaitEachGesture }
                        val padPx = pad.toPx()
                        val start = padPx + thumb.toPx() / 2
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { bubble.animateTo(1.18f, spring(0.7f, 380f)) }
                        var lastStep = 0
                        scope.launch { prog.snapTo(((down.position.x - start) / travelPx).coerceIn(0f, 1f)) }
                        drag(down.id) { ch ->
                            ch.consume()
                            val f = ((ch.position.x - start) / travelPx).coerceIn(0f, 1f)
                            val step = (f * 10).toInt()
                            if (step != lastStep) { lastStep = step; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            scope.launch { prog.snapTo(f) }
                        }
                        scope.launch { bubble.animateTo(1f, spring(0.8f, 300f)) }
                        if (prog.value >= 0.82f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                prog.animateTo(1f, spring(1f, 500f))
                                confirmNow()
                                delay(500)
                                prog.snapTo(0f)
                            }
                        } else scope.launch { prog.animateTo(0f, spring(0.62f, 220f)) }
                    }
                },
            ) {
                Box(
                    Modifier.padding(pad).graphicsLayer {
                        translationX = prog.value * travelPx
                        scaleX = bubble.value; scaleY = bubble.value
                        alpha = if (enabled) 1f else 0.5f
                    },
                ) {
                    Glass(
                        trackLayer, { Offset(with(dens) { pad.toPx() } + prog.value * travelPx, with(dens) { pad.toPx() }) },
                        thumb, thumb, edge = 10.dp, amount = 8.dp, aberr = 0.30f, sat = 1.3f, magnify = 0f, blur = 0.dp,
                    )
                    Box(Modifier.size(thumb).drop(dark, tint = Color.Transparent))
                    Box(Modifier.size(thumb), contentAlignment = Alignment.Center) { Icon24(Ico.BACK, c.labelPrimary, Modifier.size(24.dp).graphicsLayer { rotationZ = 180f }) }
                }
            }
        }
    }
}


/** Stato della barra quando si apre un cassetto: 1 = dettaglio (indietro + matita), 2 = modifica (X + conferma verde). */
object DrawerBar {
    var mode by mutableIntStateOf(0)
    var canConfirm by mutableStateOf(true)
    var onBack: () -> Unit = {}
    var onEdit: () -> Unit = {}
    var onConfirm: () -> Unit = {}
}

@Composable
fun DrawerBarUi(layer: GraphicsLayer, modifier: Modifier = Modifier) {
    val c = Canone.colors
    if (DrawerBar.mode == 2) {
        ConfirmBar(
            layer, modifier, 0.25f, { Icon24(Ico.CLOSE, c.labelPrimary, Modifier.size(26.dp)) }, { DrawerBar.onBack() }, DrawerBar.canConfirm, "Salva",
            listOf(Color(0xFF34C759), Color(0xFF5BD97C)), { DrawerBar.onConfirm() },
        )
        return
    }
    val dark = isDark()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val barH = 66.dp
    val gap = 10.dp
    BoxWithConstraints(modifier.onGloballyPositioned { origin = it.positionInParent() }.widthIn(max = 440.dp).fillMaxWidth().height(barH)) {
        val backW = (maxWidth - gap) * 0.25f
        val editW = maxWidth - gap - backW
        val dens = LocalDensity.current
        @Composable
        fun Btn(w: androidx.compose.ui.unit.Dp, x: androidx.compose.ui.unit.Dp, tint: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
            val t = rememberDropTouch()
            Box(Modifier.padding(start = x).size(w, barH).dropGestures(t, onClick).graphicsLayer { val p = t.press.value; scaleX = 1f + 0.05f * p; scaleY = 1f + 0.05f * p }) {
                Glass(layer, { Offset(origin.x + with(dens) { x.toPx() }, origin.y) }, w, barH, edge = 12.dp, amount = 5.dp, aberr = 0.18f, sat = 1.5f, magnify = 0f, blur = 14.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgCanvas.copy(alpha = if (dark) 0.42f else 0.50f), 1f to c.bgCanvas.copy(alpha = if (dark) 0.30f else 0.36f)), CircleShape))
                Box(Modifier.fillMaxSize().background(tint, CircleShape))
                Box(Modifier.fillMaxSize().drop(dark, tint = Color.Transparent, press = { t.press.value }, touch = t))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon() }
            }
        }
        Btn(backW, 0.dp, Color.Transparent, { DrawerBar.onBack() }) { Icon24(Ico.BACK, c.labelPrimary, Modifier.size(26.dp)) }
        Btn(editW, backW + gap, c.accent.copy(alpha = 0.16f), { DrawerBar.onEdit() }) { Icon24(Ico.PENCIL, c.accent, Modifier.size(28.dp)) }
    }
}
