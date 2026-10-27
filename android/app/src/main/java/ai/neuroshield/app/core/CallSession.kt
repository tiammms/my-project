package ai.neuroshield.app.core

import kotlin.math.max

/**
 * One phone or messenger call from a number that is not in contacts, followed from the first ring to hang-up.
 * Every check that ran (or could not run) is recorded, so that after the call the user gets a short, honest report:
 * what was found and what was NOT checked and why.
 *
 * Pure Kotlin (no Android), so the whole chain is unit-tested with known scam and safe scenarios.
 */
enum class CallChannel { CELLULAR, MESSENGER }

/** Why the speech analysis did or did not run during this call. */
enum class SpeechStatus(val ru: String) {
    NOT_STARTED("не запускался"),
    STARTED("запущен"),
    NO_MODEL("не запускался: модель распознавания речи не скачана"),
    NO_PERMISSION("не запускался: нет разрешения на микрофон"),
    AUTO_OFF("не запускался: автоматический анализ речи выключен в настройках"),
    SILENCE("запущен, но телефон отдаёт приложению тишину — на этом устройстве звук звонка недоступен"),
    NOT_AVAILABLE("недоступен: звук звонков в мессенджерах закрыт для других приложений"),
}

sealed interface CallEvent {
    val tMs: Long
    data class Ringing(override val tMs: Long, val number: String?, val channel: CallChannel, val app: String?, val pre: CallRiskResult) : CallEvent
    data class Answered(override val tMs: Long) : CallEvent
    data class SmsCodeArrived(override val tMs: Long, val sender: String, val masked: String) : CallEvent
    data class Speech(override val tMs: Long, val risk: TextRisk) : CallEvent
    data class SpeechState(override val tMs: Long, val status: SpeechStatus) : CallEvent
    /** Latest result of the experimental synthetic-voice check (replaces the previous one). */
    data class Voice(override val tMs: Long, val check: VoiceCheck) : CallEvent
    data class Ended(override val tMs: Long) : CallEvent
}

data class CallReport(
    val number: String?,
    val channel: CallChannel,
    val app: String?,
    val answered: Boolean,
    val durationSec: Int,
    val level: RiskLevel,
    val score: Double,
    val title: String,
    val findings: List<String>,     // what raised the risk
    val checks: List<String>,       // what was checked, with status — including what could not be checked
    val advice: List<String>,
)

class CallSession(
    private val externalReputation: String = ReputationSources.NOT_CONNECTED,
    /** true when this build carries the synthetic-voice model (ml/voice_spoof). */
    private val voiceModel: Boolean = false,
) {
    private var ringing: CallEvent.Ringing? = null
    private var answeredAt: Long? = null
    private var endedAt: Long? = null
    private val codes = mutableListOf<CallEvent.SmsCodeArrived>()
    private var worstSpeech: TextRisk? = null
    private var speech: SpeechStatus = SpeechStatus.NOT_STARTED
    private var voice: VoiceCheck? = null

    val events = mutableListOf<CallEvent>()
    val isEnded get() = endedAt != null
    val isStarted get() = ringing != null

    fun on(e: CallEvent) {
        events += e
        when (e) {
            is CallEvent.Ringing -> if (ringing == null) ringing = e
            is CallEvent.Answered -> if (answeredAt == null) answeredAt = e.tMs
            is CallEvent.SmsCodeArrived -> codes += e
            is CallEvent.Speech -> if (worstSpeech == null || e.risk.score > worstSpeech!!.score) worstSpeech = e.risk
            is CallEvent.SpeechState -> if (rank(e.status) >= rank(speech)) speech = e.status   // keep the most informative state
            is CallEvent.Voice -> voice = e.check
            is CallEvent.Ended -> if (endedAt == null) endedAt = e.tMs
        }
    }

    private fun rank(s: SpeechStatus) = when (s) {
        SpeechStatus.NOT_STARTED -> 0
        SpeechStatus.AUTO_OFF, SpeechStatus.NO_MODEL, SpeechStatus.NO_PERMISSION, SpeechStatus.NOT_AVAILABLE -> 1
        SpeechStatus.STARTED -> 2
        SpeechStatus.SILENCE -> 3
    }

    fun report(): CallReport? {
        val r = ringing ?: return null
        val end = endedAt ?: events.lastOrNull()?.tMs ?: r.tMs
        val answered = answeredAt != null
        val duration = if (answered) ((end - answeredAt!!) / 1000).toInt().coerceAtLeast(0) else 0
        val findings = mutableListOf<String>()
        var score = r.pre.score
        findings += r.pre.reasons.filter { it.points > 0 }.map { it.text }
        if (codes.isNotEmpty()) {
            score = max(score, 90.0)
            findings += "Во время звонка пришёл код из SMS (${codes.first().masked} от «${codes.first().sender}»)"
        }
        val sp = worstSpeech
        if (sp != null && sp.level != RiskLevel.LOW) {
            score = max(score, sp.score)
            findings += sp.tactics.map { t -> "В разговоре: " + t.title.replaceFirstChar { it.lowercase() } +
                (t.evidence.firstOrNull()?.let { " («${it.match}»)" } ?: "") }
        }
        val vc = voice
        val voiceFlagged = vc?.flagged == true
        if (voiceFlagged) {
            // experimental: alone it gives a MEDIUM warning, together with scam phrases or an SMS code — HIGH
            score = max(score, if (codes.isNotEmpty() || (sp != null && sp.level != RiskLevel.LOW)) 75.0 else 40.0)
            findings += "Голос в разговоре " + vc!!.summaryRu + " (экспериментальная проверка, может ошибаться)"
        }
        val level = levelOf(score)
        val checks = mutableListOf<String>()
        checks += "Номер: проверен по вашим отметкам, частоте звонков и недавним сообщениям с этого номера"
        checks += "Внешняя база мошеннических номеров: $externalReputation"
        checks += "SMS с кодом во время звонка: " + (if (codes.isNotEmpty()) "пришёл код — показана тревога" else "отслеживалось, кодов не было")
        val speechLine = when {
            r.channel == CallChannel.MESSENGER && speech == SpeechStatus.NOT_STARTED -> SpeechStatus.NOT_AVAILABLE.ru
            !answered -> "не нужен: звонок не принят"
            else -> speech.ru
        }
        checks += "Анализ речи: $speechLine"
        checks += "Синтетический голос (робот, ИИ): " + when {
            vc != null -> vc.summaryRu + " — экспериментальная модель"
            !voiceModel -> "не проверялся — модель не подключена"
            !answered -> "не нужен: звонок не принят"
            else -> "не проверялся: звук разговора не анализировался"
        }
        val title = when {
            codes.isNotEmpty() -> "Возможное мошенничество: код из SMS во время звонка"
            level == RiskLevel.HIGH -> "Возможное мошенничество"
            level == RiskLevel.MEDIUM && voiceFlagged && codes.isEmpty() && (sp == null || sp.level == RiskLevel.LOW) -> "Голос в звонке похож на синтезированный"
            level == RiskLevel.MEDIUM -> "Звонок с признаками мошенничества"
            !answered -> "Пропущенный звонок с незнакомого номера"
            else -> "Звонок с незнакомого номера: признаков мошенничества не найдено"
        }
        val advice = (if (voiceFlagged) listOf("Если звонил «близкий человек» или «банк» — положите трубку и перезвоните сами по номеру, который знаете") else emptyList<String>()) + when (level) {
            RiskLevel.HIGH -> listOf("Не называйте коды и не переводите деньги по просьбе звонившего",
                "Если уже назвали код — сразу позвоните в банк по номеру на карте и заблокируйте карту",
                "Отметьте номер как мошеннический, чтобы следующий звонок с него был заблокирован")
            RiskLevel.MEDIUM -> listOf("Перезвоните в организацию сами по официальному номеру",
                "Никому не называйте коды из SMS")
            RiskLevel.LOW -> if (answered) emptyList() else listOf("Если перезваниваете — не называйте коды и данные карты")
        }
        return CallReport(r.number, r.channel, r.app, answered, duration, level, Math.round(score * 10) / 10.0, title, findings, checks, advice)
    }
}

// ------------------------------------------------------------------ plug-in points for real models and services

/** Reputation of a phone number from an outside source (a bank, a telecom operator, a shared complaints database). */
interface ReputationSource {
    val name: String
    /** null = the number is unknown to this source. Must not block the UI thread for long; results are cached by the caller. */
    fun lookup(normalizedNumber: String): ReputationHit?
}
data class ReputationHit(val level: RiskLevel, val reason: String, val source: String)

object ReputationSources {
    const val NOT_CONNECTED = "не подключена (нужен договор и API банка, оператора или Антифрод-центра)"
    /** Registered outside sources. Empty in this build: no real API is available to the project, and none is faked. */
    val remote: MutableList<ReputationSource> = mutableListOf()
    fun status(): String = if (remote.isEmpty()) NOT_CONNECTED else "проверена (" + remote.joinToString { it.name } + ")"
    fun lookup(n: String): List<ReputationHit> = remote.mapNotNull { runCatching { it.lookup(n) }.getOrNull() }
}

/** Detects synthetic / cloned speech in raw 16 kHz mono PCM. */
interface SyntheticVoiceDetector {
    val available: Boolean
    /** Probability 0..1 that the audio is synthetic, or null when not available. */
    fun score(pcm: ShortArray, size: Int): Double?
    object NotConnected : SyntheticVoiceDetector {
        override val available = false
        override fun score(pcm: ShortArray, size: Int): Double? = null
    }
}

/** Classifies a transcript for scam tactics. The rule-based analyser is the default; a trained model can replace it. */
fun interface ScamTextClassifier {
    fun analyze(text: String): TextRisk
    companion object { val RuleBased = ScamTextClassifier { ScamAnalyzer.analyze(it) } }
}
