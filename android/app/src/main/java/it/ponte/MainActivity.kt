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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
import it.ponte.ui.ArchiveScreen
import it.ponte.ui.Canone
import it.ponte.ui.CanoneType
import it.ponte.ui.ClipScreen
import it.ponte.ui.FinanceScreen
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

private val TABS = listOf("Home", "Appunti", "Archivio", "Finanze", "Impostazioni")
private val TAB_GLYPHS = listOf(0, 2, 3, 5, 6)
private val MOBILE_TAB_LABELS = listOf("Home", "Appunti", "Archivio", "Finanze", "Impost.")

private fun settingsTitle(page: String): String = when (page) {
    "devices" -> "Dispositivi collegati"
    "audio" -> "Audio e cuffie"
    "device" -> "Questo dispositivo"
    "permissions" -> "Permessi"
    "updates" -> "Aggiornamenti"
    "backup" -> "Backup"
    else -> "Impostazioni"
}

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
                var showBackup by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    MainShell(r, onBackup = { showBackup = true }) { r.prefs.onboarded = false; mode = 0 }
                    if (showBackup) it.ponte.ui.BackupScreen(r) { showBackup = false }
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
    var settingsPage by remember { mutableStateOf("home") }
    fun select(i: Int) { scope.launch { pager.animateScrollToPage(i, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 320f)) } }
    fun selectTab(i: Int) {
        if (i == 4) settingsPage = "home"
        select(i)
    }
    // Posizione continua tra le pagine: titolo e barra seguono il gesto.
    val pos = { pager.currentPage + pager.currentPageOffsetFraction }

    val content: @Composable (Int) -> Unit = { t ->
        when (t) {
            0 -> HomeScreen(r) { destination ->
                when (destination) {
                    1 -> { settingsPage = "devices"; select(4) }
                    2 -> select(1)
                    3 -> select(2)
                    4 -> { settingsPage = "audio"; select(4) }
                    5 -> select(3)
                }
            }
            1 -> ClipScreen(r)
            2 -> ArchiveScreen(r)
            3 -> FinanceScreen(r)
            else -> SettingsScreen(r, replayOnboarding, onBackup, settingsPage) { settingsPage = it }
        }
    }

    // Testata: fibra ottica dietro e titoli che scorrono con le pagine, in parallasse.
    val header: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            FiberField(Modifier.matchParentSize(), band = true, centerY = 0.42f, spread = 0.9f)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to c.bgCanvas)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3)) {
                TABS.forEachIndexed { i, t ->
                    Text(
                        if (i == 4) settingsTitle(settingsPage) else t, style = CanoneType.title1, color = c.labelPrimary,
                        modifier = Modifier.graphicsLayer {
                            val d = i - pos()
                            alpha = (1f - kotlin.math.abs(d) * 1.6f).coerceIn(0f, 1f)
                            translationX = -d * 72.dp.toPx()
                        },
                    )
                }
            }
        }
    }

    val pages: @Composable (Modifier) -> Unit = { m ->
        androidx.compose.foundation.pager.HorizontalPager(pager, m, beyondViewportPageCount = 1) { t -> Box(Modifier.fillMaxSize()) { content(t) } }
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
                    TABS.forEachIndexed { i, t -> SideItem(t, TAB_GLYPHS[i], pager.currentPage == i) { selectTab(i) } }
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
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    pages(Modifier.fillMaxSize())
                    sheet()
                }
                TabBar(pos, pager.currentPage) { selectTab(it) }
            }
        }
    }
}

/** Barra mobile: una superficie leggera, cinque destinazioni e stato selezionato morbido. */
@Composable
private fun TabBar(pos: () -> Float, current: Int, onSelect: (Int) -> Unit) {
    val c = Canone.colors
    val light = c.bgCanvas.luminance() > 0.5f
    val barColor = if (light) c.bgSurface.copy(alpha = 0.97f) else c.bgSurface.copy(alpha = 0.96f)

    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.s4, vertical = Space.s2),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(26.dp)).background(barColor),
        ) {
            val itemWidth = maxWidth / TABS.size
            val density = LocalDensity.current
            val itemPx = with(density) { itemWidth.toPx() }
            val glide by androidx.compose.animation.core.animateFloatAsState(
                targetValue = pos().coerceIn(0f, (TABS.size - 1).toFloat()),
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 230f),
                label = "tab-smooth",
            )
            val indicatorWidth = itemWidth - 6.dp
            val indicatorPx = with(density) { indicatorWidth.toPx() }
            Box(
                Modifier.align(Alignment.CenterStart).graphicsLayer {
                    translationX = glide * itemPx + (itemPx - indicatorPx) / 2f
                }.size(width = indicatorWidth, height = 60.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(if (light) c.bgSunken.copy(alpha = 0.86f) else c.bgSunken.copy(alpha = 0.94f)),
            )

            Row(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 6.dp)) {
                TABS.forEachIndexed { i, label ->
                    val proximity = (1f - kotlin.math.abs(i - pos())).coerceIn(0f, 1f)
                    val iconColor = androidx.compose.ui.graphics.lerp(c.labelTertiary, c.labelPrimary, proximity)
                    val textColor = androidx.compose.ui.graphics.lerp(c.labelTertiary, c.labelPrimary, proximity)
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .semantics { contentDescription = label; selected = current == i }
                            .clickable(
                                role = Role.Tab, onClick = { onSelect(i) }, indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TabGlyph(TAB_GLYPHS[i], iconColor, current == i, Modifier.size(21.dp))
                        Text(
                            MOBILE_TAB_LABELS[i],
                            style = CanoneType.footnote.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = if (current == i) FontWeight.Medium else FontWeight.Normal,
                            ),
                            color = textColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Voce della barra laterale (finestre larghe): icona e testo, sfondo tenue quando è attiva. */
@Composable
private fun SideItem(label: String, index: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Canone.colors
    val bg by animateColorAsState(if (selected) c.accentSubtle else Color.Transparent, tween(220), label = "fondo")
    val fg by animateColorAsState(if (selected) c.accentOnSubtle else c.labelSecondary, tween(220), label = "testo")
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(bg).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = Space.s4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        TabGlyph(index, fg, selected, Modifier.size(22.dp))
        Text(label, maxLines = 1, style = if (selected) CanoneType.footnote.copy(fontWeight = FontWeight.Medium) else CanoneType.footnote, color = fg)
    }
}
