package ai.neuroshield.app.core

/**
 * Turns decoded audio of any sample rate and channel count into 16 kHz mono PCM16 (what the offline recogniser and
 * the voice model expect), piece by piece, so a long voice message is never held in memory at once.
 * Channels are averaged; down-sampling first smooths with a short moving average (a simple low-pass, enough for
 * speech recognition), then interpolates linearly.
 */
class PcmConverter(private val inRate: Int, channels: Int, private val outRate: Int = 16_000) {
    private val ch = channels.coerceAtLeast(1)
    private val step = inRate.toDouble() / outRate
    private val avgLen = if (step >= 2.0) step.toInt() else 1
    private val hist = FloatArray(avgLen)          // last raw mono samples for the moving average
    private var histPos = 0
    private var histSum = 0.0
    private var pos = 0.0                          // next output position, relative to the first sample of the next chunk
    private var prev = 0f                          // last smoothed sample of the previous chunk

    init { require(inRate > 0 && outRate > 0) }

    /** [n] interleaved samples in; returns the 16 kHz mono samples they complete (possibly none). */
    fun push(interleaved: ShortArray, n: Int): ShortArray {
        val frames = n / ch
        if (frames == 0) return ShortArray(0)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until ch) s += interleaved[f * ch + c]
            val x = s / ch
            // moving average over avgLen raw samples
            histSum += x - hist[histPos]; hist[histPos] = x; histPos = (histPos + 1) % avgLen
            mono[f] = (histSum / avgLen).toFloat()
        }
        if (inRate == outRate) { prev = mono[frames - 1]; return ShortArray(frames) { clip(mono[it]) } }
        val out = ArrayList<Short>(((frames / step) + 2).toInt())
        // positions in [-1, 0) interpolate between the previous chunk's last sample and mono[0]
        while (pos < frames - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val t = (pos - i).toFloat()
            val a = if (i < 0) prev else mono[i]
            val b = mono[i + 1]
            out += clip(a + (b - a) * t)
            pos += step
        }
        pos -= frames
        prev = mono[frames - 1]
        return out.toShortArray()
    }

    private fun clip(v: Float): Short = v.coerceIn(-32768f, 32767f).toInt().toShort()
}

/** Limits and checks for audio the user hands to the app (a shared voice message or a picked file). */
object VoiceInput {
    const val MAX_SECONDS = 600            // a voice message longer than 10 minutes is not checked
    const val MAX_BYTES = 60L * 1024 * 1024
    const val RETELL_MAX_SECONDS = 90

    /**
     * Only content:// streams are read. A file:// or other URI from another app could point inside this app's own
     * storage (encrypted recordings, the history database); those are refused.
     */
    fun acceptScheme(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

    fun acceptSize(bytes: Long?): Boolean = bytes == null || bytes in 1..MAX_BYTES

    /** Shared types this check handles: audio files and the Ogg container WhatsApp/Telegram use for voice notes. */
    /**
     * Level of a checked voice message: the phrases decide; a voice that looks synthesized (experimental check)
     * raises it, the same way as during a call — together with scam phrases HIGH, alone at most MEDIUM.
     */
    fun verdict(r: TextRisk, v: VoiceCheck?): Pair<RiskLevel, Double> = when {
        v?.flagged == true && r.level != RiskLevel.LOW -> RiskLevel.HIGH to maxOf(75.0, r.score)
        v?.flagged == true -> RiskLevel.MEDIUM to maxOf(40.0, r.score)
        else -> r.level to r.score
    }

    fun acceptMime(mime: String?): Boolean {
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return true      // unknown type: let the decoder decide
        return m.startsWith("audio/") || m == "application/ogg" || m == "video/ogg" || m == "application/octet-stream"
    }
}
