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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

private class TcpLink(private val r: Router, private val s: Socket) : Link {
    override val kind = "lan"
    override var peerId: String? = null
    private val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
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
    override fun close() { try { s.close() } catch (_: IOException) {} }

    fun run() {
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            write(0, r.helloLan().toString().toByteArray())
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            while (true) {
                val len = inp.readInt()
                if (len < 1 || len > 1_048_576) break
                val type = inp.readUnsignedByte()
                val buf = ByteArray(len - 1)
                inp.readFully(buf)
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
        peerId = from
        r.addLink(this)
        r.lanPeerSeen(Device(from, m.optString("name"), m.optString("platform"), parseCaps(m.optJSONArray("caps")), "lan"))
        return true
    }
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
                r.scope.launch { TcpLink(r, s).run() }
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

    private suspend fun announce() {
        while (r.scope.isActive) {
            val sock = udp
            if (sock != null && !sock.isClosed) {
                val data = JSONObject().put("p", "ponte").put("id", r.deviceId).put("name", r.prefs.name).put("port", LAN_TCP_PORT)
                    .toString().toByteArray()
                try {
                    sock.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), LAN_UDP_PORT))
                } catch (_: IOException) {
                }
            }
            delay(5_000)
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
                if (r.deviceId >= id || !connecting.add(id)) continue
                val host = p.address.hostAddress ?: continue
                val port = m.optInt("port", LAN_TCP_PORT)
                r.scope.launch {
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress(host, port), 3_000)
                        TcpLink(r, s).run()
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
