package ai.neuroshield.app.core

/**
 * Speech-to-text engine for audio the app is ALLOWED to process (the microphone, e.g. on speakerphone, or a file
 * the user chose). The Android build uses Vosk offline models; any other engine can implement this.
 */
interface SpeechToText {
    val available: Boolean
    /** Feeds 16 kHz mono PCM; returns a finished phrase when one is recognised, otherwise null. */
    fun accept(pcm: ShortArray, size: Int): String?
    fun partial(): String
    fun finish(): String
}

data class VoiceRisk(
    val assessment: RiskAssessment,
    val tactics: List<Tactic>,
    val syntheticProbability: Double?,   // null = synthetic-voice check not available
    val words: Int,
)

/**
 * Analyses what is said in a call for scam scenarios (codes, passwords, transfers, "safe account", urgency,
 * posing as a bank or police, secrecy, remote-access apps) and explains every reason with the phrase heard.
 *
 * Models are replaceable: [classifier] (rule-based by default) and [synthetic] (an EXPERIMENTAL detector of
 * synthetic / cloned voice; not connected by default). The tone, accent or emotion of a voice is never a sign of fraud.
 * Whether this works on a LIVE call depends on the phone giving the app the call audio — see docs/AUTOMATION.md.
 */
class VoiceRiskAnalyzer(
    private val classifier: ScamTextClassifier = ScamTextClassifier.RuleBased,
    private val synthetic: SyntheticVoiceDetector = SyntheticVoiceDetector.NotConnected,
    private val asrConfidence: Double = 1.0,     // lower it for a weaker speech recogniser
) {
    private var worstSynthetic: Double? = null

    /** Feeds raw audio to the experimental synthetic-voice detector (no effect when it is not connected). */
    fun onAudio(pcm: ShortArray, size: Int) {
        if (!synthetic.available) return
        val p = runCatching { synthetic.score(pcm, size) }.getOrNull() ?: return
        worstSynthetic = maxOf(worstSynthetic ?: 0.0, p.coerceIn(0.0, 1.0))
    }

    fun analyze(transcript: String): VoiceRisk {
        val text = classifier.analyze(transcript)
        // the classifier's own score already combines tactics (one weak tactic such as "urgent" stays LOW,
        // a code request together with "this is the bank" is HIGH); speech-recognition errors are its uncertainty
        val reasons = text.tactics.map { t -> t.title + (t.evidence.firstOrNull()?.let { " («${it.match}»)" } ?: "") }
        val signals = mutableListOf(RiskSignal("speech", reasons.joinToString("; ").ifEmpty { "Признаков мошенничества в речи нет" },
            text.score.toInt(), asrConfidence))
        val p = worstSynthetic
        signals += if (!synthetic.available) RiskSignal("synthetic", "Синтетический голос: модель не подключена", 0, available = false)
        // experimental: alone it can never reach HIGH (at most 40 · 0.6 = 24 points)
        else RiskSignal("synthetic", "Возможен синтетический голос (экспериментальная проверка, ${(100 * (p ?: 0.0)).toInt()}%)",
            (40 * (p ?: 0.0)).toInt(), 0.6, available = p != null)
        val a = RiskEngine.combine(signals)
        return VoiceRisk(a.copy(reasons = reasons + a.reasons.filter { it.startsWith("Возможен синтетический") }), text.tactics, p, text.words)
    }
}
