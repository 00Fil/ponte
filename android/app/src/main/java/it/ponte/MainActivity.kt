package it.ponte

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import it.ponte.ui.GradientView
import it.ponte.ui.Brand
import it.ponte.ui.DownloadCard
import it.ponte.ui.TabGlyph
import androidx.compose.runtime.collectAsState
import it.ponte.ui.Onboarding
import it.ponte.ui.UpdateDone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.ponte.ui.ArchiveScreen
import it.ponte.ui.Canone
import it.ponte.ui.CanoneType
import it.ponte.ui.ClipScreen
import it.ponte.ui.DevicesScreen
import it.ponte.ui.AudioScreen
import it.ponte.ui.PonteTheme
import it.ponte.ui.SettingsScreen
import it.ponte.ui.Space

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Ponte.ensure(this).clipLog.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PonteService.start(this)
        val router = Ponte.ensure(this)
        setContent { PonteTheme { PonteApp(router) } }
    }
}

private val TABS = listOf("Dispositivi", "Appunti", "Archivio", "Audio", "Impostazioni")

@Composable
private fun PonteApp(r: Router) {
    val ctx = LocalContext.current
    val info = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
    @Suppress("DEPRECATION") val code = remember { info.longVersionCode.toInt() }
    val name = remember { info.versionName ?: "" }
    // 0 = prima installazione, 1 = appena aggiornata, 2 = app. Un aggiornamento salta i permessi: una sola pagina.
    var mode by remember { mutableIntStateOf(if (!r.prefs.onboarded) 0 else if (r.prefs.seenVersion != code) 1 else 2) }
    // Passaggio tra onboarding e app: dissolvenza lunga e morbida.
    AnimatedContent(mode, transitionSpec = { fadeIn(tween(600)).togetherWith(fadeOut(tween(400))) }, label = "avvio") { m ->
        when (m) {
            0 -> Onboarding(r) { r.prefs.seenVersion = code; mode = 2 }
            1 -> UpdateDone(name) { r.prefs.seenVersion = code; mode = 2 }
            else -> MainShell(r) { r.prefs.onboarded = false; mode = 0 }
        }
    }
}

@Composable
private fun MainShell(r: Router, replayOnboarding: () -> Unit) {
    val c = Canone.colors
    var tab by remember { mutableIntStateOf(0) }
    var previous by remember { mutableIntStateOf(0) }
    fun select(i: Int) { previous = tab; tab = i }

    val content: @Composable (Int) -> Unit = { t ->
        when (t) {
            0 -> DevicesScreen(r)
            1 -> ClipScreen(r)
            2 -> ArchiveScreen(r)
            3 -> AudioScreen(r)
            else -> SettingsScreen(r, replayOnboarding)
        }
    }

    // Testata con il gradiente ASCII e il titolo della pagina, che scorre al cambio di scheda.
    val header: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            GradientView(Modifier.matchParentSize(), shape = "band", cell = 12, intro = true, ascii = 1, bg = c.bgCanvas)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to c.bgCanvas)))
            AnimatedContent(
                tab,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = Space.s5, vertical = Space.s3),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInVertically(tween(380)) { it / 2 * dir } + fadeIn(tween(320)))
                        .togetherWith(slideOutVertically(tween(260)) { -it / 2 * dir } + fadeOut(tween(200)))
                },
                label = "titolo",
            ) { t -> Text(TABS[t], style = CanoneType.title1, color = c.labelPrimary) }
        }
    }

    val dl by r.updater.progress.collectAsState()
    val banner: @Composable () -> Unit = {
        androidx.compose.animation.AnimatedVisibility(
            dl != null,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(0.7f, 300f)) + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
        ) { dl?.let { Box(Modifier.padding(horizontal = Space.s4, vertical = Space.s2)) { DownloadCard(it) } } }
    }

    val pages: @Composable (Modifier) -> Unit = { m ->
        AnimatedContent(
            tab, modifier = m,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(360)) { it / 8 * dir } + fadeIn(tween(300, 60)))
                    .togetherWith(slideOutHorizontally(tween(260)) { -it / 8 * dir } + fadeOut(tween(180)))
            },
            label = "pagina",
        ) { t -> Box(Modifier.fillMaxSize()) { content(t) } }
    }

    // Il layout segue la finestra, non il dispositivo: barra laterale da 1024, barra in basso sotto.
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bgCanvas)) {
        if (maxWidth >= 1024.dp) {
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(
                    Modifier.width(240.dp).fillMaxHeight().padding(Space.s4),
                    verticalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    Text("Ponte", style = CanoneType.title3, color = c.labelPrimary, modifier = Modifier.padding(Space.s3, Space.s4, Space.s3, Space.s5))
                    TABS.forEachIndexed { i, t -> TabItem(t, i, tab == i, Modifier.fillMaxWidth(), start = true) { select(i) } }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    header()
                    banner()
                    pages(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding()) { header() }
                banner()
                pages(Modifier.weight(1f).fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().background(c.bgSurface).navigationBarsPadding().padding(horizontal = Space.s2, vertical = Space.s2),
                    horizontalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    TABS.forEachIndexed { i, t -> TabItem(t, i, tab == i, Modifier.weight(1f)) { select(i) } }
                }
            }
        }
    }
}

/** Voce della TabBar: sul telefono è solo un'icona che rimbalza e si accende nella pillola sfumata; nella barra laterale resta il testo. */
@Composable
private fun TabItem(label: String, index: Int, selected: Boolean, modifier: Modifier, start: Boolean = false, onClick: () -> Unit) {
    val c = Canone.colors
    val fg by animateColorAsState(if (selected) Color.White else c.labelSecondary, tween(240), label = "testo")
    val pill by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, androidx.compose.animation.core.spring(0.6f, 380f), label = "pillola")
    if (start) {
        val bg by animateColorAsState(if (selected) c.accentSubtle else Color.Transparent, tween(240), label = "fondo")
        val fg2 by animateColorAsState(if (selected) c.accentOnSubtle else c.labelSecondary, tween(240), label = "testo2")
        Row(
            modifier.height(44.dp).clip(CircleShape).background(bg).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = Space.s4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            TabGlyph(index, fg2, selected, Modifier.size(22.dp))
            Text(label, maxLines = 1, style = if (selected) CanoneType.footnote.copy(fontWeight = FontWeight.Medium) else CanoneType.footnote, color = fg2)
        }
        return
    }
    Box(
        modifier.height(52.dp).clickable(role = Role.Tab, onClick = onClick, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 60.dp, height = 36.dp).graphicsLayer { scaleX = 0.5f + 0.5f * pill; scaleY = 0.7f + 0.3f * pill; alpha = pill.coerceIn(0f, 1f) }
                .clip(CircleShape).background(Brand.gradient),
        )
        TabGlyph(index, fg, selected, Modifier.size(24.dp))
    }
}
