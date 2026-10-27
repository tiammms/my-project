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
 */
object ChunkedAead {
    const val CHUNK = 64 * 1024
    private const val IV = 12
    private val random = SecureRandom()

    private fun aad(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    class Writer(out: OutputStream, private val key: SecretKey) : AutoCloseable {
        private val out = DataOutputStream(out)
        private val buf = ByteArray(CHUNK)
        private var used = 0
        private var index = 0L
        private var closed = false

        fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
            check(!closed)
            var o = off; var l = len
            while (l > 0) {
                if (used == CHUNK) seal(false)
                val n = minOf(l, CHUNK - used)
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
        }

        override fun close() {
            if (closed) return
            seal(true)          // always one final chunk (possibly empty): marks the end of the file
            closed = true
            out.flush(); out.close()
        }
    }

    /** Decrypts the whole stream into [out]; throws if anything was changed, reordered or cut off. */
    fun decrypt(input: InputStream, key: SecretKey, out: OutputStream) {
        val inp = DataInputStream(input)
        var index = 0L
        while (true) {
            val len = try { inp.readInt() } catch (e: EOFException) { throw SecurityException("recording is truncated") }
            require(len in 16..(CHUNK + 16)) { "bad chunk" }
            val iv = ByteArray(IV); inp.readFully(iv)
            val ct = ByteArray(len); inp.readFully(ct)
            // try as a middle chunk first, then as the last one: the tag tells which is genuine
            val plain = open(key, iv, ct, index, false) ?: open(key, iv, ct, index, true)?.also {
                out.write(it); return
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
