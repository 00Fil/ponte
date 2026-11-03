package it.ponte

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.delay
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class Router(val ctx: Context, val prefs: Prefs) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val links = CopyOnWriteArrayList<Link>()
    private val seen: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 4000
        },
    )

    val hubState = MutableStateFlow("Non configurato")
    private val hubDevices = MutableStateFlow<List<Device>>(emptyList())
    private val lanPeers = MutableStateFlow<Map<String, Device>>(emptyMap())

    /** Tutti gli altri dispositivi visibili, dalla rete locale o dal server. La rete locale vince. */
    val devices: StateFlow<List<Device>> = combine(hubDevices, lanPeers) { hub, lan ->
        (lan.values + hub.filter { it.id !in lan }).sortedBy { it.name.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val deviceId: String get() = prefs.deviceId

    val clipboard = ClipboardSync(ctx, this)
    val files = FileStore(ctx, this)
    val finance = Finance(ctx, this)
    val plan = Plan(ctx, this)
    val audio = AudioSink(this)
    val headphones = Headphones(ctx, this)
    val media = MediaSwitch(ctx, this)
    val clipLog = ClipLogWatcher(ctx, this)
    val updater = Updater(ctx, this)
    val backup = Backup(ctx, this)
    private val hub = HubLink(this)
    private val lan = LanMesh(this)
    val bt = BtMesh(ctx, this)
    val p2p = P2pMesh(ctx, this)
    private val shots = ScreenshotWatcher(ctx, this)
    private var multicast: WifiManager.MulticastLock? = null

    /** A schermo spento si risparmia: niente blocco multicast e annunci in rete locale molto più rari. */
    @Volatile var screenOn = true
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) { headphones.onUserInput(); return }
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (screenOn) {
                multicast?.let { if (!it.isHeld) it.acquire() }
                scope.launch { syncAll() }
            } else {
                multicast?.let { if (it.isHeld) it.release() }
            }
        }
    }

    fun start() {
        clipboard.start()
        headphones.start()
        media.start()
        clipLog.start()
        bt.start()
        updater.start()
        finance.start()
        plan.start()
        p2p.start()
        screenOn = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        multicast = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("ponte").apply { setReferenceCounted(false); if (screenOn) acquire() }
        ctx.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        // Rete di sicurezza: l'arrivo istantaneo passa dal collegamento sempre aperto,
        // questo recupera solo ciò che si fosse perso (telefono spento, rete assente).
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                syncAll()
            }
        }
        reconfigure()
    }

    suspend fun syncAll() {
        files.catchUp()
        clipboard.catchUp()
        finance.push()
        plan.push()
    }

    /** Da chiamare dopo ogni cambio di impostazioni. */
    fun reconfigure() {
        hub.restart()
        if (prefs.lanEnabled && prefs.token.isNotEmpty()) lan.restart() else lan.stop()
        if (prefs.screenshots) shots.start() else shots.stop()
    }

    fun caps(): List<String> = buildList {
        add("clip"); add("files"); add("audio-sink")
        if (prefs.notifications) add("notif-src")
    }

    fun hello(): JSONObject = newMsg(
        "hello",
        "from" to deviceId, "name" to prefs.name, "platform" to "android", "caps" to JSONArray(caps()),
        "addrs" to JSONArray(localAddrs()), "port" to LAN_TCP_PORT,
    )

    /** Indirizzi IPv4 di questo dispositivo, prima quelli della rete privata Headscale/Tailscale (100.64.0.0/10). */
    fun localAddrs(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .sortedBy { if (isTailnet(it)) 0 else 1 }
            .take(8)
    } catch (_: Exception) {
        emptyList()
    }

    fun helloLan(): JSONObject = hello().put("proof", Lan.proof(prefs.token, deviceId))

    // ---- collegamenti ----

    fun addLink(l: Link) {
        l.peerId?.let { id -> links.filter { it.peerId == id }.forEach { it.close(); links.remove(it) } }
        links.add(l)
    }

    fun removeLink(l: Link) {
        links.remove(l)
        if (l.kind != "hub") l.peerId?.let { id ->
            if (links.none { it.peerId == id && it.kind != "hub" }) lanPeers.value = lanPeers.value - id
        }
        if (l.kind == "hub") hubDevices.value = emptyList()
    }

    fun hasLanLink(id: String) = links.any { it.kind == "lan" && it.peerId == id }

    /** Né server né rete locale: restano Bluetooth e Wi-Fi Direct. */
    fun isolated() = hubState.value != "Connesso" && links.none { it.kind == "lan" }
    fun closeLanLinks() = links.filter { it.kind == "lan" }.forEach { it.close(); removeLink(it) }
    fun lanPeerSeen(d: Device) { lanPeers.value = lanPeers.value + (d.id to d) }
    fun maxQueued(): Long = links.maxOfOrNull { it.queued() } ?: 0L

    fun onHubConnected() {
        scope.launch { syncAll() }
    }

    // ---- invio e ricezione ----

    fun send(m: JSONObject) {
        m.put("from", deviceId)
        seen[m.getString("id")] = true
        val text = m.toString()
        val to = m.optString("to").ifEmpty { null }
        val hubUp = links.any { it.kind == "hub" }
        // Il Bluetooth è lento: si usa solo quando quel dispositivo non è raggiungibile in altro modo.
        val usable = links.filter { l ->
            l.kind != "bt" || (!hubUp && links.none { it.kind == "lan" && it.peerId == l.peerId })
        }
        val targets = if (to != null) {
            usable.filter { it.peerId == to }.ifEmpty { usable.filter { it.kind == "hub" } }
        } else {
            usable
        }
        // Le scritture su socket non possono stare sul thread principale (NetworkOnMainThreadException).
        sender.execute { for (l in targets) try { l.sendJson(text) } catch (_: Exception) {} }
    }

    fun onText(text: String, link: Link) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val id = m.optString("id")
        val t = m.optString("t")
        if (id.isEmpty() || t.isEmpty()) return
        if (seen.put(id, true) != null) return
        val to = m.optString("to")
        if (to.isNotEmpty() && to != deviceId) return
        // Un messaggio che manda in errore un modulo non deve far cadere il collegamento.
        try { dispatch(m, t, link) } catch (e: Exception) { android.util.Log.w("Ponte", "messaggio $t", e) }
    }

    private fun dispatch(m: JSONObject, t: String, link: Link) {
        when (t) {
            "devices" -> if (link.kind == "hub") {
                val a = m.optJSONArray("list") ?: return
                hubDevices.value = (0 until a.length()).mapNotNull { i ->
                    val d = a.optJSONObject(i) ?: return@mapNotNull null
                    if (d.optString("id") == deviceId) null
                    else Device(d.optString("id"), d.optString("name"), d.optString("platform"), parseCaps(d.optJSONArray("caps")), "server")
                }
                // Il server fa da rubrica: con gli indirizzi diretti ci si collega senza passare da lui.
                for (i in 0 until a.length()) {
                    val d = a.optJSONObject(i) ?: continue
                    val id = d.optString("id")
                    val addrs = d.optJSONArray("addrs") ?: continue
                    if (id == deviceId || addrs.length() == 0) continue
                    lan.dial(id, (0 until addrs.length()).map { addrs.optString(it) }, d.optInt("port", LAN_TCP_PORT))
                }
            }
            "clip" -> clipboard.onRemote(m.optString("text"), m.optString("from"))
            "file.begin", "file.chunk", "file.end" -> files.onMessage(m)
            "audio.start" -> audio.onStart(m)
            "audio.stop" -> audio.onStop(m)
            "bt.claim" -> headphones.onClaim(m)
            "bt.state" -> headphones.onState(m)
            "focus" -> headphones.onFocus(m)
            "audio.lock" -> headphones.onLock(m)
            "media.take" -> media.onTake(m)
            "media.state" -> media.onState(m)
            "media.pause" -> media.onPauseReq()
            "media.cmd" -> media.onCmd(m)
            "fin" -> finance.onRemote(m.optJSONObject("doc"))
            "plan" -> plan.onRemote(m.optJSONObject("doc"))
        }
    }

    fun dialHost(host: String) = lan.dialHost(host)

    fun onAudio(b: ByteArray) = audio.onPacket(b)

    fun deviceName(id: String) = devices.value.firstOrNull { it.id == id }?.name ?: "un altro dispositivo"
}
