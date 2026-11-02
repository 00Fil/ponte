package it.ponte.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import it.ponte.Banner
import it.ponte.Notify
import kotlinx.coroutines.delay

/** L'avviso interno, a forma di notifica: scende dall'alto, si chiude da solo o con uno swipe in su. */
@Composable
fun BoxScope.BannerHost() {
    val c = Canone.colors
    val b by Notify.banner.collectAsState()
    var last by remember { mutableStateOf<Banner?>(null) }
    b?.let { last = it }
    LaunchedEffect(b?.id) { if (b != null) { delay(6000); Notify.banner.value = null } }
    AnimatedVisibility(
        b != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = Space.s4, vertical = Space.s2),
        enter = slideInVertically(spring(0.82f, 380f)) { -it - 40 } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(220)) { -it - 40 } + fadeOut(tween(160)),
    ) {
        val shape = RoundedCornerShape(26.dp)
        last?.let { n ->
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().shadow(18.dp, shape, ambientColor = c.labelPrimary.copy(alpha = 0.2f)).clip(shape).background(c.bgSurface)
                    .border(BorderStroke(1.dp, c.lineSeparator), shape)
                    .pointerInput(n.id) { var dy = 0f; detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -20f) Notify.banner.value = null }) { _, a -> dy += a } }
                    .padding(start = Space.s4, top = Space.s3, bottom = Space.s3, end = Space.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accent))
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1)
                    Text(n.text, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 2)
                }
                if (n.action != null) CButton(n.action, { n.onAction?.invoke(); Notify.banner.value = null }, kind = ButtonKind.Secondary)
            }
        }
    }
}
