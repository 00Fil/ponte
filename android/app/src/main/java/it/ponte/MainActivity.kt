package it.ponte

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import it.ponte.ui.BannerHost
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
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
import it.ponte.ui.Brand
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import it.ponte.ui.UpdateSheet
import it.ponte.ui.FiberField
import it.ponte.ui.HomeScreen
import kotlinx.coroutines.launch
import it.ponte.ui.TabGlyph
import androidx.compose.runtime.collectAsState
import it.ponte.ui.Onboarding
import it.ponte.ui.UpdateDone
import androidx.compose.runtime.Composable
import androidx.compose.ui.zIndex
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
import it.ponte.ui.ShareScreen
import it.ponte.ui.Canone
import it.ponte.ui.CanoneType
import it.ponte.ui.PonteTheme
import it.ponte.ui.SettingsScreen
import it.ponte.ui.Space

class MainActivity : ComponentActivity() {
    override fun onPause() { super.onPause(); Notify.foreground = false }

    override fun onResume() {
        super.onResume()
        Notify.foreground = true
        Ponte.ensure(this).clipLog.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PonteService.start(this)
        fastestMode()
        // Il Router carica archivi e reti: fuori dal thread principale, così la prima schermata non si blocca.
        val ready = androidx.compose.runtime.mutableStateOf<it.ponte.Router?>(Ponte.router)
        if (ready.value == null) Thread {
            val rt = Ponte.ensure(applicationContext)
            it.ponte.ui.MoneyPrivacy.hidden = rt.prefs.hideMoney
            runOnUiThread { ready.value = rt }
        }.start() else it.ponte.ui.MoneyPrivacy.hidden = ready.value!!.prefs.hideMoney
        setContent { PonteTheme { ready.value?.let { PonteApp(it) } } }
    }
}

/** Chiede il massimo del ritmo di aggiornamento dello schermo alla risoluzione attuale (120 Hz dove c'è). */
private fun android.app.Activity.fastestMode() {
    try {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    } catch (_: Throwable) {}
}

private val TABS = listOf("Home", "Condividi", "Finanze", "Agenda", "Impostazioni")
/** Icona di ogni scheda (indici di TabGlyph). */
private val GLYPHS = listOf(0, 1, 6, 11, 5)

@Composable
private fun PonteApp(r: Router) {
    val ctx = LocalContext.current
    val info = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
    @Suppress("DEPRECATION") val code = remember { info.longVersionCode.toInt() }
    val name = remember { info.versionName ?: "" }
    // 0 = prima installazione, 1 = appena aggiornata, 2 = app. Un aggiornamento salta i permessi: una sola pagina.
    var mode by remember { mutableIntStateOf(if (!r.prefs.onboarded) 0 else if (r.prefs.seenVersion != code) 1 else 2) }
    // Passaggio tra onboarding e app: dissolvenza lunga e morbida.
    AnimatedContent(mode, transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) }, label = "avvio") { m ->
        when (m) {
            0 -> Onboarding(r) { r.prefs.seenVersion = code; mode = 2 }
            1 -> UpdateDone(name) { r.prefs.seenVersion = code; mode = 2 }
            else -> {
                var page by remember { mutableIntStateOf(0) } // 0 nessuna, 1 backup, 2 dispositivi
                Box(Modifier.fillMaxSize()) {
                    MainShell(r, onBackup = { page = 1 }) { r.prefs.onboarded = false; mode = 0 }
                    if (page == 1) it.ponte.ui.BackupScreen(r) { page = 0 }
                    it.ponte.ui.FlashHost()
                }
            }
        }
    }
}

@Composable
private fun MainShell(r: Router, onBackup: () -> Unit, replayOnboarding: () -> Unit) {
    val c = Canone.colors
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0) { TABS.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun select(i: Int) {
        scope.launch {
            // Un tocco su una scheda lontana non attraversa le pagine in mezzo (comporle tutte bloccava lo schermo): salta a quella accanto e scorre l'ultima.
            val cur = pager.currentPage
            if (kotlin.math.abs(i - cur) > 1) pager.scrollToPage(if (i > cur) i - 1 else i + 1)
            pager.animateScrollToPage(i, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 360f))
        }
    }
    // Le pagine si preparano una alla volta a riposo, così il primo passaggio su ognuna non compone nulla al volo.
    var warm by remember { mutableIntStateOf(1) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1800)
        for (k in 2..TABS.size - 1) { warm = k; kotlinx.coroutines.delay(700) }
    }
    // Posizione continua tra le pagine (es. 1,4 = a metà tra la seconda e la terza): titolo e barra la seguono col dito.
    val pos = { pager.currentPage + pager.currentPageOffsetFraction }

    // Pagina aperta in ogni modulo (null = la sua dashboard). Sta qui così la Home può aprire direttamente una funzione e "indietro" torna alla dashboard.
    var shareSub by remember { mutableStateOf<String?>(null) }
    var finSub by remember { mutableStateOf<String?>(null) }
    var setSub by remember { mutableStateOf<String?>(null) }
    var agSub by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(
        enabled = (pager.currentPage == 2 && finSub != null) || (pager.currentPage == 4 && setSub != null) || (pager.currentPage == 3 && agSub != null),
    ) {
        when (pager.currentPage) { 2 -> finSub = null; 3 -> agSub = null; else -> setSub = null }
    }
    val content: @Composable (Int) -> Unit = { t ->
        when (t) {
            0 -> HomeScreen(r) { to ->
                when (to) {
                    "devices" -> select(1)
                    "clips", "images" -> { shareSub = to; select(1) }
                    "finance" -> { finSub = null; select(2) }
                    "agenda" -> { agSub = null; select(3) }
                    "studio" -> { agSub = "study"; select(3) }
                    "perm" -> { setSub = "perm"; select(4) }
                    else -> { setSub = null; select(4) }
                }
            }
            1 -> ShareScreen(r, shareSub, { shareSub = it }, onBackup)
            2 -> it.ponte.ui.FinanceScreen(r, finSub) { finSub = it }
            3 -> it.ponte.ui.AgendaScreen(r, agSub) { agSub = it }
            else -> SettingsScreen(r, setSub, { setSub = it }, replayOnboarding)
        }
    }

    var devOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(devOpen) { if (devOpen) { kotlinx.coroutines.delay(7000); devOpen = false } }
    val np = it.ponte.ui.rememberNowPlaying()
    var dock by remember { mutableIntStateOf(0) }      // 0 niente, 1 goccia staccata, 2 schermo intero, 3 agganciato alla barra
    var prevDock by remember { mutableIntStateOf(1) }  // dove tornare chiudendo lo schermo intero
    androidx.compose.runtime.LaunchedEffect(np?.title, np?.playing) {
        if (np == null) dock = 0
        else if (np.playing && dock == 0) dock = 1
    }
    // Testata: fibra ottica dietro e titoli che scorrono con le pagine, in parallasse.
    val header: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            FiberField(Modifier.matchParentSize(), band = true, centerY = 0.42f, spread = 0.9f)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.30f to Color.Transparent, 0.92f to c.bgCanvas, 1f to c.bgCanvas)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3)) {
                TABS.forEachIndexed { i, t ->
                    Column(
                        Modifier.fillMaxWidth().graphicsLayer {
                            val d = i - pos()
                            alpha = (1f - kotlin.math.abs(d) * 1.6f).coerceIn(0f, 1f)
                            translationX = -d * 72.dp.toPx()
                        },
                    ) {
                        Text(t, style = CanoneType.title1, color = c.labelPrimary)
                        if (i == 0) Box(Modifier.fillMaxWidth().padding(top = 4.dp)) { it.ponte.ui.DevicesMorph(r, devOpen, onClose = { devOpen = false }) { devOpen = !devOpen } }
                    }
                }
            }
        }
    }

    val pages: @Composable (Modifier) -> Unit = { m ->
        androidx.compose.foundation.pager.HorizontalPager(pager, m, beyondViewportPageCount = warm, userScrollEnabled = it.ponte.ui.DrawerBar.mode == 0) { t ->
            // profondità: la pagina che esce si attenua e si rimpicciolisce appena (solo fase di disegno)
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val off = kotlin.math.abs((pager.currentPage - t) + pager.currentPageOffsetFraction)
                    alpha = 1f - (off * 0.45f).coerceIn(0f, 0.6f)
                    val sc = 1f - (off * 0.05f).coerceIn(0f, 0.06f)
                    scaleX = sc; scaleY = sc
                },
            ) { content(t) }
        }
    }

    // Scheda dell'aggiornamento: galleggia in basso, non sposta il contenuto.
    val upd by r.updater.ui.collectAsState()
    val sheet: @Composable BoxScope.() -> Unit = {
        BannerHost()
        androidx.compose.animation.AnimatedVisibility(
            upd != null, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(1f, 300f)) { it / 2 } + fadeIn(tween(220)),
            exit = androidx.compose.animation.slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        ) {
            var last by remember { mutableStateOf(upd) }
            upd?.let { last = it }
            last?.let { Box(Modifier.widthIn(max = 560.dp).padding(Space.s4)) { UpdateSheet(it) } }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(it.ponte.ui.LocalNowPlaying provides np) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bgCanvas).pointerInput(Unit) {
            // un tocco qualsiasi, in ogni punto, richiude il popup dei dispositivi (senza consumarlo)
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (devOpen) devOpen = false
            }
        },
    ) {
        if (maxWidth >= 1024.dp) {
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.width(240.dp).fillMaxHeight().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Row(Modifier.padding(Space.s3, Space.s4, Space.s3, Space.s5), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        it.ponte.ui.RelayMark(32.dp)
                        Text("Relay", style = CanoneType.title3, color = c.labelPrimary)
                    }
                    TABS.forEachIndexed { i, t -> SideItem(t, GLYPHS[i], pager.currentPage == i) { select(i) } }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.zIndex(2f)) { header() }
                        pages(Modifier.weight(1f).fillMaxWidth())
                    }
                    sheet()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding().zIndex(2f)) { header() }
                val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val strip by androidx.compose.animation.core.animateDpAsState(if (dock == 3 || (dock == 2 && prevDock == 3)) 62.dp else 0.dp, androidx.compose.animation.core.spring(0.9f, 400f), label = "striscia")
                val inset = 66.dp + 10.dp + navBottom + strip
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    androidx.compose.runtime.CompositionLocalProvider(it.ponte.ui.LocalBarInset provides inset) {
                        pages(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) })
                    }
                                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = inset)) { sheet() }
                    // Nei contanti la barra diventa il tasto di conferma (indietro + scorri per registrare).
                    val cashOn = it.ponte.ui.CashBar.on && pager.currentPage == 2
                    val drawerOn = it.ponte.ui.DrawerBar.mode != 0 && pager.currentPage == 3
                    if (np != null && !cashOn && !drawerOn) {
                        val dens = androidx.compose.ui.platform.LocalDensity.current
                        val rise = remember { androidx.compose.animation.core.Animatable(0f) }
                        val settle = remember { androidx.compose.animation.core.Animatable(0f) }
                        val dropOn = dock == 1 || (dock == 2 && prevDock == 1)
                        androidx.compose.runtime.LaunchedEffect(dropOn) {
                            if (dropOn) {
                                settle.snapTo(0f)
                                rise.animateTo(1f, tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                settle.animateTo(1f, androidx.compose.animation.core.spring(0.55f, 320f))
                            } else { settle.snapTo(1f); rise.animateTo(0f, tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                        }
                        // quanto sta sopra il bordo alto della barra (dp): parte dentro la barra, sale oltre il punto di distacco e ricade nella sua posizione
                        val upDp = { -61f + (40f + 61f) * rise.value + (14f - 40f) * settle.value }
                        val barBottom = 10.dp + navBottom
                        if (rise.value > 0.01f || dropOn) {
                            // il collo d'acqua tra la barra e la goccia, che si assottiglia fino a staccarsi
                            androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomCenter).padding(bottom = barBottom + 66.dp).size(220.dp, 90.dp)) {
                                val up = upDp()
                                val gate = 1f - settle.value
                                val k = ((1f - up / 30f).coerceIn(0f, 1f)) * gate
                                if (up > 0f && k > 0.02f) {
                                    val d = dens.density
                                    val cx = size.width / 2f
                                    val yB = size.height + 2f * d
                                    val yT = size.height - up * d
                                    val ym = (yB + yT) / 2f
                                    val hw = 30f * d * k * k
                                    val fl = 18f * d * k
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(cx - hw - fl, yB); quadraticTo(cx - hw, yB, cx - hw, ym); quadraticTo(cx - hw, yT, cx - hw - fl, yT)
                                        lineTo(cx + hw + fl, yT); quadraticTo(cx + hw, yT, cx + hw, ym); quadraticTo(cx + hw, yB, cx + hw + fl, yB); close()
                                    }
                                    drawPath(path, c.bgCanvas.copy(alpha = 0.62f))
                                    drawPath(path, c.lineSeparator, style = androidx.compose.ui.graphics.drawscope.Stroke(1f * d))
                                }
                            }
                            it.ponte.ui.MiniPlayer(
                                np, layer, { prevDock = 1; dock = 2 }, { dock = 3 },
                                Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = barBottom + 66.dp + 14.dp)
                                    .graphicsLayer {
                                        val up = upDp()
                                        translationY = -(up - 14f) * dens.density
                                        val s = ((up + 61f) / 75f).coerceIn(0f, 1f)
                                        scaleX = 0.5f + 0.5f * s; scaleY = 0.7f + 0.3f * s
                                        alpha = (s * 2.5f).coerceIn(0f, 1f)
                                    },
                            )
                        }
                                            androidx.compose.animation.AnimatedVisibility(
                            dock == 3 || (dock == 2 && prevDock == 3), Modifier.align(Alignment.BottomCenter),
                            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(0.9f, 420f)) { it / 2 } + fadeIn(tween(160)),
                            exit = androidx.compose.animation.slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(140)),
                        ) {
                            it.ponte.ui.DockedStrip(np, layer, { prevDock = 3; dock = 2 }, { dock = 1 }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp + navBottom + 66.dp + 4.dp))
                        }
                    }
                    if (cashOn) it.ponte.ui.CashConfirmBar(layer, Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 10.dp + navBottom))
                    else if (drawerOn) it.ponte.ui.DrawerBarUi(layer, Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 10.dp + navBottom))
                    else it.ponte.ui.GlassTabBar(
                        layer, TABS, GLYPHS, pos, pager.currentPage, { select(it) },
                        Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 10.dp + navBottom)
                            .then(it.ponte.ui.swipeUpObserver(dock == 3) { dock = 1 }),
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            dock == 2 && np != null, Modifier.fillMaxSize().zIndex(5f),
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(0.9f, 320f)) { it } + fadeIn(tween(160)),
            exit = androidx.compose.animation.slideOutVertically(tween(240)) { it } + fadeOut(tween(200)),
        ) { if (np != null) it.ponte.ui.FullPlayer(np) { dock = prevDock } }
    }
    }
}


/** Voce della barra laterale (finestre larghe): icona e testo, sfondo tenue quando è attiva. */
@Composable
private fun SideItem(label: String, glyph: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Canone.colors
    val bg by animateColorAsState(if (selected) c.accentSubtle else Color.Transparent, tween(220), label = "fondo")
    val fg by animateColorAsState(if (selected) c.accentOnSubtle else c.labelSecondary, tween(220), label = "testo")
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(bg).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = Space.s4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        TabGlyph(glyph, fg, selected, Modifier.size(22.dp))
        Text(label, maxLines = 1, style = if (selected) CanoneType.footnote.copy(fontWeight = FontWeight.Medium) else CanoneType.footnote, color = fg)
    }
}
