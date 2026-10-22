package ai.neuroshield.app.core

/**
 * One piece of evidence about a call. [points] = how strongly it points to fraud (0..100) if it is true;
 * [confidence] = how sure we are that it IS true (speech recognition can mishear, a number can be spoofed).
 * A signal that could not be checked is kept with [available] = false: it never adds risk, but the result
 * says it was not checked, so "no risk found" is never confused with "not checked".
 */
data class RiskSignal(
    val id: String,
    val title: String,
    val points: Int,
    val confidence: Double = 1.0,
    val available: Boolean = true,
)

/** Thresholds are configurable and covered by tests (CallSessionTest, RiskEngineTest, the text test set). */
data class RiskThresholds(val medium: Double = 30.0, val high: Double = 70.0) {
    init { require(medium in 0.0..100.0 && high in medium..100.0) { "0 ≤ medium ≤ high ≤ 100" } }
}

data class RiskAssessment(
    val score: Double,               // 0..100
    val level: RiskLevel,
    val reasons: List<String>,       // signals that raised the risk, strongest first
    val notChecked: List<String>,    // signals that were not available
    val confidence: Double,          // 0..1: how much of the evidence could actually be checked, weighted by certainty
)

object RiskEngine {
    @Volatile var thresholds = RiskThresholds()

    /**
     * Independent signals combine as "probability that at least one is a real fraud sign":
     * score = 100 · (1 − Π(1 − points·confidence/100)). One weak signal stays weak; several add up.
     */
    fun combine(signals: List<RiskSignal>, t: RiskThresholds = thresholds): RiskAssessment {
        val used = signals.filter { it.available && it.points > 0 }
        val score = 100 * (1 - used.fold(1.0) { a, s -> a * (1 - (s.points.coerceIn(0, 100) * s.confidence.coerceIn(0.0, 1.0)) / 100.0) })
        val checked = signals.filter { it.available }
        val conf = if (signals.isEmpty()) 1.0 else checked.sumOf { it.confidence.coerceIn(0.0, 1.0) } / signals.size
        val rounded = Math.round(score * 10) / 10.0
        return RiskAssessment(
            score = rounded,
            level = levelOf(rounded, t),
            reasons = used.sortedByDescending { it.points * it.confidence }.map { it.title },
            notChecked = signals.filter { !it.available }.map { it.title },
            confidence = Math.round(conf * 100) / 100.0,
        )
    }
}

/**
 * What this phone already knows about a number: the user's marks and the app's own earlier verdicts
 * (reports after calls, suspicious messages). Stored only on the phone.
 */
data class NumberHistory(
    val userMarkedScam: Boolean = false,
    val userMarkedSafe: Boolean = false,
    val highReports: Int = 0,        // earlier calls from this number ended with a HIGH-risk report
    val mediumReports: Int = 0,
    val priorMessageLevel: RiskLevel? = null,
)

object LocalReputation {
    /** Signals from local history. It never labels the person: it says what happened before with this number. */
    fun signals(h: NumberHistory): List<RiskSignal> {
        if (h.userMarkedSafe) return emptyList()
        val out = mutableListOf<RiskSignal>()
        if (h.userMarkedScam) out += RiskSignal("user_mark", "Вы ранее отметили этот номер как мошеннический", 80)
        if (h.highReports > 0) out += RiskSignal("prev_high", "Прошлый звонок с этого номера был оценён как высокий риск" +
            (if (h.highReports > 1) " (${h.highReports} раза)" else ""), 60, 0.8)
        else if (h.mediumReports > 0) out += RiskSignal("prev_medium", "Прошлый звонок с этого номера имел признаки мошенничества", 30, 0.7)
        return out
    }
}
