package ai.neuroshield.app.listen

import ai.neuroshield.app.core.SyntheticVoiceMonitor
import ai.neuroshield.app.core.VoiceCheck
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * The EXPERIMENTAL synthetic-voice model shipped inside the app (assets/voice): AASIST-L fine-tuned on real vs
 * synthesized speech (ml/voice_spoof, metrics in assets/voice/model.json and docs). Runs only on the phone.
 *
 * The model file is checked against the SHA-256 written next to it before it is loaded.
 * Input: 4.04 s of 16 kHz mono audio as float (int16 / 32768). Output: P(synthetic) 0..1.
 */
class VoiceModel private constructor(private val session: OrtSession, val threshold: Double, val info: JSONObject) {
    private val env = OrtEnvironment.getEnvironment()

    @Synchronized fun score(window: FloatArray): Double {
        require(window.size == VoiceCheck.WINDOW)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, VoiceCheck.WINDOW.toLong())).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = r[0].value as Array<FloatArray>
                return out[0][0].toDouble()
            }
        }
    }

    /** Checks a decrypted WAV recording (16 kHz mono PCM16, 44-byte header), reading it piece by piece. */
    fun checkWav(f: File): VoiceCheck = java.io.RandomAccessFile(f, "r").use { raf ->
        val samples = ((raf.length() - 44) / 2).coerceAtLeast(0)
        val bytes = ByteArray(VoiceCheck.WINDOW * 2)
        SyntheticVoiceMonitor.checkPieces((samples / VoiceCheck.WINDOW).toInt(), threshold, 40, { w ->
            raf.seek(44L + w.toLong() * bytes.size); raf.readFully(bytes)
            FloatArray(VoiceCheck.WINDOW) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        }) { score(it) }
    }

    /** P(synthetic) for the first 4 seconds of a WAV file (the on-screen examples are exactly one piece long). */
    fun scoreWav(f: File): Double = java.io.RandomAccessFile(f, "r").use { raf ->
        val bytes = ByteArray(VoiceCheck.WINDOW * 2)
        raf.seek(44); raf.readFully(bytes)
        score(FloatArray(VoiceCheck.WINDOW) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f })
    }

    companion object {
        private const val DIR = "voice"
        private const val FILE = "saqtan_voice_aasist_l.onnx"
        @Volatile private var cached: VoiceModel? = null
        @Volatile private var failed = false

        /** true when this build carries the model (it is loaded lazily, off the main thread). */
        fun bundled(ctx: Context): Boolean = runCatching { ctx.assets.list(DIR)?.contains(FILE) == true }.getOrDefault(false)

        /** Loads the model once; null if it is missing, damaged or cannot run on this phone. Call off the main thread. */
        @Synchronized fun get(ctx: Context): VoiceModel? {
            cached?.let { return it }
            if (failed || !bundled(ctx)) return null
            return try {
                val info = JSONObject(ctx.assets.open("$DIR/model.json").bufferedReader().readText())
                val bytes = ctx.assets.open("$DIR/$FILE").use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(sha.equals(info.getString("sha256"), ignoreCase = true)) { "model file does not match its SHA-256" }
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val s = OrtEnvironment.getEnvironment().createSession(bytes, opts)
                VoiceModel(s, info.getDouble("threshold"), info).also { cached = it }
            } catch (e: Exception) {
                failed = true
                null
            }
        }
    }
}
