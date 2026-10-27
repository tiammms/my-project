package ai.neuroshield.app.core

import kotlin.math.sqrt

/**
 * Result of the EXPERIMENTAL synthetic-voice check (AASIST-L, see ml/voice_spoof) over a call or a recording.
 * Sound is cut into 4-second pieces; each piece with speech gets P(synthetic) from the model.
 *
 * The verdict is deliberately cautious: at least [MIN_HITS] pieces must look synthetic, and they must be at least
 * [MIN_SHARE] of all pieces with speech (the microphone also hears the phone's owner, a real person).
 * It says how the sound looks, never who the person is: banks also call with recorded or synthesized voices.
 */
data class VoiceCheck(val windows: Int, val synthetic: Int, val maxP: Double, val threshold: Double) {
    val flagged: Boolean get() = synthetic >= MIN_HITS && synthetic >= windows * MIN_SHARE

    /** One line for reports, e.g. "похож на синтезированный в 3 из 5 фрагментов по 4 с". */
    val summaryRu: String get() = when {
        windows == 0 -> "речи для проверки было мало"
        flagged -> "похож на синтезированный в $synthetic из $windows фрагментов по 4 с"
        else -> "признаков синтезированного голоса не найдено ($windows фрагм. по 4 с" +
            (if (synthetic > 0) ", похожих на синтезированный: $synthetic" else "") + ")"
    }

    companion object {
        const val MIN_HITS = 2
        const val MIN_SHARE = 0.3
        const val WINDOW = 64_600            // 4.04 s at 16 kHz: the model's input size
        const val MIN_RMS = 0.003            // pieces quieter than this are treated as silence and skipped
    }
}

/** Collects 4-second pieces from 16 kHz mono PCM and keeps the model's scores. Thread-safe. */
class SyntheticVoiceMonitor(private val threshold: Double, private val minRms: Double = VoiceCheck.MIN_RMS) {
    private val buf = FloatArray(VoiceCheck.WINDOW)
    private var used = 0
    private val scores = mutableListOf<Double>()

    /** Returns the 4-second pieces completed by this buffer that contain sound (to be scored), possibly none. */
    @Synchronized fun onAudio(pcm: ShortArray, n: Int): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        var i = 0
        while (i < n) {
            val k = minOf(n - i, buf.size - used)
            for (j in 0 until k) buf[used + j] = pcm[i + j] / 32768f
            used += k; i += k
            if (used == buf.size) {
                if (rms(buf) >= minRms) out += buf.copyOf()
                used = 0
            }
        }
        return out
    }

    @Synchronized fun onScore(p: Double) { scores += p.coerceIn(0.0, 1.0) }

    @get:Synchronized val result: VoiceCheck
        get() = VoiceCheck(scores.size, scores.count { it >= threshold }, scores.maxOrNull() ?: 0.0, threshold)

    companion object {
        fun rms(x: FloatArray): Double {
            var s = 0.0
            for (v in x) s += v * v
            return sqrt(s / x.size)
        }

        /** Checks a whole recording held in memory (tests, short clips). */
        fun check(pcm: ShortArray, threshold: Double, maxWindows: Int = 40, score: (FloatArray) -> Double): VoiceCheck {
            val read: (Int) -> FloatArray = { w -> val off = w * VoiceCheck.WINDOW; FloatArray(VoiceCheck.WINDOW) { pcm[off + it] / 32768f } }
            return checkPieces(pcm.size / VoiceCheck.WINDOW, threshold, maxWindows, read, score)
        }

        /**
         * Checks a recording piece by piece, reading piece number w on demand (a long recording is never loaded
         * into memory at once): up to [maxWindows] pieces spread evenly over it, to keep the check quick on a phone.
         */
        fun checkPieces(total: Int, threshold: Double, maxWindows: Int, read: (Int) -> FloatArray, score: (FloatArray) -> Double): VoiceCheck {
            val m = SyntheticVoiceMonitor(threshold)
            if (total <= 0) return m.result
            val step = maxOf(1, (total + maxWindows - 1) / maxWindows)
            var w = 0
            while (w < total) {
                val piece = read(w)
                if (rms(piece) >= VoiceCheck.MIN_RMS) m.onScore(score(piece))
                w += step
            }
            return m.result
        }
    }
}
