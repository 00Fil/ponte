package it.ponte

import android.content.Context
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
    val audio = AudioSink(this)
    val headphones = Headphones(ctx, this)
    private val hub = HubLink(this)
    private val lan = LanMesh(this)
    private val shots = ScreenshotWatcher(ctx, this)
    private var multicast: WifiManager.MulticastLock? = null

    fun start() {
        clipboard.start()
        headphones.start()
        multicast = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("ponte").apply { setReferenceCounted(false); acquire() }
        reconfigure()
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
    )

    fun helloLan(): JSONObject = hello().put("proof", Lan.proof(prefs.token, deviceId))

    // ---- collegamenti ----

    fun addLink(l: Link) {
        l.peerId?.let { id -> links.filter { it.peerId == id }.forEach { it.close(); links.remove(it) } }
        links.add(l)
    }

    fun removeLink(l: Link) {
        links.remove(l)
        if (l.kind == "lan") l.peerId?.let { id -> lanPeers.value = lanPeers.value - id }
        if (l.kind == "hub") hubDevices.value = emptyList()
    }

    fun hasLanLink(id: String) = links.any { it.kind == "lan" && it.peerId == id }
    fun closeLanLinks() = links.filter { it.kind == "lan" }.forEach { it.close(); removeLink(it) }
    fun lanPeerSeen(d: Device) { lanPeers.value = lanPeers.value + (d.id to d) }
    fun maxQueued(): Long = links.maxOfOrNull { it.queued() } ?: 0L

    fun onHubConnected() {
        scope.launch { files.catchUp() }
    }

    // ---- invio e ricezione ----

    fun send(m: JSONObject) {
        m.put("from", deviceId)
        seen[m.getString("id")] = true
        val text = m.toString()
        val to = m.optString("to").ifEmpty { null }
        val targets = if (to != null) {
            links.filter { it.peerId == to }.ifEmpty { links.filter { it.kind == "hub" } }
        } else {
            links.toList()
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
        when (t) {
            "devices" -> if (link.kind == "hub") {
                val a = m.optJSONArray("list") ?: return
                hubDevices.value = (0 until a.length()).mapNotNull { i ->
                    val d = a.optJSONObject(i) ?: return@mapNotNull null
                    if (d.optString("id") == deviceId) null
                    else Device(d.optString("id"), d.optString("name"), d.optString("platform"), parseCaps(d.optJSONArray("caps")), "server")
                }
            }
            "clip" -> clipboard.onRemote(m.optString("text"), m.optString("from"))
            "file.begin", "file.chunk", "file.end" -> files.onMessage(m)
            "audio.start" -> audio.onStart(m)
            "audio.stop" -> audio.onStop(m)
            "bt.claim" -> headphones.onClaim(m)
        }
    }

    fun onAudio(b: ByteArray) = audio.onPacket(b)

    fun deviceName(id: String) = devices.value.firstOrNull { it.id == id }?.name ?: "un altro dispositivo"
}
