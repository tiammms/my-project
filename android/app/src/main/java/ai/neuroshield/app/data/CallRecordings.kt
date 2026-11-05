package ai.neuroshield.app.data

import android.content.Context
import android.util.Base64
import ai.neuroshield.app.core.ChunkedAead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Call recordings, only when the user turned on "Record calls" and only while Saqtan listens to a call.
 *
 * What is recorded: the phone's MICROPHONE (the other person is heard only on speakerphone; Android does not give
 * apps the call audio itself). Where: only in the app's private storage, encrypted (AES-256-GCM, a new key per
 * recording, that key sealed with the Keystore key of [LocalCrypto]). How long: [RETENTION_DAYS] days, or until the
 * user deletes it. Nothing is uploaded anywhere.
 */
object CallRecordings {
    const val RETENTION_DAYS = 30
    const val SAMPLE_RATE = 16_000
    /** One second of audio per encrypted chunk: if the app is killed, at most about a second is lost. */
    private const val CHUNK_BYTES = 2 * SAMPLE_RATE
    /** Ask the storage to persist the file every 10 chunks (≈10 s), so little is lost even if the phone switches off. */
    private const val SYNC_EVERY = 10
    private val ID = Regex("rec_[0-9]{10,16}")

    private fun dir(ctx: Context) = File(ctx.filesDir, "recordings").apply { mkdirs() }
    private fun data(ctx: Context, id: String) = File(dir(ctx), "$id.enc")
    private fun keyFile(ctx: Context, id: String) = File(dir(ctx), "$id.key")

    class Recording internal constructor(val id: String, private val writer: ChunkedAead.Writer) {
        private val bytes = ByteArray(ChunkedAead.CHUNK)
        var samples = 0L; private set

        fun write(pcm: ShortArray, n: Int) {
            var i = 0
            while (i < n) {
                val m = minOf(n - i, bytes.size / 2)
                for (k in 0 until m) { val s = pcm[i + k].toInt(); bytes[2 * k] = s.toByte(); bytes[2 * k + 1] = (s shr 8).toByte() }
                writer.write(bytes, 0, 2 * m); i += m
            }
            samples += n
        }

        fun close() = writer.close()
    }

    /** null when the key cannot be protected: then nothing is recorded (never stored unencrypted). */
    fun start(ctx: Context, now: Long = System.currentTimeMillis()): Recording? {
        val id = "rec_$now"
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrapped = LocalCrypto.encrypt(Base64.encodeToString(key.encoded, Base64.NO_WRAP)) ?: return null
        keyFile(ctx, id).writeText(wrapped)
        val fos = FileOutputStream(data(ctx, id))
        return Recording(id, ChunkedAead.Writer(fos, key, CHUNK_BYTES) { n -> if (n % SYNC_EVERY == 0L) runCatching { fos.fd.sync() } })
    }

    private fun key(ctx: Context, id: String): SecretKey? {
        if (!ID.matches(id)) return null
        val f = keyFile(ctx, id); if (!f.exists()) return null
        val raw = LocalCrypto.decrypt(f.readText()) ?: return null
        return SecretKeySpec(Base64.decode(raw, Base64.NO_WRAP), "AES")
    }

    fun exists(ctx: Context, id: String?): Boolean = id != null && ID.matches(id) && data(ctx, id).exists() && keyFile(ctx, id).exists()

    /** Seconds of audio (16 kHz mono 16-bit), estimated from the file size. */
    fun seconds(ctx: Context, id: String): Int = (data(ctx, id).length() / (2L * SAMPLE_RATE)).toInt()

    /** A decrypted copy for playback; [complete] = false when the recording was cut off (the app was killed). */
    class Playable(val file: File, val complete: Boolean)

    /**
     * Decrypts into a temporary WAV in the cache for playback; the caller deletes it when done. A recording cut off
     * by a crash is opened up to its last complete second and marked incomplete; a modified file is not opened.
     */
    fun open(ctx: Context, id: String): Playable? {
        val k = key(ctx, id) ?: return null
        val out = File(ctx.cacheDir, "play_$id.wav")
        return try {
            RandomAccessFile(out, "rw").use { it.setLength(0); it.write(ByteArray(44)) }
            val complete = FileOutputStream(out, true).use { o -> FileInputStream(data(ctx, id)).use { ChunkedAead.decrypt(it, k, o, allowTruncated = true) } }
            val pcm = out.length() - 44
            if (pcm <= 0) { out.delete(); return null }
            RandomAccessFile(out, "rw").use { it.seek(0); it.write(wavHeader(pcm)) }
            Playable(out, complete)
        } catch (e: Exception) { out.delete(); null }
    }

    fun toWav(ctx: Context, id: String): File? = open(ctx, id)?.file

    /** Ids of all recordings on the phone (used to show recordings left without a history entry after a crash). */
    fun ids(ctx: Context): List<String> = dir(ctx).listFiles()?.mapNotNull { f ->
        f.name.removeSuffix(".enc").takeIf { f.name.endsWith(".enc") && ID.matches(it) }
    } ?: emptyList()

    private fun wavHeader(pcmBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + pcmBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(SAMPLE_RATE)
        b.putInt(SAMPLE_RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcmBytes.toInt())
        return b.array()
    }

    fun delete(ctx: Context, id: String?) {
        if (id == null || !ID.matches(id)) return
        data(ctx, id).delete(); keyFile(ctx, id).delete(); File(ctx.cacheDir, "play_$id.wav").delete()
    }

    fun deleteAll(ctx: Context) { File(ctx.filesDir, "recordings").deleteRecursively() }

    /** Removes recordings older than the retention period (by the time in their name). */
    fun purge(ctx: Context, now: Long = System.currentTimeMillis()) {
        dir(ctx).listFiles()?.forEach { f ->
            val ts = f.name.removePrefix("rec_").substringBefore('.').toLongOrNull() ?: return@forEach
            if (now - ts > RETENTION_DAYS * 86_400_000L) f.delete()
        }
    }
}
