package it.ponte.car

import android.content.Intent
import android.graphics.Bitmap
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.ponte.Cams
import it.ponte.Notify
import it.ponte.Ponte
import it.ponte.PonteService
import it.ponte.R
import it.ponte.RemoteMedia
import it.ponte.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Relay su Android Auto: la stessa Home del telefono, con modelli adatti alla guida. */
class RelayCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            try { PonteService.start(carContext) } catch (_: Exception) {}
            return HomeCar(carContext)
        }
    }
}

private val BLUE = CarColor.createCustom(0xFF2F5FFF.toInt(), 0xFF5A8CFF.toInt())
private val AMBER = CarColor.createCustom(0xFFE09020.toInt(), 0xFFFFB347.toInt())

private fun CarContext.icon(res: Int, tint: CarColor = BLUE): CarIcon = CarIcon.Builder(IconCompat.createWithResource(this, res)).setTint(tint).build()
private fun pic(b: Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(b)).build()
private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))
private fun euro(cents: Long) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(cents / 100.0)
private fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
private fun day(t: Long): String {
    val d = dayStart(t); val today = dayStart(System.currentTimeMillis())
    return when (d) { today -> "Oggi"; today + 86_400_000L -> "Domani"; else -> SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date(t)) }
}
private fun ticker(ms: Long): Flow<Unit> = flow { while (true) { emit(Unit); delay(ms) } }
private fun ListTemplate.Builder.title(t: String) = setTitle(t).setHeaderAction(Action.BACK)

/** Schermata che si ridisegna quando cambiano i dati che guarda. */
abstract class LiveCar(ctx: CarContext, vararg flows: Flow<*>) : Screen(ctx) {
    protected val r: Router = Ponte.ensure(ctx)
    protected val maxRows: Int = try {
        if (ctx.carAppApiLevel >= 2) ctx.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST) else 6
    } catch (_: Exception) { 6 }

    init {
        if (flows.isNotEmpty()) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { merge(*flows).conflate().collect { invalidate(); delay(1_000) } }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Home

class HomeCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).plan.state, Ponte.ensure(ctx).media.media, Ponte.ensure(ctx).finance.state, Ponte.ensure(ctx).devices, Cams.alerts, ticker(30_000)) {
    override fun onGetTemplate(): Template {
        val now = System.currentTimeMillis()
        val p = r.plan.state.value
        val list = ItemList.Builder()
        fun item(title: String, text: String, res: Int, tint: CarColor, open: () -> Screen) = list.addItem(
            GridItem.Builder().setTitle(title).setText(text.ifEmpty { " " }).setImage(carContext.icon(res, tint), GridItem.IMAGE_TYPE_ICON)
                .setOnClickListener { screenManager.push(open()) }.build(),
        )

        val m = playing(r)
        item("Audio", m?.title?.ifEmpty { null } ?: "Fermo", R.drawable.ic_n_audio, BLUE) { AudioCar(carContext) }
        val t = p.timer
        item("Studio", if (t.running && t.endAt > now) "${phase(t.phase)} · ${((t.endAt - now) / 60_000) + 1} min" else "Pomodoro", R.drawable.ic_n_timer, AMBER) { StudioCar(carContext) }
        val ev = upcoming(r).firstOrNull()
        item("Agenda", ev?.let { "${if (it.allDay) day(it.start) else hm(it.start)} ${it.title}" } ?: "Libero", R.drawable.ic_n_event, BLUE) { AgendaCar(carContext) }
        val open = p.tasks.count { !it.done }
        item("To-do", if (open > 0) "$open da fare" else "Tutto fatto", R.drawable.ic_c_todo, BLUE) { TodoCar(carContext) }
        item("Saldo", euro(r.finance.state.value.totalCents), R.drawable.ic_c_wallet, AMBER) { SaldoCar(carContext) }
        val a = Cams.alerts.value.maxByOrNull { it.time }
        item("Cam", a?.let { "${Cams.kind(it.type)} · ${hm(it.time * 1000)}" } ?: "Dal vivo", R.drawable.ic_c_cam, BLUE) { CamsCar(carContext) }
        item("Dispositivi", "${r.devices.value.size} collegati", R.drawable.ic_c_devices, BLUE) { DevicesCar(carContext) }

        return GridTemplate.Builder().setTitle("Relay").setHeaderAction(Action.APP_ICON).setSingleList(list.build()).build()
    }
}

private fun phase(p: String) = when (p) { "short" -> "Pausa breve"; "long" -> "Pausa lunga"; else -> "Concentrazione" }

private fun playing(r: Router): RemoteMedia? = r.media.media.value.values.let { v -> v.filter { it.playing }.maxByOrNull { it.at } ?: v.filter { it.title.isNotEmpty() }.maxByOrNull { it.at } }

private class Up(val title: String, val start: Long, val end: Long, val allDay: Boolean, val place: String)

private fun upcoming(r: Router): List<Up> {
    val now = System.currentTimeMillis()
    val until = dayStart(now) + 2 * 86_400_000L
    val p = r.plan.state.value
    val own = p.events.map { Up(it.title, it.start, it.end, it.allDay, it.place) }
    val ext = p.ext.map { Up(it.title, it.start, it.end, it.allDay, it.place) }
    return (own + ext).filter { it.end.coerceAtLeast(it.start) > now && it.start < until }.distinctBy { it.title to it.start }.sortedBy { it.start }
}

// ------------------------------------------------------------------------------------------------ Audio

class AudioCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).media.media) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Niente in riproduzione")
        r.media.media.value.values.sortedWith(compareBy({ !it.playing }, { -it.at })).take(maxRows).forEach { m ->
            list.addItem(
                Row.Builder().setTitle(m.title.ifEmpty { "Senza titolo" })
                    .addText(listOf(m.artist, if (m.id == r.deviceId) "Questo telefono" else r.deviceName(m.id)).filter { it.isNotEmpty() }.joinToString(" · "))
                    .setImage(carContext.icon(if (m.playing) R.drawable.ic_c_pause else R.drawable.ic_c_play))
                    .setOnClickListener { screenManager.push(PlayerCar(carContext, m.id)) }.build(),
            )
        }
        return ListTemplate.Builder().title("Audio").setSingleList(list.build()).build()
    }
}

class PlayerCar(ctx: CarContext, private val id: String) : LiveCar(ctx, Ponte.ensure(ctx).media.media) {
    override fun onGetTemplate(): Template {
        val m = r.media.media.value[id]
        val pane = Pane.Builder()
        if (m == null) pane.addRow(Row.Builder().setTitle("Fermo").build())
        else {
            pane.addRow(Row.Builder().setTitle(m.title.ifEmpty { "Senza titolo" }).addText(m.artist.ifEmpty { " " }).build())
            pane.addRow(Row.Builder().setTitle(if (id == r.deviceId) "Questo telefono" else r.deviceName(id)).addText(if (m.playing) "In riproduzione" else "In pausa").build())
            pane.addAction(
                Action.Builder().setTitle(if (m.playing) "Pausa" else "Riproduci").setIcon(carContext.icon(if (m.playing) R.drawable.ic_c_pause else R.drawable.ic_c_play, CarColor.DEFAULT))
                    .setFlags(Action.FLAG_PRIMARY).setOnClickListener { r.media.cmd(id, if (m.playing) "pause" else "play") }.build(),
            )
            pane.addAction(Action.Builder().setTitle("Avanti").setIcon(carContext.icon(R.drawable.ic_c_next, CarColor.DEFAULT)).setOnClickListener { r.media.cmd(id, "next") }.build())
        }
        val strip = ActionStrip.Builder().addAction(Action.Builder().setTitle("Indietro").setOnClickListener { r.media.cmd(id, "prev") }.build()).build()
        return PaneTemplate.Builder(pane.build()).setTitle("Audio").setHeaderAction(Action.BACK).setActionStrip(strip).build()
    }
}

// ------------------------------------------------------------------------------------------------ Studio

class StudioCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).plan.state, ticker(15_000)) {
    override fun onGetTemplate(): Template {
        val now = System.currentTimeMillis()
        val p = r.plan.state.value
        val t = p.timer
        val on = t.running && t.endAt > now
        val mins = when (t.phase) { "short" -> p.cfg("short", 5); "long" -> p.cfg("long", 15); else -> p.cfg("focus", 25) }
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(phase(t.phase)).addText(if (on) "${((t.endAt - now) / 60_000) + 1} min · finisce alle ${hm(t.endAt)}" else "$mins min").build())
        if (t.subject.isNotEmpty()) p.subjects.firstOrNull { it.id == t.subject }?.let { pane.addRow(Row.Builder().setTitle(it.name).build()) }
        pane.addRow(Row.Builder().setTitle("Oggi").addText("${p.sessions.filter { dayStart(it.start) == dayStart(now) }.sumOf { it.minutes }} min di studio").build())
        pane.addAction(
            Action.Builder().setTitle(if (on) "Ferma" else "Avvia").setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { if (on) r.plan.timerStop() else r.plan.timerStart(t.subject) }.build(),
        )
        if (!on) pane.addAction(Action.Builder().setTitle(if (t.phase == "focus") "Pausa" else "Concentrazione").setOnClickListener { r.plan.timerPhase(if (t.phase == "focus") "short" else "focus") }.build())
        return PaneTemplate.Builder(pane.build()).setTitle("Studio").setHeaderAction(Action.BACK).build()
    }
}

// ------------------------------------------------------------------------------------------------ Agenda e To-do

class AgendaCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).plan.state, ticker(60_000)) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Niente oggi e domani")
        upcoming(r).take(maxRows).forEach { e ->
            val time = if (e.allDay) day(e.start) else "${day(e.start)} ${hm(e.start)}–${hm(e.end)}"
            list.addItem(Row.Builder().setTitle(e.title.ifEmpty { "Evento" }).addText(listOf(time, e.place).filter { it.isNotEmpty() }.joinToString(" · ")).build())
        }
        return ListTemplate.Builder().title("Agenda").setSingleList(list.build()).build()
    }
}

class TodoCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).plan.state) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Tutto fatto")
        r.plan.state.value.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title })).take(maxRows).forEach { t ->
            val row = Row.Builder().setTitle(t.title.ifEmpty { "—" })
            if (t.due > 0) row.addText(day(t.due))
            row.setToggle(Toggle.Builder { done -> r.plan.saveTask(t.copy(done = done)) }.setChecked(false).build())
            list.addItem(row.build())
        }
        return ListTemplate.Builder().title("To-do").setSingleList(list.build()).build()
    }
}

// ------------------------------------------------------------------------------------------------ Saldo

class SaldoCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).finance.state) {
    override fun onGetTemplate(): Template {
        val m = r.finance.state.value
        val month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis.let { dayStart(it) }
        val spent = -m.txs.filter { it.ts >= month && it.cents < 0 }.sumOf { it.cents }
        val inc = m.txs.filter { it.ts >= month && it.cents > 0 }.sumOf { it.cents }
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Saldo").addText(euro(m.totalCents)).build())
            .addRow(Row.Builder().setTitle("Questo mese").addText("Uscite ${euro(spent)} · Entrate ${euro(inc)}").build())
        m.txs.sortedByDescending { it.ts }.take(2).forEach { t ->
            pane.addRow(Row.Builder().setTitle(t.note.ifEmpty { t.cat }).addText("${euro(t.cents)} · ${day(t.ts)}").build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("Saldo").setHeaderAction(Action.BACK).build()
    }
}

// ------------------------------------------------------------------------------------------------ Cam

/** Foto degli avvisi già scaricate (url → immagine). */
private val alertPics = ConcurrentHashMap<String, Bitmap>()

class CamsCar(ctx: CarContext) : LiveCar(ctx, Cams.alerts, Cams.live, Cams.seen) {
    private val loading = ConcurrentHashMap.newKeySet<String>()

    init { Cams.ensure(r) }

    private fun load(url: String) {
        if (url.isEmpty() || alertPics.containsKey(url) || !loading.add(url)) return
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) {
                try {
                    okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) android.graphics.BitmapFactory.decodeStream(it.body?.byteStream()) else null }
                } catch (_: Exception) { null }
            }
            if (b != null) { alertPics[url] = Bitmap.createScaledBitmap(b, 320, (320L * b.height / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true); invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val cams = (if (Cams.seen.value.isNotEmpty()) Cams.seen.value else Cams.live.value).sorted().filter { it !in Cams.hidden.value }
        val live = ItemList.Builder().setNoItemsMessage("Collegamento…")
        cams.take(maxRows).forEach { ch ->
            live.addItem(Row.Builder().setTitle("Cam ${ch + 1}").setImage(carContext.icon(R.drawable.ic_c_cam)).setOnClickListener { screenManager.push(CamCar(carContext, ch)) }.setBrowsable(true).build())
        }
        val alerts = ItemList.Builder().setNoItemsMessage("Nessun avviso")
        Cams.alerts.value.sortedByDescending { it.time }.take(maxRows).forEach { a ->
            load(a.img)
            val row = Row.Builder().setTitle(Cams.kind(a.type)).addText("Cam ${a.channel + 1} · ${day(a.time * 1000)} ${hm(a.time * 1000)}")
            alertPics[a.img]?.let { row.setImage(pic(it), Row.IMAGE_TYPE_LARGE) } ?: row.setImage(carContext.icon(R.drawable.ic_c_cam, AMBER))
            row.setOnClickListener { screenManager.push(CamCar(carContext, a.channel)) }
            alerts.addItem(row.build())
        }
        val b = ListTemplate.Builder().title("Cam")
        val l = live.build(); val a = alerts.build()
        if (l.items.isEmpty() && a.items.isEmpty()) return b.setSingleList(l).build()
        if (l.items.isNotEmpty()) b.addSectionedList(SectionedItemList.create(l, "Dal vivo"))
        if (a.items.isNotEmpty()) b.addSectionedList(SectionedItemList.create(a, "Avvisi"))
        return b.build()
    }
}

/** Una cam: l'immagine si aggiorna ogni pochi secondi (il video in movimento in auto non è consentito). */
class CamCar(ctx: CarContext, private val ch: Int) : LiveCar(ctx, ticker(2_000)) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { Cams.ensure(r); Cams.resume(); Cams.enter(ch) }
            override fun onStop(owner: LifecycleOwner) { Cams.leave(ch); if (!Notify.foreground) Cams.pause() }
        })
    }

    override fun onGetTemplate(): Template {
        val frame = Cams.frame(ch).value?.let { try { it.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null } }
        val pane = Pane.Builder()
        if (frame != null && carContext.carAppApiLevel >= 4) pane.setImage(pic(frame))
        pane.addRow(Row.Builder().setTitle("Cam ${ch + 1}").addText(if (frame == null) "Collegamento…" else "Dal vivo · ${hm(System.currentTimeMillis())}").apply {
            if (frame != null && carContext.carAppApiLevel < 4) setImage(pic(frame), Row.IMAGE_TYPE_LARGE)
        }.build())
        pane.addAction(Action.Builder().setTitle("Luce").setIcon(carContext.icon(R.drawable.ic_c_light, CarColor.DEFAULT)).setFlags(Action.FLAG_PRIMARY).setOnClickListener { Cams.light(ch, "light") }.build())
        pane.addAction(Action.Builder().setTitle("Automatico").setOnClickListener { Cams.light(ch, "smart") }.build())
        return PaneTemplate.Builder(pane.build()).setTitle("Cam ${ch + 1}").setHeaderAction(Action.BACK).build()
    }
}

// ------------------------------------------------------------------------------------------------ Dispositivi

class DevicesCar(ctx: CarContext) : LiveCar(ctx, Ponte.ensure(ctx).devices, Ponte.ensure(ctx).media.media) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Nessun dispositivo collegato")
        val playing = r.media.playingNow.value
        r.devices.value.take(maxRows).forEach { d ->
            val plat = when (d.platform) { "windows" -> "Windows"; "android" -> "Android"; else -> d.platform }
            val via = if (d.via == "lan") "rete di casa" else "internet"
            list.addItem(Row.Builder().setTitle(d.name).addText(listOfNotNull(plat, via, playing[d.id]?.let { "♪ " + it.ifEmpty { "audio" } }).joinToString(" · ")).build())
        }
        return ListTemplate.Builder().title("Dispositivi").setSingleList(list.build()).build()
    }
}
