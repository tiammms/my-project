package ai.neuroshield.app

import ai.neuroshield.app.core.*
import org.junit.Assert.*
import org.junit.Test

class ScamAnalyzerTest {
    @Test fun bankSecurityScriptIsHigh() {
        val r = ScamAnalyzer.analyze("Здравствуйте, это служба безопасности банка. Срочно продиктуйте код из СМС и никому не говорите.")
        assertEquals(RiskLevel.HIGH, r.level)
        val ids = r.tactics.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("credentials_request", "authority_impersonation", "urgency_pressure")))
    }

    @Test fun speechRecognizerStyleTextWithoutPunctuation() {
        // Vosk output: lower case, no punctuation
        val r = ScamAnalyzer.analyze("вас беспокоит служба безопасности сбербанка назовите пожалуйста код из смс который вам пришел")
        assertEquals(RiskLevel.HIGH, r.level)
    }

    @Test fun safeAccountAndAnyDesk() {
        assertEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("переведите все деньги на безопасный счет и установите приложение anydesk").level)
    }

    @Test fun everydayTalkIsLow() {
        for (t in listOf("привет мам я задержусь купи хлеба", "оплати пожалуйста счет за интернет до пятницы",
                "сәлем кешке кездесейік", "see you at the cinema at seven")) {
            assertEquals(t, RiskLevel.LOW, ScamAnalyzer.analyze(t).level)
        }
    }

    @Test fun singleTacticNeverHigh() {
        val r = ScamAnalyzer.analyze("срочно немедленно прямо сейчас последний шанс")
        assertTrue(r.score <= 45.0)
        assertNotEquals(RiskLevel.HIGH, r.level)
    }

    @Test fun kazakhAndEnglish() {
        assertEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("Банктің қауіпсіздік қызметі. Кодты айтыңыз, жедел!").level)
        assertEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("this is the bank security team read me the verification code right now").level)
    }

    @Test fun evidenceIsReported() {
        val t = ScamAnalyzer.analyze("продиктуйте код из смс").tactics.first { it.id == "credentials_request" }
        assertTrue(t.evidence.isNotEmpty())
        assertEquals(1, t.evidence.size)    // overlapping patterns count once
    }
}

class CallRiskTest {
    private fun facts(number: String?, contact: Boolean = false, calls: Int = 1, scam: Boolean = false, safe: Boolean = false) =
        CallFacts(number, contact, "+7", calls, scam, safe)

    @Test fun contactIsLow() = assertEquals(RiskLevel.LOW, CallRisk.assess(facts("+77011234567", contact = true)).level)
    @Test fun unknownLocalIsLowButExplained() {
        val r = CallRisk.assess(facts("+77011234567"))
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.reasons.any { "контактах" in it.text })
        assertTrue(r.advice.isNotEmpty())
    }
    @Test fun hiddenOrInternationalIsMedium() {
        assertEquals(RiskLevel.MEDIUM, CallRisk.assess(facts(null)).level)
        assertEquals(RiskLevel.MEDIUM, CallRisk.assess(facts("+442071234567")).level)
    }
    @Test fun repeatedInternationalStaysBelowHighWithoutUserMark() {
        assertNotEquals(RiskLevel.HIGH, CallRisk.assess(facts("+442071234567", calls = 4)).level)
    }
    @Test fun userMarkedScamIsHigh() = assertEquals(RiskLevel.HIGH, CallRisk.assess(facts("+77011234567", scam = true)).level)
    @Test fun userMarkedSafeIsLow() = assertEquals(RiskLevel.LOW, CallRisk.assess(facts(null, safe = true)).level)
}

class SmsCodeTest {
    @Test fun findsCodes() {
        assertEquals("482913", SmsCode.find("Код подтверждения: 482913. Никому его не сообщайте")!!.code)
        assertEquals("1234", SmsCode.find("Your code is 1234")!!.code)
        assertEquals("5531", SmsCode.find("Растаушы код 5531")!!.code)
    }
    @Test fun ignoresOrdinaryMessages() {
        assertNull(SmsCode.find("Встречаемся в 1830 у входа"))
        assertNull(SmsCode.find("Баланс 25000 тенге"))
    }
    @Test fun masksCode() = assertEquals("4••••3", SmsCode.find("код 482913")!!.masked)
}

class LivenessTest {
    private fun frame(yaw: Float, eyes: Float = 0.9f, faces: Int = 1, id: Int = 7) = FaceFrame(faces, id, yaw, eyes, eyes)
    private fun yawFor(p: Pose) = when (p) { Pose.LEFT -> 30f; Pose.RIGHT -> -30f; else -> 0f }

    private fun run(s: LivenessSession, act: (Pose) -> List<FaceFrame>): LivenessState {
        var t = 0L
        while (s.state == LivenessState.RUNNING && t < 120_000) {
            val p = s.current ?: break
            for (f in act(p)) { t += 100; s.onFrame(f, t) }
        }
        return s.state
    }

    @Test fun followingInstructionsPasses() {
        val s = LivenessSession()
        val st = run(s) { p -> if (p == Pose.BLINK) listOf(frame(0f, 0.9f), frame(0f, 0.1f)) else listOf(frame(yawFor(p))) }
        assertEquals(s.failure, LivenessState.PASSED, st)
    }

    @Test fun photoFails() {   // a photo never turns and never blinks
        val s = LivenessSession(stepTimeoutMs = 2000)
        assertEquals(LivenessState.FAILED, run(s) { listOf(frame(0f)) })
    }

    @Test fun wrongDirectionFails() {
        val s = LivenessSession(steps = listOf(Pose.CENTER, Pose.LEFT), stepTimeoutMs = 2000)
        assertEquals(LivenessState.FAILED, run(s) { p -> listOf(frame(-yawFor(p))) })
    }

    @Test fun secondFaceOrSwapFails() {
        val s1 = LivenessSession(); s1.onFrame(frame(0f, faces = 2), 0)
        assertEquals(LivenessState.FAILED, s1.state)
        val s2 = LivenessSession(); s2.onFrame(frame(0f, id = 1), 0); s2.onFrame(frame(0f, id = 2), 100)
        assertEquals(LivenessState.FAILED, s2.state)
    }

    @Test fun stepsAreRandomAndComplete() {
        val seqs = (1..50).map { LivenessSession.randomSteps() }.toSet()
        assertTrue(seqs.size >= 4)
        for (s in seqs) assertTrue(s.containsAll(listOf(Pose.LEFT, Pose.RIGHT, Pose.BLINK)))
    }
}

class LiveMonitorTest {
    @Test fun escalatesOnlyUpwards() {
        val m = LiveCallMonitor()
        assertNull(m.onFinal("алло здравствуйте"))
        assertNull(m.onFinal("вас беспокоит служба безопасности банка"))   // one tactic, weight 25: LOW
        val e1 = m.onFinal("по вашей карте подозрительная операция")
        assertNotNull(e1); assertEquals(RiskLevel.MEDIUM, e1!!.risk.level)
        assertNull(m.onPartial("сейчас я вам"))                    // still MEDIUM: no repeated pop-up
        val e2 = m.onFinal("срочно продиктуйте код из смс")
        assertEquals(RiskLevel.HIGH, e2!!.risk.level)
        assertNull(m.onFinal("никому не говорите"))                // already warned at HIGH
    }

    @Test fun silenceDetected() {
        val d = SilenceDetector(windowSeconds = 1.0)
        val zero = ShortArray(1600)
        var flagged = false
        repeat(12) { if (d.onBuffer(zero, zero.size)) flagged = true }
        assertTrue(flagged)
        val d2 = SilenceDetector(windowSeconds = 1.0)
        val tone = ShortArray(1600) { (3000 * kotlin.math.sin(it / 5.0)).toInt().toShort() }
        var f2 = false
        repeat(12) { if (d2.onBuffer(tone, tone.size)) f2 = true }
        assertFalse(f2)
    }
}

class MessengerTest {
    @Test fun whatsappIncomingCallFromUnknownNumber() {
        val e = Messenger.parse("com.whatsapp", "+44 7911 123456", "Входящий голосовой звонок", true) as MessengerEvent.IncomingCall
        assertEquals("+447911123456", e.number); assertFalse(e.video)
        val v = Messenger.parse("com.whatsapp", "‪+1 (415) 555-0100‬", "Incoming video call", false) as MessengerEvent.IncomingCall
        assertEquals("+14155550100", v.number); assertTrue(v.video)
    }
    @Test fun russianTextWithoutCallCategory() {
        assertTrue(Messenger.parse("com.whatsapp", "+447911123456", "Входящий видеозвонок", false) is MessengerEvent.IncomingCall)
        assertTrue(Messenger.parse("com.whatsapp", "+447911123456", "Текущий видеозвонок", false) is MessengerEvent.OngoingCall)
    }
    @Test fun contactNameHasNoNumber() {
        val e = Messenger.parse("com.whatsapp", "Мама", "Входящий голосовой звонок", true) as MessengerEvent.IncomingCall
        assertNull(e.number)
    }
    @Test fun ongoingCallAndMessages() {
        assertTrue(Messenger.parse("com.whatsapp", "+447911123456", "Текущий голосовой звонок", true) is MessengerEvent.OngoingCall)
        val m = Messenger.parse("org.telegram.messenger", "+77011234567", "Срочно продиктуйте код из смс", false) as MessengerEvent.Message
        assertEquals("Telegram", m.app)
    }
    @Test fun otherAppsIgnored() = assertNull(Messenger.parse("com.example.game", "+447911123456", "Incoming call", true))

    @Test fun messengerCallRisk() {
        val base = CallFacts("+447911123456", false, "+7", 1, false, false, messenger = "WhatsApp")
        assertEquals(RiskLevel.MEDIUM, CallRisk.assess(base).level)                               // foreign + via WhatsApp
        val afterScamMessage = base.copy(priorMessageLevel = RiskLevel.HIGH, priorTactics = listOf("Просят код, пароль или данные карты"))
        val r = CallRisk.assess(afterScamMessage)
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.reasons.any { "писал" in it.text })
        assertTrue(r.advice.any { "WhatsApp" in it })
        assertEquals(RiskLevel.LOW, CallRisk.assess(CallFacts("+77011234567", false, "+7", 1, false, false, messenger = "WhatsApp")).level)
    }
}

class VideoCallCheckTest {
    private fun frames(seconds: Int, f: (Int) -> ScreenFace) = (0 until seconds * 3).map { f(it) }
    private fun face(i: Int, yaw: Float = 0f, eyes: Float? = 0.9f, cx: Float = 0.5f, faces: Int = 1, area: Float = 0.15f) =
        ScreenFace(i * 333L, faces, area, cx, 0.4f, yaw, eyes, eyes)

    @Test fun photoInsteadOfPersonIsHigh() {
        val v = VideoCallCheck.evaluate(frames(25) { face(it) })
        assertEquals(RiskLevel.HIGH, v.level); assertEquals(0, v.blinks)
    }
    @Test fun livePersonIsLow() {
        val v = VideoCallCheck.evaluate(frames(25) { i ->
            face(i, yaw = (8 * kotlin.math.sin(i / 4.0)).toFloat(), eyes = if (i % 12 == 6) 0.1f else 0.9f, cx = 0.5f + 0.02f * kotlin.math.sin(i / 7.0).toFloat())
        })
        assertEquals(RiskLevel.LOW, v.level); assertTrue(v.blinks >= 5); assertTrue(v.conclusive)
    }
    @Test fun noFaceShownIsMedium() {
        assertEquals(RiskLevel.MEDIUM, VideoCallCheck.evaluate(frames(20) { face(it, faces = 0) }).level)
        // only the small self-view thumbnail visible
        assertEquals(RiskLevel.MEDIUM, VideoCallCheck.evaluate(frames(20) { face(it, area = 0.01f) }).level)
    }
    @Test fun tooShortIsInconclusive() = assertFalse(VideoCallCheck.evaluate(frames(5) { face(it) }).conclusive)
    @Test fun eyesNotMeasurableIsNotAccusing() {
        val v = VideoCallCheck.evaluate(frames(25) { face(it, yaw = (6 * kotlin.math.sin(it / 3.0)).toFloat(), eyes = null) })
        assertEquals(RiskLevel.LOW, v.level)
    }
}

class LinkCheckTest {
    @Test fun lookalikeBrandIsFlagged() {
        val f = LinkCheck.check("https://kaspi-verify.top/login")
        assertTrue(f.score >= 70); assertTrue(f.reasons.any { "Kaspi" in it })
    }
    @Test fun officialDomainsAreClean() {
        for (u in listOf("https://kaspi.kz/shop", "https://www.halykbank.kz", "https://egov.kz/cms/ru", "https://t.me/neuroshield"))
            assertEquals(u, 0, LinkCheck.check(u).score)
    }
    @Test fun shortenerIpAndPunycode() {
        assertTrue(LinkCheck.check("https://bit.ly/3xYz").score >= 35)
        assertTrue(LinkCheck.check("http://185.22.11.4/pay").score >= 50)
        assertTrue(LinkCheck.check("https://xn--80ak6aa92e.com").score >= 45)
    }
    @Test fun phishingSmsIsHigh() {
        val r = ScamAnalyzer.analyze("Ваша карта заблокирована. Срочно подтвердите данные: https://kaspi-verify.top/login")
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.tactics.any { it.id == "phishing_link" })
    }
    @Test fun forgedBankSiteAloneIsHigh() {
        assertEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("Kaspi: https://kaspi-bonus.top/login").level)
        assertEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("Your Kaspi account is blocked. Verify now: https://kaspi-verify.top/login").level)
        assertNotEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("Скидки тут https://bit.ly/abc").level)   // a short link alone is not a verdict
    }
    @Test fun normalLinkIsNotATactic() {
        val r = ScamAnalyzer.analyze("Смотри фото с дня рождения https://photos.google.com/share/abc")
        assertEquals(RiskLevel.LOW, r.level)
    }
}

/** The demo scenario uses made-up input but real analyzers: these tests pin what the presentation will show. */
class DemoScenarioTest {
    @Test fun unknownInternationalCallIsMedium() {
        val r = CallRisk.assess(DemoScenario.callFacts("+7"))
        assertEquals(RiskLevel.MEDIUM, r.level)
    }
    @Test fun riskGrowsDuringTheCallAndEndsHigh() {
        val first = ScamAnalyzer.analyze(DemoScenario.callerText(1))
        val all = ScamAnalyzer.analyze(DemoScenario.callerText(DemoScenario.CALL.size))
        assertNotEquals(RiskLevel.HIGH, first.level)
        assertEquals(RiskLevel.HIGH, all.level)
        assertTrue(all.tactics.any { it.id == "credentials_request" })
        assertTrue(all.tactics.any { it.id == "safe_account_transfer" })
    }
    @Test fun smsCodeAndPhishingAreCaught() {
        assertEquals("4821", SmsCode.find(DemoScenario.SMS)?.code)
        val p = ScamAnalyzer.analyze(DemoScenario.PHISHING)
        assertEquals(RiskLevel.HIGH, p.level)
        assertTrue(p.tactics.any { it.id == "phishing_link" })
    }
    @Test fun normalMessageIsLow() {
        assertEquals(RiskLevel.LOW, ScamAnalyzer.analyze(DemoScenario.NORMAL).level)
    }
}

class VideoChallengesTest {
    // a face frame every 333 ms; yaw/eyes/area set per moment
    private fun frames(totalMs: Long, f: (Long) -> ScreenFace): List<ScreenFace> = (0..totalMs step 333).map(f)
    private fun face(t: Long, yaw: Float = 0f, eyes: Float = 0.9f, area: Float = 0.12f, faces: Int = 1, jitter: Boolean = true) =
        ScreenFace(t, faces, area, 0.5f + (if (jitter) ((t / 333) % 3 - 1) * 0.01f else 0f), 0.4f,
            yaw + (if (jitter) ((t / 333) % 5 - 2).toFloat() else 0f), eyes, eyes)
    private val plan = listOf(Challenge(ChallengeKind.TURN, 5_000, 14_000), Challenge(ChallengeKind.HAND, 14_000, 23_000))

    @Test fun planIsTwoDifferentChallengesInARow() {
        val p = VideoChallenges.plan(kotlin.random.Random(1))
        assertEquals(2, p.size); assertNotEquals(p[0].kind, p[1].kind)
        assertEquals(p[0].endMs, p[1].startMs)
        assertEquals(p[1], VideoChallenges.current(p, p[1].startMs + 10))
    }

    @Test fun livePersonFollowingRequestsPasses() {
        val fr = frames(30_000) { t ->
            when {
                t in 7_000..8_500 -> face(t, yaw = 30f)                        // turns the head
                t in 16_000..16_700 -> face(t, faces = 0, area = 0f)           // hand covers the face
                t % 4_000 < 333 -> face(t, eyes = 0.1f)                        // ordinary blinks
                else -> face(t)
            }
        }
        val r = VideoChallenges.checkAll(fr, plan)
        assertTrue(r.all { it.passed })
        val v = VideoCallCheck.evaluate(fr, r)
        assertEquals(RiskLevel.LOW, v.level)
        assertTrue(v.reasons.first().contains("2 из 2"))
    }

    @Test fun movingRecordingThatIgnoresRequestsIsMedium() {
        val fr = frames(30_000) { t -> if (t % 4_000 < 333) face(t, eyes = 0.1f) else face(t) }   // blinks and jitters, never reacts
        val r = VideoChallenges.checkAll(fr, plan)
        assertTrue(r.none { it.passed })
        assertEquals(RiskLevel.MEDIUM, VideoCallCheck.evaluate(fr, r).level)
    }

    @Test fun photoStaysHighAndFailsChallenges() {
        val fr = frames(30_000) { t -> face(t, jitter = false) }
        val r = VideoChallenges.checkAll(fr, plan)
        assertTrue(r.none { it.passed })
        assertEquals(RiskLevel.HIGH, VideoCallCheck.evaluate(fr, r).level)
    }

    @Test fun closingEyesLongerThanABlink() {
        val eyes = Challenge(ChallengeKind.EYES, 5_000, 14_000)
        val blinkOnly = frames(20_000) { t -> if (t % 4_000 < 333) face(t, eyes = 0.1f) else face(t) }
        assertFalse(VideoChallenges.check(blinkOnly, eyes).passed)
        val closed = frames(20_000) { t -> if (t in 8_000..9_200) face(t, eyes = 0.05f) else face(t) }
        assertTrue(VideoChallenges.check(closed, eyes).passed)
    }
}
