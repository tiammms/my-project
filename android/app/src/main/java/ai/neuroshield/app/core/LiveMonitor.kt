package ai.neuroshield.app.core

import kotlin.math.sqrt

/**
 * Keeps the running transcript of a call and decides when to warn.
 * A warning is raised only when the risk level goes UP (no repeated pop-ups for the same level).
 */
class LiveCallMonitor(private val maxChars: Int = 6000) {
    private val finals = StringBuilder()
    private var partial = ""
    var lastResult: TextRisk = ScamAnalyzer.analyze("")
        private set
    private var warnedLevel: RiskLevel? = null

    val transcript: String get() = (finals.toString() + " " + partial).trim()

    data class Escalation(val risk: TextRisk, val transcript: String)

    fun onFinal(text: String): Escalation? {
        if (text.isNotBlank()) {
            finals.append(' ').append(text.trim())
            if (finals.length > maxChars) finals.delete(0, finals.length - maxChars)
        }
        partial = ""
        return evaluate()
    }

    fun onPartial(text: String): Escalation? {
        partial = text.trim()
        return evaluate()
    }

    private fun evaluate(): Escalation? {
        val r = ScamAnalyzer.analyze(transcript)
        lastResult = r
        val prev = warnedLevel
        if (r.level != RiskLevel.LOW && (prev == null || r.level.ordinal > prev.ordinal)) {
            warnedLevel = r.level
            return Escalation(r, transcript)
        }
        return null
    }
}

/** Detects that Android hands the app silence instead of microphone audio (common during cellular calls). */
class SilenceDetector(private val sampleRate: Int = 16000, private val windowSeconds: Double = 6.0) {
    private var samples = 0L
    private var maxRms = 0.0
    var reported = false; private set

    fun rms(buf: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var s = 0.0
        for (i in 0 until n) { val v = buf[i] / 32768.0; s += v * v }
        return sqrt(s / n)
    }

    /** Returns true once, when the first [windowSeconds] contained (almost) pure digital silence. */
    fun onBuffer(buf: ShortArray, n: Int): Boolean {
        samples += n
        maxRms = maxOf(maxRms, rms(buf, n))
        if (!reported && samples >= sampleRate * windowSeconds) {
            reported = true
            return maxRms < 0.0008
        }
        return false
    }

    val level: Double get() = maxRms
}
