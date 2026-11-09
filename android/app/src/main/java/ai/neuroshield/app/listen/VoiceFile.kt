package ai.neuroshield.app.listen

import ai.neuroshield.app.app
import ai.neuroshield.app.core.PcmConverter
import ai.neuroshield.app.core.VoiceInput
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/** Offline speech recognition (Vosk) of a stream of 16 kHz mono PCM: a voice message or the user's own retelling. */
class Transcriber(dir: File) : Closeable {
    private val model: Model
    private val rec: Recognizer
    private val parts = StringBuilder()

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(dir.absolutePath)
        rec = try { Recognizer(model, ListenService.SAMPLE_RATE.toFloat()) } catch (e: Exception) { model.close(); throw e }
    }

    fun feed(pcm: ShortArray, n: Int) {
        if (rec.acceptWaveForm(pcm, n)) add(JSONObject(rec.result).optString("text"))
    }

    /** The text recognised so far, including the phrase still being spoken. */
    fun live(): String = (parts.toString() + " " + JSONObject(rec.partialResult).optString("partial")).trim()

    fun finish(): String { add(JSONObject(rec.finalResult).optString("text")); return parts.toString().trim() }

    private fun add(t: String) { if (t.isNotBlank()) parts.append(t.trim()).append(' ') }

    override fun close() { runCatching { rec.close() }; runCatching { model.close() } }

    companion object {
        /** Languages whose recognition model is on the phone. */
        fun ready(ctx: Context): List<SpeechModel> = SpeechModels.all.filter { SpeechModels.modelDir(ctx, it.lang) != null }

        /** The language to use: the one asked for if its model is on the phone, else the user's speech language, else any. */
        fun pick(ctx: Context, wanted: String? = null): String? {
            val r = ready(ctx).map { it.lang }
            return listOfNotNull(wanted, ctx.app.prefs.speechLang).firstOrNull { it in r } ?: r.firstOrNull()
        }
    }
}

/** Decodes an audio file (Ogg/Opus voice notes, m4a, mp3, wav…) with the phone's own decoders. */
object AudioFileDecoder {
    class Rejected(val reason: String) : Exception(reason)

    fun size(ctx: Context, uri: Uri): Long? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /**
     * Streams the file as 16 kHz mono PCM16 into [sink], piece by piece. Returns the number of seconds decoded.
     * Throws [Rejected] for a file that is not accepted (scheme, size, length, no audio track).
     */
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = VoiceInput.MAX_SECONDS, cancelled: () -> Boolean = { false },
               sink: (ShortArray, Int) -> Unit): Int {
        if (!VoiceInput.acceptScheme(uri.scheme)) throw Rejected("scheme")
        if (!VoiceInput.acceptSize(size(ctx, uri))) throw Rejected("size")
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Rejected("no_audio")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var conv: PcmConverter? = null
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var idle = 0
            var total = 0L
            val limit = maxSeconds.toLong() * ListenService.SAMPLE_RATE
            while (true) {
                if (cancelled()) throw InterruptedException()
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        conv = null
                    }
                    o >= 0 -> {
                        idle = 0
                        val b = c.getOutputBuffer(o)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val pcm = if (float) {
                            val fb = b.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                        } else {
                            val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                            ShortArray(sb.remaining()).also { sb.get(it) }
                        }
                        c.releaseOutputBuffer(o, false)
                        val cv = conv ?: PcmConverter(rate, channels).also { conv = it }
                        val m = cv.push(pcm, pcm.size)
                        if (m.isNotEmpty()) { sink(m, m.size); total += m.size }
                        if (total > limit) throw Rejected("too_long")
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    inDone && ++idle > 600 -> break        // a decoder that never signals the end (~6 s of nothing)
                }
            }
            return (total / ListenService.SAMPLE_RATE).toInt()
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { ex.release() }
        }
    }
}
