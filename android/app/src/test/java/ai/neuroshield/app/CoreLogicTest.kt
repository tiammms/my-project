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

    @Test fun newSchemes() {
        val cases = listOf(
            Triple("Мам, у меня новый номер, старый телефон украли. Кинь, пожалуйста, 30 тысяч на эту карту", RiskLevel.HIGH, "family_emergency"),
            Triple("Пассивный доход от 15% в неделю, вложите 50 000 тенге", RiskLevel.MEDIUM, "investment_promise"),
            Triple("Вы выиграли автомобиль! Чтобы забрать приз, оплатите налог 25 000 тенге.", RiskLevel.HIGH, "fee_request"),
            Triple("Оставайтесь на линии, сейчас подключится специалист банка", RiskLevel.MEDIUM, "urgency_pressure"),
            Triple("апа бұл мен балаңыз жол апатына түстім тез ақша жіберіңізші", RiskLevel.HIGH, "family_emergency"),
        )
        for ((text, level, id) in cases) {
            val r = ScamAnalyzer.analyze(text)
            assertEquals(text, level, r.level)
            assertTrue(r.tactics.any { it.id == id })
        }
    }

    @Test fun newSchemesNoFalseAlarms() {
        for (t in listOf("Переведи мне 500 тенге за обед", "Не клади трубку, сейчас передам телефон папе",
                "Мы выиграли матч со счётом 3:1", "На паре обсуждали инвестиции и риски", "Посылка придёт завтра")) {
            assertEquals(t, RiskLevel.LOW, ScamAnalyzer.analyze(t).level)
        }
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
        assertEquals(RiskLevel.HIGH, ScamAnalyzer.analyze("Kaspi: https://kaspi-bonus.example/login").level)
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

/** The automatic chain for one call, from known scam and safe scenarios to the report shown after hang-up. */
class CallSessionTest {
    private fun pre(number: String?, home: String = "+7", prior: RiskLevel? = null, messenger: String? = null) =
        CallRisk.assess(CallFacts(number, false, home, 1, false, false, messenger = messenger, priorMessageLevel = prior))

    private fun session(number: String?, channel: CallChannel = CallChannel.CELLULAR, app: String? = null, p: CallRiskResult = pre(number)) =
        CallSession().also { it.on(CallEvent.Ringing(0, number, channel, app, p)) }

    @Test fun bankScamCallWithCodeAndScamSpeechIsHigh() {
        val s = session("+447700900123")
        s.on(CallEvent.Answered(5_000))
        s.on(CallEvent.SpeechState(6_000, SpeechStatus.STARTED))
        s.on(CallEvent.Speech(30_000, ScamAnalyzer.analyze("служба безопасности банка, срочно продиктуйте код из смс")))
        s.on(CallEvent.SmsCodeArrived(40_000, "Kaspi.kz", "4••1"))
        s.on(CallEvent.Ended(125_000))
        val r = s.report()!!
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.title.startsWith("Возможное мошенничество"))
        assertTrue(r.findings.any { it.contains("код из SMS") })
        assertTrue(r.findings.any { it.startsWith("В разговоре:") })
        assertTrue(r.findings.any { it.contains("Международный номер") })
        assertEquals(120, r.durationSec)
        assertTrue(r.checks.any { it.startsWith("Анализ речи: запущен") })
        assertTrue(r.advice.isNotEmpty())
    }

    @Test fun ordinaryUnknownCallStaysLowAndSaysNothingFound() {
        val s = session("+77015550123")
        s.on(CallEvent.Answered(3_000))
        s.on(CallEvent.SpeechState(4_000, SpeechStatus.STARTED))
        s.on(CallEvent.Speech(20_000, ScamAnalyzer.analyze("здравствуйте, это курьер, буду у вас через час")))
        s.on(CallEvent.Ended(60_000))
        val r = s.report()!!
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.title.contains("признаков мошенничества не найдено"))
        assertFalse(r.findings.any { it.startsWith("В разговоре:") })
    }

    @Test fun singleUrgencyWordDoesNotRaiseRisk() {
        val s = session("+77015550124")
        s.on(CallEvent.Answered(1_000))
        s.on(CallEvent.Speech(5_000, ScamAnalyzer.analyze("перезвони срочно, когда сможешь")))
        s.on(CallEvent.Ended(9_000))
        assertEquals(RiskLevel.LOW, s.report()!!.level)
    }

    @Test fun missedCallIsReportedWithoutSpeechCheck() {
        val s = session("+77015550125")
        s.on(CallEvent.Ended(20_000))
        val r = s.report()!!
        assertFalse(r.answered)
        assertTrue(r.title.startsWith("Пропущенный"))
        assertTrue(r.checks.any { it == "Анализ речи: не нужен: звонок не принят" })
    }

    @Test fun reportIsHonestAboutWhatWasNotChecked() {
        val s = session("+77015550126")
        s.on(CallEvent.Answered(1_000))
        s.on(CallEvent.SpeechState(1_500, SpeechStatus.NO_MODEL))
        s.on(CallEvent.Ended(10_000))
        val r = s.report()!!
        assertTrue(r.checks.any { it.contains("модель распознавания речи не скачана") })
        assertTrue(r.checks.any { it.startsWith("Внешняя база мошеннических номеров: не подключена") })
        assertTrue(r.checks.any { it.contains("Синтетический") && it.contains("не проверялся") })
    }

    @Test fun silenceOverridesStartedSoTheUserKnowsTheCallWasNotHeard() {
        val s = session("+77015550127")
        s.on(CallEvent.Answered(1_000))
        s.on(CallEvent.SpeechState(2_000, SpeechStatus.STARTED))
        s.on(CallEvent.SpeechState(8_000, SpeechStatus.SILENCE))
        s.on(CallEvent.SpeechState(9_000, SpeechStatus.STARTED))
        s.on(CallEvent.Ended(20_000))
        assertTrue(s.report()!!.checks.any { it.contains("тишину") })
    }

    @Test fun messengerCallAfterScamMessageIsRiskyAndAudioIsNotClaimed() {
        val s = session("+447911123456", CallChannel.MESSENGER, "WhatsApp", pre("+447911123456", prior = RiskLevel.HIGH, messenger = "WhatsApp"))
        s.on(CallEvent.Answered(4_000))
        s.on(CallEvent.Ended(50_000))
        val r = s.report()!!
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.checks.any { it.contains("звук звонков в мессенджерах закрыт") })
    }

    @Test fun codeDuringCallAloneMakesItHigh() {
        val s = session("+77015550128")
        s.on(CallEvent.Answered(1_000))
        s.on(CallEvent.SmsCodeArrived(30_000, "1414", "4••3"))
        s.on(CallEvent.Ended(90_000))
        val r = s.report()!!
        assertEquals(RiskLevel.HIGH, r.level)
        assertEquals("Возможное мошенничество: код из SMS во время звонка", r.title)
    }

    @Test fun noRingingMeansNoReport() {
        val s = CallSession(); s.on(CallEvent.Answered(1)); s.on(CallEvent.Ended(2))
        assertNull(s.report())
    }

    @Test fun pluggableModelsDefaultToNotConnected() {
        assertFalse(SyntheticVoiceDetector.NotConnected.available)
        assertNull(SyntheticVoiceDetector.NotConnected.score(ShortArray(10), 10))
        assertEquals(ReputationSources.NOT_CONNECTED, ReputationSources.status())
        assertEquals(RiskLevel.HIGH, ScamTextClassifier.RuleBased.analyze("назовите код из смс, переведите деньги на безопасный счёт").level)
    }
}

class RiskEngineTest {
    @Test fun combinesIndependentSignalsAndListsWhatWasNotChecked() {
        val a = RiskEngine.combine(listOf(
            RiskSignal("a", "Международный номер", 25), RiskSignal("b", "Нет в контактах", 10),
            RiskSignal("c", "Внешняя база номеров", 0, available = false)))
        assertTrue(kotlin.math.abs(a.score - 32.5) < 0.1)
        assertEquals(RiskLevel.MEDIUM, a.level)
        assertEquals(listOf("Международный номер", "Нет в контактах"), a.reasons)
        assertEquals(listOf("Внешняя база номеров"), a.notChecked)
        assertTrue(a.confidence < 1.0)
    }
    @Test fun confidenceLowersTheWeightOfUncertainEvidence() {
        val sure = RiskEngine.combine(listOf(RiskSignal("x", "x", 60, 1.0))).score
        val unsure = RiskEngine.combine(listOf(RiskSignal("x", "x", 60, 0.5))).score
        assertTrue(unsure < sure); assertTrue(kotlin.math.abs(unsure - 30.0) < 0.01)
    }
    @Test fun noSignalsIsZero() = assertEquals(RiskLevel.LOW, RiskEngine.combine(emptyList()).level)
    @Test fun thresholdsAreConfigurable() {
        val s = listOf(RiskSignal("x", "x", 50))
        assertEquals(RiskLevel.MEDIUM, RiskEngine.combine(s).level)
        assertEquals(RiskLevel.HIGH, RiskEngine.combine(s, RiskThresholds(medium = 20.0, high = 50.0)).level)
        assertEquals(RiskLevel.LOW, RiskEngine.combine(s, RiskThresholds(medium = 60.0, high = 90.0)).level)
    }
    @Test fun invalidThresholdsRejected() { assertTrue(runCatching { RiskThresholds(medium = 80.0, high = 50.0) }.exceptionOrNull() is IllegalArgumentException) }
}

/** Stage 7 scenarios for ordinary calls: known suspicious number, ordinary unknown, contact, no internet, local reputation. */
class CallScenarioTest {
    private fun facts(n: String?, contact: Boolean = false) = CallFacts(n, contact, "+7", 1, false, false)

    @Test fun knownSuspiciousNumberMarkedByUser() =
        assertEquals(RiskLevel.HIGH, CallRisk.assess(facts("+77015550000").copy(userMarkedScam = true)).level)

    @Test fun numberWithEarlierHighReportIsRaised() {
        val before = CallRisk.assess(facts("+77015550001"))
        val after = CallRisk.assess(facts("+77015550001").copy(highReports = 1))
        assertEquals(RiskLevel.LOW, before.level)
        assertEquals(RiskLevel.MEDIUM, after.level)
        assertTrue(after.reasons.any { it.text.startsWith("Прошлый звонок с этого номера был оценён как высокий риск") })
    }

    @Test fun localReputationNeverOverridesTheUsersSafeMark() {
        val r = CallRisk.assess(facts("+77015550002").copy(highReports = 3, userMarkedSafe = true))
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(LocalReputation.signals(NumberHistory(userMarkedSafe = true, highReports = 3)).isEmpty())
    }

    @Test fun ordinaryUnknownLocalNumberIsLow() {
        val r = CallRisk.assess(facts("+77015550003"))
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.score < RiskEngine.thresholds.medium)
    }

    @Test fun contactIsNotSuspicious() {
        val r = CallRisk.assess(facts("+447911123456", contact = true))
        assertTrue(r.reasons.none { it.text.contains("контактах") })
        assertTrue(r.advice.isEmpty())
    }

    @Test fun noInternetOrBrokenOutsideSourceDoesNotBreakTheCheck() {
        val broken = object : ReputationSource {
            override val name = "Тестовый источник без сети"
            override fun lookup(normalizedNumber: String): ReputationHit? = throw java.io.IOException("no network")
        }
        ReputationSources.remote += broken
        try {
            assertTrue(ReputationSources.lookup("+77015550004").isEmpty())   // the failure is swallowed, the call is not delayed
            assertEquals(RiskLevel.LOW, CallRisk.assess(facts("+77015550004")).level)
        } finally { ReputationSources.remote -= broken }
        assertEquals(ReputationSources.NOT_CONNECTED, ReputationSources.status())
    }

    @Test fun missingMicrophonePermissionIsReportedNotHidden() {
        val s = CallSession(); s.on(CallEvent.Ringing(0, "+77015550005", CallChannel.CELLULAR, null, CallRisk.assess(facts("+77015550005"))))
        s.on(CallEvent.Answered(1)); s.on(CallEvent.SpeechState(2, SpeechStatus.NO_PERMISSION)); s.on(CallEvent.Ended(3))
        assertTrue(s.report()!!.checks.any { it.contains("нет разрешения на микрофон") })
    }

    @Test fun whatsappCallDetectedAndNotDetected() {
        assertTrue(Messenger.parse("com.whatsapp", "+44 7911 123456", "Входящий голосовой звонок", true) is MessengerEvent.IncomingCall)
        // a call whose notification says nothing about a call and is not in the call category is not seen as a call
        assertFalse(Messenger.parse("com.whatsapp", "+44 7911 123456", "Привет", false) is MessengerEvent.IncomingCall)
        // the number is hidden for contacts: the app does not pretend to know it
        assertNull((Messenger.parse("com.whatsapp", "Мама", "Входящий голосовой звонок", true) as MessengerEvent.IncomingCall).number)
    }
}

/** VoiceRiskAnalyzer on labelled transcripts (team-written, not real calls). */
class VoiceRiskAnalyzerTest {
    private val scam = listOf(
        "здравствуйте вас беспокоит служба безопасности банка продиктуйте код из смс",
        "переведите все деньги на безопасный счёт иначе их спишут",
        "назовите номер карты и три цифры на обороте",
        "this is the bank security team read me the verification code right now",
        "банктің қауіпсіздік қызметі кодты айтыңыз",
    )
    private val safe = listOf(
        "привет купи хлеба по дороге домой",
        "здравствуйте это курьер буду у вас через час",
        "перезвони срочно когда сможешь",
        "see you at the library at five",
        "сәлем кешке үйге келесің бе",
    )

    @Test fun labelledScamTranscriptsAreFlagged() {
        val a = VoiceRiskAnalyzer()
        scam.forEach { t -> assertEquals(t to true, t to (a.analyze(t).assessment.level != RiskLevel.LOW)) }
    }

    @Test fun labelledSafeTranscriptsStayLow() {
        val a = VoiceRiskAnalyzer()
        safe.forEach { t -> assertEquals(t to RiskLevel.LOW, t to a.analyze(t).assessment.level) }
    }

    @Test fun reasonsQuoteThePhraseHeard() {
        val r = VoiceRiskAnalyzer().analyze("служба безопасности банка, продиктуйте код из смс")
        assertTrue(r.assessment.reasons.any { "«" in it })
        assertNull(r.syntheticProbability)
        assertTrue(r.assessment.notChecked.any { it.contains("Синтетический голос") })
    }

    @Test fun experimentalSyntheticDetectorCanNeverRaiseToHighAlone() {
        val fake = object : SyntheticVoiceDetector {
            override val available = true
            override fun score(pcm: ShortArray, size: Int) = 1.0
        }
        val a = VoiceRiskAnalyzer(synthetic = fake)
        a.onAudio(ShortArray(16), 16)
        val r = a.analyze("здравствуйте это курьер")
        assertTrue(r.syntheticProbability == 1.0)
        assertNotEquals(RiskLevel.HIGH, r.assessment.level)
        assertTrue(r.assessment.reasons.any { it.startsWith("Возможен синтетический голос") })
    }

    @Test fun classifierIsReplaceable() {
        val always = ScamTextClassifier { TextRisk(90.0, RiskLevel.HIGH, emptyList(), 1) }
        assertEquals(RiskLevel.HIGH, VoiceRiskAnalyzer(classifier = always).analyze("что угодно").assessment.level)
    }
}

/** Kazakh interface: every line of a call report and every tactic is shown in Kazakh, Russian stays the default. */
class I18nTest {
    private val kazakhLetters = Regex("[әғқңөұүһіӘҒҚҢӨҰҮҺІ]")
    private fun assertKazakh(ru: String) {
        val kk = I18n.core(ru)
        assertEquals("not translated: $ru -> $kk", true, kk != ru && kazakhLetters.containsMatchIn(kk))
    }

    private val allTactics = "служба безопасности банка, срочно продиктуйте код из смс, переведите все деньги на безопасный счет, " +
        "установите приложение anydesk, никому не говорите, ваша карта заблокирована. мама, я в беде, сбил человека, " +
        "кинь 30 тысяч на эту карту. пассивный доход 15% в неделю. вы выиграли приз, оплатите налог. https://kaspi-bonus.example/verify"

    @Test fun russianIsDefaultAndUnchanged() {
        I18n.kk = false
        assertEquals("Возможное мошенничество", I18n.core("Возможное мошенничество"))
        assertEquals("Да", tr("Да", "Иә"))
    }

    @Test fun reportsAndTacticsAreTranslated() {
        I18n.kk = true
        try {
            assertEquals("Иә", tr("Да", "Иә"))
            val r = ScamAnalyzer.analyze(allTactics)
            assertEquals(13, r.tactics.size)
            r.tactics.forEach { assertKazakh(it.title); assertKazakh(it.advice) }
            assertKazakh(r.summary)
            for (l in RiskLevel.values()) assertKazakh(l.ru)

            fun pre(n: String?, messenger: String? = null) = CallRisk.assess(CallFacts(n, false, "+7", 4, false, false,
                messenger = messenger, priorMessageLevel = RiskLevel.HIGH, priorTactics = listOf("Просят код, пароль или данные карты"), highReports = 2))
            val sessions = listOf(
                CallSession().apply {
                    on(CallEvent.Ringing(0, "+447700900123", CallChannel.CELLULAR, null, pre("+447700900123")))
                    on(CallEvent.Answered(5_000)); on(CallEvent.SpeechState(6_000, SpeechStatus.STARTED))
                    on(CallEvent.Speech(30_000, ScamAnalyzer.analyze("служба безопасности банка, срочно продиктуйте код из смс")))
                    on(CallEvent.SmsCodeArrived(40_000, "Kaspi.kz", "4••1")); on(CallEvent.Ended(125_000))
                },
                CallSession().apply { on(CallEvent.Ringing(0, null, CallChannel.CELLULAR, null, pre(null))); on(CallEvent.Ended(9_000)) },
                CallSession().apply {
                    on(CallEvent.Ringing(0, "+77015550123", CallChannel.MESSENGER, "WhatsApp", pre("+77015550123", "WhatsApp")))
                    on(CallEvent.Answered(2_000)); on(CallEvent.Ended(60_000))
                },
                CallSession().apply {
                    on(CallEvent.Ringing(0, "87015550123", CallChannel.CELLULAR, null, CallRisk.assess(CallFacts("87015550123", false, "+7", 1, false, false))))
                    on(CallEvent.Answered(2_000)); on(CallEvent.SpeechState(3_000, SpeechStatus.SILENCE)); on(CallEvent.Ended(60_000))
                },
            )
            for (s in sessions) {
                val rep = s.report()!!
                (listOf(rep.title) + rep.findings + rep.checks + rep.advice).forEach { assertKazakh(it) }
            }
            pre("+447700900123").let { (it.reasons.map { r -> r.text } + it.advice).forEach { assertKazakh(it) } }
            pre("+77015550123", "WhatsApp").let { (it.reasons.map { r -> r.text } + it.advice).forEach { assertKazakh(it) } }
        } finally { I18n.kk = false }
    }

    @Test fun dictionaryHasNoEmptyOrUntranslatedEntries() {
        I18n.entries().forEach { (ru, kk) -> assertEquals(ru, true, kk.isNotBlank() && kk != ru) }
    }
}

/** Call recordings are stored encrypted in 64 KB chunks: round trip, and any change or cut is detected. */
class ChunkedAeadTest {
    private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun seal(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ChunkedAead.Writer(out, key).use { it.write(data) }
        return out.toByteArray()
    }
    private fun open(sealed: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ChunkedAead.decrypt(java.io.ByteArrayInputStream(sealed), key, out)
        return out.toByteArray()
    }
    private fun fails(block: () -> Unit): Boolean = try { block(); false } catch (e: Exception) { true }

    @Test fun roundTripOfSeveralChunksAndEmpty() {
        val data = ByteArray(200_000) { (it * 7).toByte() }
        assertEquals(true, open(seal(data)).contentEquals(data))
        assertEquals(0, open(seal(ByteArray(0))).size)
        assertEquals(true, open(seal(ByteArray(ChunkedAead.CHUNK) { 1 })).contentEquals(ByteArray(ChunkedAead.CHUNK) { 1 }))
    }

    @Test fun changedOrCutFileIsRejected() {
        val sealed = seal(ByteArray(150_000) { it.toByte() })
        assertEquals(true, fails { open(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }) })
        assertEquals(true, fails { open(sealed.copyOf(sealed.size - 30)) })
        val firstChunk = 4 + 12 + ChunkedAead.CHUNK + 16
        assertEquals(true, fails { open(sealed.copyOf(firstChunk)) })      // file cut after a whole chunk
        val other = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(true, fails { ChunkedAead.decrypt(java.io.ByteArrayInputStream(sealed), other, java.io.ByteArrayOutputStream()) })
    }
}
