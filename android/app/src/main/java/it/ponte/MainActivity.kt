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
        it.ponte.ui.MoneyPrivacy.hidden = router.prefs.hideMoney
        setContent { PonteTheme { PonteApp(router) } }
    }
}

private val TABS = listOf("Home", "Condividi", "Finanze", "Audio", "Impostazioni")
/** Icona di ogni scheda (indici di TabGlyph). */
private val GLYPHS = listOf(0, 1, 6, 4, 5)

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
    androidx.activity.compose.BackHandler(
        enabled = (pager.currentPage == 2 && finSub != null) || (pager.currentPage == 4 && setSub != null),
    ) {
        when (pager.currentPage) { 2 -> finSub = null; else -> setSub = null }
    }
    val content: @Composable (Int) -> Unit = { t ->
        when (t) {
            0 -> HomeScreen(r) { to ->
                when (to) {
                    "devices" -> select(1)
                    "clips", "images" -> { shareSub = to; select(1) }
                    "finance" -> { finSub = null; select(2) }
                    "audio" -> select(3)
                    "perm" -> { setSub = "perm"; select(4) }
                    else -> { setSub = null; select(4) }
                }
            }
            1 -> ShareScreen(r, shareSub, { shareSub = it }, onBackup)
            2 -> it.ponte.ui.FinanceScreen(r, finSub) { finSub = it }
            3 -> AudioScreen(r) { setSub = "audio"; select(4) }
            else -> SettingsScreen(r, setSub, { setSub = it }, replayOnboarding)
        }
    }

    val headerDevices by r.devices.collectAsState()
    // Testata: fibra ottica dietro e titoli che scorrono con le pagine, in parallasse.
    val header: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            FiberField(Modifier.matchParentSize(), band = true, centerY = 0.42f, spread = 0.9f)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.30f to Color.Transparent, 0.92f to c.bgCanvas, 1f to c.bgCanvas)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3)) {
                TABS.forEachIndexed { i, t ->
                    Column(
                        Modifier.graphicsLayer {
                            val d = i - pos()
                            alpha = (1f - kotlin.math.abs(d) * 1.6f).coerceIn(0f, 1f)
                            translationX = -d * 72.dp.toPx()
                        },
                    ) {
                        Text(t, style = CanoneType.title1, color = c.labelPrimary)
                        if (i == 0) {
                            val n = headerDevices.size
                            Text(
                                if (n == 0) androidx.compose.ui.text.buildAnnotatedString { append("Solo questo dispositivo") }
                                else androidx.compose.ui.text.buildAnnotatedString {
                                    append("Collegato con ")
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = c.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                                    append(if (n == 1) "1 dispositivo" else "$n dispositivi")
                                    pop()
                                },
                                style = CanoneType.footnote, color = c.labelSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    val pages: @Composable (Modifier) -> Unit = { m ->
        androidx.compose.foundation.pager.HorizontalPager(pager, m, beyondViewportPageCount = warm) { t ->
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

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bgCanvas)) {
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
                        header()
                        pages(Modifier.weight(1f).fillMaxWidth())
                    }
                    sheet()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding()) { header() }
                val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val inset = 66.dp + 10.dp + navBottom
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    androidx.compose.runtime.CompositionLocalProvider(it.ponte.ui.LocalBarInset provides inset) {
                        pages(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) })
                    }
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(c.bgCanvas, Color.Transparent))))
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = inset)) { sheet() }
                    it.ponte.ui.GlassTabBar(
                        layer, TABS, GLYPHS, pos, pager.currentPage, { select(it) },
                        Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 10.dp + navBottom),
                    )
                }
            }
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
