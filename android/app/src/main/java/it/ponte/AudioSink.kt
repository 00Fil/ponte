package it.ponte

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Riceve l'audio di un altro dispositivo (PCM 16 bit, 48 kHz, stereo) e lo suona sull'uscita attiva,
 * quindi nelle cuffie collegate a questo dispositivo.
 */
class AudioSink(private val r: Router) {
    private class Stream(val track: AudioTrack, val from: String) {
        var packets = 0
        var playing = false
    }

    private val streams = ConcurrentHashMap<Int, Stream>()

    /** Id del dispositivo da cui sta arrivando l'audio, o null. */
    val activeSource = MutableStateFlow<String?>(null)

    fun onStart(m: JSONObject) {
        val sid = m.optInt("sid", -1)
        if (sid < 0) return
        stopAll()
        val minBuf = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, PACKET_BYTES * 16))
            .build()
        val from = m.optString("from")
        streams[sid] = Stream(track, from)
        activeSource.value = from
    }

    fun onStop(m: JSONObject) {
        streams.remove(m.optInt("sid", -1))?.track?.let { runCatching { it.stop(); it.release() } }
        if (streams.isEmpty()) activeSource.value = null
    }

    fun onPacket(b: ByteArray) {
        if (b.size <= 4) return
        val sid = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        val st = streams[sid] ?: return
        // Scrittura non bloccante: se il buffer è pieno il pacchetto si scarta, così la latenza non si accumula.
        st.track.write(b, 4, b.size - 4, AudioTrack.WRITE_NON_BLOCKING)
        // Si parte dopo circa 100 ms di audio accumulato, per assorbire i ritardi della rete.
        if (!st.playing && ++st.packets >= 5) {
            st.playing = true
            st.track.play()
        }
    }

    fun stopAll() {
        for ((_, s) in streams) runCatching { s.track.stop(); s.track.release() }
        streams.clear()
        activeSource.value = null
    }

    /** Chiede a una sorgente (di solito un PC) di mandare il suo audio a questo dispositivo. */
    fun listenTo(sourceId: String) = r.send(newMsg("audio.route", "to" to sourceId, "sink" to r.deviceId))

    fun stopListening(sourceId: String) {
        r.send(newMsg("audio.route", "to" to sourceId, "sink" to null))
        stopAll()
    }

    companion object {
        const val PACKET_BYTES = 3840
    }
}
