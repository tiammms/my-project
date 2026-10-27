package ai.neuroshield.app.core

import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

enum class RiskLevel(val ru: String) { LOW("Низкий риск"), MEDIUM("Средний риск"), HIGH("Высокий риск") }

fun levelOf(score: Double, t: RiskThresholds = RiskEngine.thresholds): RiskLevel = when {
    score >= t.high -> RiskLevel.HIGH
    score >= t.medium -> RiskLevel.MEDIUM
    else -> RiskLevel.LOW
}

data class Evidence(val match: String, val context: String)
data class Tactic(val id: String, val title: String, val advice: String, val weight: Int, val evidence: List<Evidence>, val weak: Boolean = false)
data class TextRisk(val score: Double, val level: RiskLevel, val tactics: List<Tactic>, val words: Int) {
    val summary: String
        get() = tactics.count { !it.weak }.let { n -> if (n == 0) "Признаков мошеннического сценария не найдено" else "Найдено признаков обмана: $n" }
}

/**
 * Rule-based detector of social-engineering tactics in what is SAID (speech transcript) or WRITTEN (SMS, message).
 * Russian, Kazakh and English. Transparent on purpose: every warning shows the phrase that triggered it.
 * It judges the content of the conversation, never the person or the sound of their voice.
 * Same rules as the NeuroShield server (backend/app/biometrics/scam_text.py), adapted for speech recognition output.
 */
object ScamAnalyzer {
    // The phrase rules live in shared/scam_rules.json (one source for Android, iOS and the server);
    // ScamRules.kt is generated from it by scripts/gen_scam_rules.py.

    // Java regex \w and \b are ASCII-only on the JVM; make them Unicode-aware the same way on JVM and Android.
    private const val W = """[\p{L}\p{N}_]"""
    private fun portable(p: String): String = p
        .replace("""\b""", """(?:(?<!$W)(?=$W)|(?<=$W)(?!$W))""")
        .replace("""\w""", W)

    private val compiled: List<Pair<ScamRule, List<Pattern>>> = ScamRules.CATEGORIES.map { c ->
        c to c.patterns.map { Pattern.compile(portable(it), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }
    }

    const val MAX_TEXT = 20_000

    private val weakIds = ScamRules.CATEGORIES.filter { it.weak }.map { it.id }.toSet()
    fun isWeak(id: String): Boolean = id in weakIds

    fun normalize(t: String): String = t.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    fun analyze(text: String): TextRisk {
        val t = normalize(text.take(MAX_TEXT))
        val found = mutableListOf<Tactic>()
        for ((c, pats) in compiled) {
            val hits = mutableListOf<Evidence>()
            val spans = mutableListOf<IntRange>()
            loop@ for (p in pats) {
                val m = p.matcher(t)
                while (m.find()) {
                    if (spans.any { m.start() < it.last && m.end() > it.first }) continue
                    spans += m.start()..m.end()
                    val a = max(0, m.start() - 40)
                    val b = min(t.length, m.end() + 40)
                    hits += Evidence(m.group(), (if (a > 0) "…" else "") + t.substring(a, b) + (if (b < t.length) "…" else ""))
                    if (hits.size >= 3) break@loop
                }
            }
            if (hits.isNotEmpty()) found += Tactic(c.id, c.title, c.advice, c.weight, hits, c.weak)
        }
        // links: checked by LinkCheck (look-alike brands, shorteners, IP hosts, punycode, throw-away domains)
        val links = LinkCheck.scan(text.take(MAX_TEXT)).filter { it.score >= 30 }
        if (links.isNotEmpty()) {
            val worst = links.maxBy { it.score }
            found += Tactic("phishing_link", "Подозрительная ссылка: ${worst.host}",
                "Не открывайте ссылку и не вводите на ней данные. Зайдите в приложение банка или на сайт сами.",
                worst.score.coerceIn(30, 60), links.take(3).map { Evidence(it.host, it.reasons.joinToString("; ")) })
        }
        // "weak" categories (only mentioning a bank, a card, a code) add a little to the score but are never
        // counted as a separate tactic: they cannot turn one tactic into HIGH or raise a warning on their own
        val ids = found.map { it.id }.filter { !isWeak(it) }.toSet()
        var score = if (found.isEmpty()) 0.0 else 100 * (1 - found.fold(1.0) { acc, f -> acc * (1 - f.weight / 100.0) })
        // A single tactic alone never yields HIGH (same safeguard as the NeuroShield risk engine).
        // A fake bank/government site is strong evidence on its own (brand in the address but not the official domain + risky signs).
        val forgedSite = links.any { it.score >= 70 }
        if (ids.size <= 1 && !forgedSite) score = min(score, 45.0)
        if (forgedSite) score = max(score, 75.0)
        val critical = ids.any { it in ScamRules.CRITICAL } || links.any { it.score >= 50 }
        if (critical && ids.size >= 2) score = max(score, 75.0)
        val words = if (t.isBlank()) 0 else t.split(" ").size
        return TextRisk(Math.round(score * 10) / 10.0, levelOf(score), found, words)
    }
}
