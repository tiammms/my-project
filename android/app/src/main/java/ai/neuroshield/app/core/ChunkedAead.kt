package ai.neuroshield.app.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Streaming encryption of a long file (a call recording) with standard AES-256-GCM from the platform:
 * the data is cut into 64 KB chunks, each sealed with its own random 12-byte IV. The chunk number and a
 * "last chunk" flag are authenticated too, so chunks cannot be reordered, removed or the file cut short.
 * Only standard JCA primitives are used; this class only frames them.
 *
 * Format: per chunk  [int length of ciphertext][12-byte IV][ciphertext + 16-byte tag]
 *
 * If the writing process is killed, the file ends without its "last" chunk. Such a file can still be read up to the
 * last complete chunk with allowTruncated = true: every chunk read is authenticated (its number is in the tag),
 * only the missing end cannot be told apart from a deliberately cut-off file, so the caller must say it is incomplete.
 */
object ChunkedAead {
    const val CHUNK = 64 * 1024
    private const val IV = 12
    private val random = SecureRandom()

    private fun aad(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    /**
     * @param chunkSize plaintext bytes per chunk (≤ [CHUNK]); smaller chunks lose less data if the process dies
     * @param onChunk   called after each chunk is written (e.g. to flush the file to the storage now and then)
     */
    class Writer(out: OutputStream, private val key: SecretKey, chunkSize: Int = CHUNK, private val onChunk: (Long) -> Unit = {}) : AutoCloseable {
        private val out = DataOutputStream(out)
        private val buf = ByteArray(chunkSize.coerceIn(1, CHUNK))
        private var used = 0
        private var index = 0L
        private var closed = false

        fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
            check(!closed)
            var o = off; var l = len
            while (l > 0) {
                if (used == buf.size) seal(false)
                val n = minOf(l, buf.size - used)
                System.arraycopy(b, o, buf, used, n); used += n; o += n; l -= n
            }
        }

        private fun seal(last: Boolean) {
            val iv = ByteArray(IV).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad(index++, last))
            val ct = c.doFinal(buf, 0, used)
            out.writeInt(ct.size); out.write(iv); out.write(ct)
            used = 0
            if (!last) onChunk(index)
        }

        override fun close() {
            if (closed) return
            seal(true)          // always one final chunk (possibly empty): marks the end of the file
            closed = true
            out.flush(); out.close()
        }
    }

    /**
     * Decrypts the stream into [out]. Throws if anything was changed or reordered. A file that ends without its last
     * chunk throws too, unless [allowTruncated]: then everything up to the last complete chunk is written.
     * @return true if the file was complete, false if it was cut off (only possible with [allowTruncated])
     */
    fun decrypt(input: InputStream, key: SecretKey, out: OutputStream, allowTruncated: Boolean = false): Boolean {
        val inp = DataInputStream(input)
        var index = 0L
        while (true) {
            val chunk = try {
                val len = inp.readInt()
                require(len in 16..(CHUNK + 16)) { "bad chunk" }
                val iv = ByteArray(IV); inp.readFully(iv)
                val ct = ByteArray(len); inp.readFully(ct)
                iv to ct
            } catch (e: EOFException) {
                if (allowTruncated) return false else throw SecurityException("recording is truncated")
            }
            val (iv, ct) = chunk
            // try as a middle chunk first, then as the last one: the tag tells which is genuine
            val plain = open(key, iv, ct, index, false) ?: open(key, iv, ct, index, true)?.also {
                out.write(it); return true
            } ?: throw SecurityException("recording was modified")
            out.write(plain)
            index++
        }
    }

    private fun open(key: SecretKey, iv: ByteArray, ct: ByteArray, index: Long, last: Boolean): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad(index, last))
        c.doFinal(ct)
    } catch (_: javax.crypto.AEADBadTagException) { null }
}
