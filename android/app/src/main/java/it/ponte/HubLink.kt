package it.ponte

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

private class WsLink(private val ws: WebSocket) : Link {
    override val kind = "hub"
    override var peerId: String? = null
    override fun sendJson(s: String) = ws.send(s)
    override fun queued() = ws.queueSize()
    override fun close() { ws.close(1000, null) }
}

/** Collegamento con il server sul VPS. Si riconnette da solo, con attesa crescente. */
class HubLink(private val r: Router) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var job: Job? = null
    private var current: WsLink? = null

    @Synchronized
    fun restart() {
        job?.cancel()
        current?.close()
        current = null
        job = r.scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val url = r.prefs.wsUrl()
                if (url == null || r.prefs.token.isEmpty()) {
                    r.hubState.value = "Non configurato"
                    delay(3_000)
                    continue
                }
                val closed = CompletableDeferred<Unit>()
                r.hubState.value = "Connessione in corso"
                val req = try {
                    Request.Builder().url(url).header("Authorization", "Bearer ${r.prefs.token}").build()
                } catch (_: IllegalArgumentException) {
                    r.hubState.value = "Indirizzo non valido"
                    delay(5_000)
                    continue
                }
                var link: WsLink? = null
                client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        link = WsLink(webSocket).also { current = it; r.addLink(it) }
                        webSocket.send(r.hello().toString())
                        r.hubState.value = "Connesso"
                        backoff = 1_000L
                        r.onHubConnected()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        link?.let { r.onText(text, it) }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        r.onAudio(bytes.toByteArray())
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        r.hubState.value = if (response?.code == 401) "Token non accettato" else "Server non raggiungibile"
                        closed.complete(Unit)
                    }
                })
                try {
                    closed.await()
                } finally {
                    // Anche se la coroutine viene annullata (riavvio dalle impostazioni): niente collegamenti orfani.
                    link?.let { r.removeLink(it); it.close() }
                }
                if (r.hubState.value == "Connesso") r.hubState.value = "Disconnesso"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }
}
