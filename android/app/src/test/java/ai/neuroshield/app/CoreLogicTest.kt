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
