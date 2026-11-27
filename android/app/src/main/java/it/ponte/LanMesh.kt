package it.ponte

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Collegamento diretto con un altro dispositivo su qualsiasi canale a flusso (TCP, Bluetooth RFCOMM).
 * Trame: [u32 BE lunghezza][u8 tipo][dati], tipo 0 = JSON, 1 = audio. Il primo messaggio è hello con proof.
 */
class StreamLink(
    private val r: Router,
    inp: InputStream,
    outp: OutputStream,
    private val closer: () -> Unit,
    override val kind: String,
    private val initiator: Boolean = false,
) : Link {
    override var peerId: String? = null
    private val out = DataOutputStream(BufferedOutputStream(outp))
    private val input = DataInputStream(BufferedInputStream(inp))
    private val lock = Any()

    private fun write(type: Int, payload: ByteArray): Boolean = try {
        synchronized(lock) {
            out.writeInt(payload.size + 1)
            out.writeByte(type)
            out.write(payload)
            out.flush()
        }
        true
    } catch (_: Exception) {
        close()
        false
    }

    override fun sendJson(s: String) = write(0, s.toByteArray())
    override fun queued() = 0L
    override fun close() { try { closer() } catch (_: Exception) {} }

    fun run() {
        try {
            write(0, r.helloLan().toString().toByteArray())
            while (true) {
                val len = input.readInt()
                if (len < 1 || len > 1_048_576) break
                val type = input.readUnsignedByte()
                val buf = ByteArray(len - 1)
                input.readFully(buf)
                if (peerId == null) {
                    if (type != 0 || !acceptHello(buf)) break
                } else if (type == 0) {
                    r.onText(String(buf), this)
                } else if (type == 1) {
                    r.onAudio(buf)
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
            r.removeLink(this)
        }
    }

    private fun acceptHello(buf: ByteArray): Boolean {
        val m = JSONObject(String(buf))
        val from = m.optString("from")
        if (m.optString("t") != "hello" || from.isEmpty() || from == r.deviceId) return false
        if (!Lan.verify(r.prefs.token, from, m.optString("proof"))) return false
        // Bluetooth tra due Android: si chiamano a vicenda; resta il collegamento aperto da chi ha l'id minore.
        if (kind == "bt" && initiator && m.optString("platform") == "android" && r.deviceId > from) return false
        peerId = from
        r.addLink(this)
        r.lanPeerSeen(Device(from, m.optString("name"), m.optString("platform"), parseCaps(m.optJSONArray("caps")), kind))
        return true
    }
}

fun tcpLink(r: Router, s: Socket): StreamLink {
    try { s.tcpNoDelay = true; s.keepAlive = true } catch (_: Exception) {}
    return StreamLink(r, s.getInputStream(), s.getOutputStream(), { s.close() }, "lan")
}

/** Scoperta in UDP broadcast e collegamenti TCP diretti tra i dispositivi di casa. */
class LanMesh(private val r: Router) {
    private var job: Job? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun restart() {
        stop()
        job = r.scope.launch {
            launch { accept() }
            launch { listen() }
            launch { announce() }
            launch { redialKnown() }
        }
    }

    /**
     * Collegamento diretto a un dispositivo di cui si conoscono gli indirizzi (dal server o dalla rubrica salvata).
     * Si prova prima l'indirizzo della rete privata: funziona anche senza Wi-Fi in comune ed è cifrato da WireGuard.
     */
    fun dial(id: String, addrs: List<String>, port: Int = LAN_TCP_PORT) {
        if (job == null || id == r.deviceId) return
        val clean = addrs.filter { it.isNotBlank() }.distinct().sortedBy { if (isTailnet(it)) 0 else 1 }
        if (clean.isEmpty()) return
        r.prefs.peers = r.prefs.peers + (id to clean)
        // Si collega solo chi ha l'id minore: nasce un solo collegamento per coppia.
        if (r.hasLanLink(id) || !connecting.add(id)) return
        val late = r.deviceId >= id
        r.scope.launch {
            try {
                if (late) { delay(8_000); if (r.hasLanLink(id)) return@launch }
                for (host in clean) {
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress(host, port), 3_000)
                    } catch (_: IOException) {
                        try { s.close() } catch (_: IOException) {}
                        continue
                    }
                    connecting.remove(id)
                    tcpLink(r, s).run()
                    return@launch
                }
            } finally {
                connecting.remove(id)
            }
        }
    }

    /** Collegamento a un indirizzo di cui non si conosce ancora il dispositivo (es. capo gruppo Wi-Fi Direct). */
    fun dialHost(host: String, port: Int = LAN_TCP_PORT) {
        if (job == null || !connecting.add("host:$host")) return
        r.scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5_000)
                tcpLink(r, s).run()
            } catch (_: IOException) {
            } finally {
                connecting.remove("host:$host")
            }
        }
    }

    private suspend fun redialKnown() {
        while (r.scope.isActive) {
            delay(if (r.screenOn) 60_000 else 300_000)
            r.prefs.peers.forEach { (id, addrs) -> if (!r.hasLanLink(id)) dial(id, addrs) }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        try { server?.close() } catch (_: IOException) {}
        try { udp?.close() } catch (_: IOException) {}
        r.closeLanLinks()
    }

    private fun accept() {
        try {
            val ss = ServerSocket(LAN_TCP_PORT).also { server = it }
            while (true) {
                val s = ss.accept()
                r.scope.launch { tcpLink(r, s).run() }
            }
        } catch (_: IOException) {
        }
    }

    private fun openUdp(): DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(LAN_UDP_PORT))
        }.also { udp = it }
    } catch (_: IOException) {
        null
    }

    /** Indirizzi di trasmissione di ogni scheda di rete attiva: il broadcast generico non esce da tutte. */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try { out += InetAddress.getByName("255.255.255.255") } catch (_: Exception) {}
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { n ->
                if (n.isUp && !n.isLoopback) n.interfaceAddresses.forEach { a -> a.broadcast?.let { out += it } }
            }
        } catch (_: Exception) {}
        return out.toList()
    }

    private suspend fun announce() {
        var n = 0
        while (r.scope.isActive) {
            val sock = udp
            if (sock == null || sock.isClosed) { delay(300); if (++n < 20) continue }
            n = 0
            if (sock != null && !sock.isClosed) {
                val data = JSONObject().put("p", "ponte").put("id", r.deviceId).put("name", r.prefs.name).put("port", LAN_TCP_PORT)
                    .toString().toByteArray()
                for (t in broadcastTargets()) {
                    try { sock.send(DatagramPacket(data, data.size, t, LAN_UDP_PORT)) } catch (_: IOException) {}
                }
            }
            delay(if (r.screenOn) 10_000 else 120_000)
        }
    }

    private fun listen() {
        val sock = openUdp() ?: return
        val buf = ByteArray(1024)
        try {
            while (!sock.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val m = try { JSONObject(String(p.data, 0, p.length)) } catch (_: Exception) { continue }
                if (m.optString("p") != "ponte") continue
                val id = m.optString("id")
                if (id.isEmpty() || id == r.deviceId || r.hasLanLink(id)) continue
                // Si collega solo chi ha l'id minore: nasce un solo collegamento per coppia.
                if (!connecting.add(id)) continue
                val late = r.deviceId >= id
                val host = p.address.hostAddress ?: continue
                val port = m.optInt("port", LAN_TCP_PORT)
                r.scope.launch {
                    try {
                        // Chi ha l'id maggiore aspetta: se l'altro non riesce a collegarsi (firewall) lo fa lui.
                        if (late) { delay(8_000); if (r.hasLanLink(id)) return@launch }
                        val s = Socket()
                        s.connect(InetSocketAddress(host, port), 3_000)
                        tcpLink(r, s).run()
                    } catch (_: IOException) {
                    } finally {
                        connecting.remove(id)
                    }
                }
            }
        } catch (_: IOException) {
        }
    }
}
