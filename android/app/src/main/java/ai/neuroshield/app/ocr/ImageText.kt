package ai.neuroshield.app.ocr

import ai.neuroshield.app.core.VoiceInput
import ai.neuroshield.app.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Offline text recognition (Tesseract) for screenshots and photos: Russian, Kazakh and English.
 * The language files (≈13 MB) are downloaded once over HTTPS from the Tesseract project, pinned to one commit,
 * and each is checked against its SHA-256 before it is used. After that recognition works without internet.
 */
object OcrData {
    private const val BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/"
    data class Lang(val code: String, val sha256: String, val bytes: Long)
    val LANGS = listOf(
        Lang("rus", "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225", 3_861_738),
        Lang("kaz", "fcc01eed3815a42b9c6321c4c9d3606f39b166cbf95ade98b7d8d12063eae53d", 4_734_644),
        Lang("eng", "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2", 4_113_088),
    )
    const val SIZE_MB = 13

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val percent: Int) : Status
        data object Ready : Status
        data class Failed(val error: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Missing)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)      // the download continues if the screen is left
    val status: StateFlow<Status> = _status

    fun root(ctx: Context) = File(ctx.filesDir, "tesseract")
    private fun file(ctx: Context, l: Lang) = File(root(ctx), "tessdata/${l.code}.traineddata")

    fun ready(ctx: Context): Boolean = LANGS.all { file(ctx, it).length() == it.bytes }

    fun refresh(ctx: Context) { if (_status.value !is Status.Downloading) _status.value = if (ready(ctx)) Status.Ready else Status.Missing }

    fun download(ctx: Context): Job = scope.launch {
        if (_status.value is Status.Downloading) return@launch
        val total = LANGS.sumOf { it.bytes }
        var done = 0L
        try {
            _status.value = Status.Downloading(0)
            for (l in LANGS) {
                val dst = file(ctx, l)
                if (dst.length() == l.bytes) { done += l.bytes; continue }
                dst.parentFile?.mkdirs()
                val tmp = File(dst.parentFile, "${l.code}.part")
                val conn = (java.net.URL(BASE + l.code + ".traineddata").openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000
                }
                conn.connect()
                if (conn.responseCode !in 200..299) throw IllegalStateException("http")
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                conn.inputStream.use { inp -> FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        got += n
                        if (got > l.bytes) throw IllegalStateException("size")
                        out.write(buf, 0, n); md.update(buf, 0, n)
                        _status.value = Status.Downloading((100 * (done + got) / total).toInt())
                        if (!isActive) throw InterruptedException()
                    }
                } }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (got != l.bytes || !sha.equals(l.sha256, ignoreCase = true)) { tmp.delete(); throw SecurityException("sha") }
                if (!tmp.renameTo(dst)) { tmp.delete(); throw IllegalStateException("rename") }
                done += l.bytes
            }
            _status.value = Status.Ready
        } catch (e: SecurityException) {
            _status.value = Status.Failed(tr("файл не прошёл проверку подлинности и удалён", "файл түпнұсқалық тексеруден өтпеді және жойылды"))
        } catch (e: InterruptedException) {
            _status.value = Status.Missing
        } catch (e: Exception) {
            _status.value = Status.Failed(tr("нет связи с сервером, проверьте интернет", "сервермен байланыс жоқ, интернетті тексеріңіз"))
        }
    }
}

/** Reads the text in a picture the user chose or shared. Runs on the phone; the picture is not stored. */
object ImageText {
    class Rejected(val reason: String) : Exception(reason)
    private const val MAX_SIDE = 2200

    fun recognize(ctx: Context, uri: Uri): String {
        if (!VoiceInput.acceptScheme(uri.scheme)) throw Rejected("scheme")
        val size = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
        if (size != null && size > VoiceInput.MAX_IMAGE_BYTES) throw Rejected("size")
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val k = VoiceInput.imageScale(w, h, MAX_SIDE)
            if (k < 1.0) d.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
        }
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        val tess = TessBaseAPI()
        try {
            if (!tess.init(OcrData.root(ctx).absolutePath, OcrData.LANGS.joinToString("+") { it.code })) throw Rejected("init")
            tess.setImage(argb)
            return tess.getUTF8Text() ?: ""
        } finally {
            tess.recycle()
            argb.recycle()
        }
    }
}
