package it.ponte.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import it.ponte.Root
import it.ponte.Router
import kotlinx.coroutines.launch

/**
 * Configurazione iniziale, alla Apple: quattro passi, transizioni morbide, una cosa per volta.
 * Benvenuto → collegamento → permessi → pronto. Si può rivedere dalle impostazioni.
 */
@Composable
fun Onboarding(r: Router, onDone: () -> Unit) {
    val c = Canone.colors
    var step by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    fun go(to: Int) { forward = to > step; step = to }

    Box(Modifier.fillMaxSize().background(c.bgCanvas)) {
        // Il gradiente resta sullo sfondo per tutto l'onboarding; nei passi con tanto testo si attenua.
        val dim by animateFloatAsState(if (step == 0 || step == 3) 1f else 0.35f, tween(700), label = "sfondo")
        GradientView(Modifier.fillMaxSize().graphicsLayer { alpha = dim }, shape = "hero", cell = 14, ascii = 1)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to c.bgCanvas.copy(alpha = 0.92f)),
            ),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(520, easing = FastOutSlowInEasing)) { it / 4 * dir } + fadeIn(tween(420, 120)))
                        .togetherWith(slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { -it / 4 * dir } + fadeOut(tween(260)))
                },
                label = "passo",
            ) { s ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (s) {
                        0 -> Welcome { go(1) }
                        1 -> Connect(r, onNext = { go(2) }, onBack = { go(0) })
                        2 -> Permissions(r, onNext = { go(3) }, onBack = { go(1) })
                        else -> Ready { r.prefs.onboarded = true; onDone() }
                    }
                }
            }
            Dots(step, 4)
            Spacer(Modifier.height(Space.s5))
        }
    }
}

/** Puntini di avanzamento: quello attivo si allunga in una pillola sfumata. */
@Composable
private fun Dots(current: Int, count: Int) {
    val c = Canone.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 24.dp else 8.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "punto")
            Box(
                Modifier.height(8.dp).width(w).clip(CircleShape)
                    .then(if (i == current) Modifier.background(Brand.gradient) else Modifier.background(c.fillControl)),
            )
        }
    }
}

/** Elementi che entrano uno dopo l'altro, salendo appena e comparendo. */
@Composable
private fun Stagger(index: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120L + index * 110L)
        a.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 36f }) { content() }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.s5, vertical = Space.s5),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) { content() }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val c = Canone.colors
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxSize().padding(Space.s5),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.Start,
    ) {
        Stagger(0) { Text("Ponte", style = CanoneType.title1.copy(fontSize = 64.sp, lineHeight = 68.sp), color = c.labelPrimary) }
        Spacer(Modifier.height(Space.s3))
        Stagger(1) { Text("I tuoi dispositivi, un solo spazio.", style = CanoneType.callout, color = c.labelPrimary) }
        Spacer(Modifier.height(Space.s2))
        Stagger(2) {
            Text(
                "Appunti, file, screenshot, notifiche e cuffie passano da un dispositivo all'altro senza che tu ci pensi.",
                style = CanoneType.body, color = c.labelSecondary,
            )
        }
        Spacer(Modifier.height(Space.s6))
        Stagger(3) { CButton("Inizia", onNext, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) }
    }
}

@Composable
private fun Connect(r: Router, onNext: () -> Unit, onBack: () -> Unit) {
    val c = Canone.colors
    var name by remember { mutableStateOf(r.prefs.name) }
    var hub by remember { mutableStateOf(r.prefs.hubUrl) }
    var token by remember { mutableStateOf(r.prefs.token) }
    Page {
        Stagger(0) { Text("Collega i tuoi dispositivi", style = CanoneType.title2, color = c.labelPrimary) }
        Stagger(1) {
            Text(
                "Stesso server e stesso token su tutti i dispositivi. Senza server funzionano comunque rete locale, Bluetooth e Wi-Fi Direct.",
                style = CanoneType.body, color = c.labelSecondary,
            )
        }
        Stagger(2) {
            CCard {
                CField("Nome di questo dispositivo", name, { name = it }, "Telefono")
                CField("Indirizzo del server", hub, { hub = it }, "ponte.esempio.it", mono = true)
                CField("Token", token, { token = it }, "Lo stesso del server", mono = true, secret = true)
            }
        }
        Stagger(3) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                CButton("Continua", {
                    r.prefs.name = name; r.prefs.hubUrl = hub; r.prefs.token = token
                    r.reconfigure()
                    onNext()
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = token.length >= 16)
                CButton("Indietro", onBack, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
            }
        }
    }
}

private class Perm(val title: String, val why: String, val granted: (Context) -> Boolean, val ask: () -> Unit)

@Composable
private fun Permissions(r: Router, onNext: () -> Unit, onBack: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val scope = rememberCoroutineScope()

    // Tornando dalle impostazioni di sistema le spunte si aggiornano da sole.
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun open(action: String, pkg: Boolean = true) {
        try {
            ctx.startActivity(if (pkg) Intent(action, Uri.parse("package:" + ctx.packageName)) else Intent(action))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
    }
    val nearby = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    val perms = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Perm("Notifiche", "Per tenere Ponte attivo e avvisarti", { has(it, Manifest.permission.POST_NOTIFICATIONS) }) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) })
        add(Perm("Mostra sopra altre app", "Appunti in tempo reale e capire quando usi il telefono", { Settings.canDrawOverlays(it) }) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) })
        if (Build.VERSION.SDK_INT >= 31) add(Perm("Bluetooth", "Cuffie e collegamento senza Wi-Fi", { has(it, Manifest.permission.BLUETOOTH_CONNECT) }) { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        add(Perm("Dispositivi Wi-Fi vicini", "Wi-Fi Direct con il tablet quando non c'è rete", { has(it, nearby) }) { ask.launch(nearby) })
        add(Perm("Foto e screenshot", "Per condividere gli screenshot appena li fai", { has(it, imagesPermission()) }) { ask.launch(imagesPermission()) })
        add(Perm("Installare aggiornamenti", "Ponte si aggiorna da solo dalle release", { it.packageManager.canRequestPackageInstalls() }) { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) })
        add(Perm("Nessun risparmio batteria", "Evita che Android fermi Ponte", {
            (it.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(it.packageName)
        }) { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, pkg = false) })
        add(Perm("Notifiche sul PC (facoltativo)", "Inoltra le notifiche del telefono al computer", { listenerEnabled(it) }) {
            r.prefs.notifications = true
            open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, pkg = false)
        })
        add(Perm("Appunti in tempo reale (avanzato)", "Con il root si attiva da qui; senza, serve un comando adb dal PC", { r.clipLog.hasLogPermission() }) {
            scope.launch {
                val ok = Root.available() && try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant ${ctx.packageName} android.permission.READ_LOGS")).waitFor() == 0
                } catch (_: Exception) { false }
                toast(ctx, if (ok) "Concesso: Ponte si riavvia" else "Dal PC: adb shell pm grant ${ctx.packageName} android.permission.READ_LOGS")
                tick++
            }
        })
    }
    @Suppress("UNUSED_EXPRESSION") tick

    Page {
        Stagger(0) { Text("Permessi", style = CanoneType.title2, color = c.labelPrimary) }
        Stagger(1) {
            val done = perms.count { it.granted(ctx) }
            Text("$done di ${perms.size} concessi. Puoi completarli anche più tardi dalle impostazioni.", style = CanoneType.body, color = c.labelSecondary)
        }
        perms.forEachIndexed { i, p ->
            Stagger(2 + i) { PermCard(p.title, p.why, p.granted(ctx), p.ask) }
        }
        Stagger(2 + perms.size) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                CButton("Continua", onNext, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                CButton("Indietro", onBack, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
            }
        }
    }
}

@Composable
private fun PermCard(title: String, why: String, granted: Boolean, onAsk: () -> Unit) {
    val c = Canone.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface.copy(alpha = 0.92f))
            .clickable(enabled = !granted, onClick = onAsk).padding(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CanoneType.bodyMedium, color = c.labelPrimary)
            Text(why, style = CanoneType.footnote, color = c.labelSecondary)
        }
        AnimatedContent(granted, transitionSpec = { (scaleIn(spring(dampingRatio = 0.55f)) + fadeIn()).togetherWith(fadeOut()) }, label = "spunta") { ok ->
            if (ok) AnimatedCheck(28.dp) else CButton("Consenti", onAsk)
        }
    }
}

@Composable
private fun Ready(onDone: () -> Unit) {
    val c = Canone.colors
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxSize().padding(Space.s5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedCheck(96.dp, delayMs = 150)
        Spacer(Modifier.height(Space.s6))
        Stagger(1) { Text("Tutto pronto", style = CanoneType.title1, color = c.labelPrimary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(Space.s3))
        Stagger(2) {
            Text(
                "Copia qualcosa qui e incollalo sul PC. Il resto lo fa Ponte.",
                style = CanoneType.body, color = c.labelSecondary, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Space.s6))
        Stagger(3) { CButton("Apri Ponte", onDone, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) }
    }
}

/**
 * Dopo un aggiornamento: niente permessi, una sola pagina come la prima della configurazione.
 */
@Composable
fun UpdateDone(version: String, onContinue: () -> Unit) {
    val c = Canone.colors
    Box(Modifier.fillMaxSize().background(c.bgCanvas)) {
        GradientView(Modifier.fillMaxSize(), shape = "hero", cell = 14, ascii = 1)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to c.bgCanvas.copy(alpha = 0.92f))))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Space.s5),
            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.Start,
        ) {
            AnimatedCheck(64.dp, delayMs = 250)
            Spacer(Modifier.height(Space.s5))
            Stagger(0) { Text("Passato con successo alla versione $version", style = CanoneType.title1.copy(fontSize = 40.sp, lineHeight = 44.sp), color = c.labelPrimary) }
            Spacer(Modifier.height(Space.s2))
            Stagger(1) { Text("Collegamenti e permessi sono rimasti come prima.", style = CanoneType.body, color = c.labelSecondary) }
            Spacer(Modifier.height(Space.s6))
            Stagger(2) { CButton("Continua", onContinue, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) }
        }
    }
}
