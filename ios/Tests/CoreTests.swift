import XCTest

/// Same expectations as the Android unit tests (CoreLogicTest.kt) and the server tests: the three versions must agree.
final class ScamAnalyzerTests: XCTestCase {
    func testClassicBankScamIsHigh() {
        let r = ScamAnalyzer.analyze("Здравствуйте, служба безопасности банка. Срочно продиктуйте код из СМС и никому не говорите.")
        XCTAssertEqual(r.level, .high)
        XCTAssertTrue(r.tactics.contains { $0.id == "credentials_request" })
    }
    func testEverydayTextIsLow() {
        XCTAssertEqual(ScamAnalyzer.analyze("Привет, во сколько встречаемся завтра у кинотеатра?").level, .low)
    }
    func testSingleTacticNeverHigh() {
        XCTAssertNotEqual(ScamAnalyzer.analyze("Срочно перезвони, пожалуйста").level, .high)
    }
    func testKazakhAndEnglish() {
        XCTAssertFalse(ScamAnalyzer.analyze("Кодты айтыңыз, ешкімге айтпаңыз").tactics.isEmpty)
        XCTAssertEqual(ScamAnalyzer.analyze("This is the bank security team. Tell me the verification code right now.").level, .high)
    }
    func testEvidenceIsShown() {
        let r = ScamAnalyzer.analyze("Продиктуйте код из смс")
        XCTAssertFalse(r.tactics.first?.evidence.isEmpty ?? true)
    }
}

final class LinkCheckTests: XCTestCase {
    func testLookalikeBrand() {
        let f = LinkCheck.check("https://kaspi-verify.top/login")
        XCTAssertGreaterThanOrEqual(f.score, 70)
        XCTAssertTrue(f.reasons.contains { $0.contains("Kaspi") })
    }
    func testOfficialDomainsAreClean() {
        for u in ["https://kaspi.kz/shop", "https://www.halykbank.kz", "https://egov.kz/cms/ru", "https://t.me/neuroshield"] {
            XCTAssertEqual(LinkCheck.check(u).score, 0, u)
        }
    }
    func testShortenerIpPunycode() {
        XCTAssertGreaterThanOrEqual(LinkCheck.check("https://bit.ly/3xYz").score, 35)
        XCTAssertGreaterThanOrEqual(LinkCheck.check("http://185.22.11.4/pay").score, 50)
        XCTAssertGreaterThanOrEqual(LinkCheck.check("https://xn--80ak6aa92e.com").score, 45)
    }
    func testForgedSiteAloneIsHigh() {
        XCTAssertEqual(ScamAnalyzer.analyze("Kaspi: https://kaspi-bonus.top/login").level, .high)
        XCTAssertNotEqual(ScamAnalyzer.analyze("Скидки тут https://bit.ly/abc").level, .high)
        XCTAssertEqual(ScamAnalyzer.analyze("Смотри фото с дня рождения https://photos.google.com/share/abc").level, .low)
    }
}

final class CallRiskTests: XCTestCase {
    func testInternationalUnknownIsMedium() {
        XCTAssertEqual(CallRisk.assess(CallFacts(number: "+447700900123")).level, .medium)
    }
    func testHiddenAndMarked() {
        XCTAssertEqual(CallRisk.assess(CallFacts(number: nil)).level, .medium)
        XCTAssertEqual(CallRisk.assess(CallFacts(number: "+77015550123", userMarkedScam: true)).level, .high)
        XCTAssertEqual(CallRisk.assess(CallFacts(number: "+77015550123", userMarkedSafe: true)).level, .low)
    }
    func testSmsCodeIsMasked() {
        let f = SmsCode.find("Kaspi.kz: код подтверждения 4821. Никому не сообщайте")
        XCTAssertEqual(f?.code, "4821"); XCTAssertEqual(f?.masked, "4••1")
        XCTAssertNil(SmsCode.find("Встречаемся в 1830 у входа"))
    }
}

final class DemoScenarioTests: XCTestCase {
    func testDemoShowsRealVerdicts() {
        XCTAssertEqual(CallRisk.assess(DemoScenario.callFacts()).level, .medium)
        XCTAssertNotEqual(ScamAnalyzer.analyze(DemoScenario.callerText(1)).level, .high)
        let all = ScamAnalyzer.analyze(DemoScenario.callerText(DemoScenario.call.count))
        XCTAssertEqual(all.level, .high)
        XCTAssertTrue(all.tactics.contains { $0.id == "safe_account_transfer" })
        XCTAssertEqual(SmsCode.find(DemoScenario.sms)?.code, "4821")
        XCTAssertEqual(ScamAnalyzer.analyze(DemoScenario.phishing).level, .high)
        XCTAssertEqual(ScamAnalyzer.analyze(DemoScenario.normal).level, .low)
    }
    func testCallKitNumber() {
        XCTAssertEqual(callKitNumber("+7 701 555-01-23"), 77015550123)
    }
}
