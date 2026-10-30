package it.ponte

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Vedi PROTOCOLLO.md nella radice del progetto. */
const val LAN_TCP_PORT = 47800
const val LAN_UDP_PORT = 47801
const val CHUNK_BYTES = 48 * 1024
const val AUDIO_RATE = 48000

fun newMsg(t: String, vararg extra: Pair<String, Any?>): JSONObject {
    val o = JSONObject().put("t", t).put("id", UUID.randomUUID().toString()).put("ts", System.currentTimeMillis())
    for ((k, v) in extra) o.put(k, v ?: JSONObject.NULL)
    return o
}

data class Device(
    val id: String,
    val name: String,
    val platform: String,
    val caps: Set<String>,
    val via: String, // "lan" oppure "server"
)

fun parseCaps(a: JSONArray?): Set<String> = buildSet { if (a != null) for (i in 0 until a.length()) add(a.optString(i)) }

/** Un collegamento verso un altro dispositivo (rete locale) o verso il server. */
interface Link {
    val kind: String // "hub" o "lan"
    var peerId: String?
    fun sendJson(s: String): Boolean
    fun queued(): Long
    fun close()
}

object Lan {
    fun proof(token: String, id: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        return mac.doFinal("ponte-lan|$id".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun verify(token: String, id: String, proof: String): Boolean =
        token.isNotEmpty() && MessageDigest.isEqual(proof(token, id).toByteArray(), proof.toByteArray())
}
