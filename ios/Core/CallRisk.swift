import Foundation

/// Facts about one phone call that the app can legitimately know.
public struct CallFacts {
    public var number: String?
    public var inContacts: Bool
    public var homeCallingCode: String
    public var callsFromNumberLastHour: Int
    public var userMarkedScam: Bool
    public var userMarkedSafe: Bool
    public init(number: String?, inContacts: Bool = false, homeCallingCode: String = "+7", callsFromNumberLastHour: Int = 1,
                userMarkedScam: Bool = false, userMarkedSafe: Bool = false) {
        self.number = number; self.inContacts = inContacts; self.homeCallingCode = homeCallingCode
        self.callsFromNumberLastHour = callsFromNumberLastHour; self.userMarkedScam = userMarkedScam; self.userMarkedSafe = userMarkedSafe
    }
}

public struct CallRiskResult { public let score: Double; public let level: RiskLevel; public let reasons: [String]; public let advice: [String] }

/// Risk of an incoming call from the number alone (same rules as the Android app, core/CallRisk.kt).
/// It cannot know whether a stranger is a scammer; it only says how much caution fits.
public enum CallRisk {
    public static func normalize(_ raw: String?) -> String? {
        guard let raw, !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let d = raw.filter { $0.isNumber || $0 == "+" }
        return d.isEmpty ? nil : d
    }

    public static func assess(_ f: CallFacts) -> CallRiskResult {
        if f.userMarkedSafe { return CallRiskResult(score: 0, level: .low, reasons: ["Вы отметили этот номер как надёжный"], advice: []) }
        var reasons: [(String, Int)] = []
        if f.userMarkedScam { reasons.append(("Вы ранее отметили этот номер как мошеннический", 80)) }
        if let n = normalize(f.number) {
            if !f.inContacts { reasons.append(("Номера нет в ваших контактах", 10)) }
            if (n.hasPrefix("+") && !n.hasPrefix(f.homeCallingCode)) || n.hasPrefix("00") { reasons.append(("Международный номер (\(n))", 25)) }
            if f.callsFromNumberLastHour >= 3 { reasons.append(("Звонит уже \(f.callsFromNumberLastHour)-й раз за час", 20)) }
            var local = n
            if local.hasPrefix(f.homeCallingCode) { local.removeFirst(f.homeCallingCode.count) }
            if local.hasPrefix("+") { local.removeFirst() }
            if !f.inContacts && (3...6).contains(local.count) { reasons.append(("Короткий номер: мошенники подделывают номера банков", 10)) }
        } else {
            reasons.append(("Номер скрыт", 30))
        }
        var score = reasons.isEmpty ? 0 : 100 * (1 - reasons.reduce(1.0) { $0 * (1 - Double($1.1) / 100) })
        if f.userMarkedScam { score = max(score, 80) }
        let advice = f.inContacts ? [] : [
            "Если звонящий представится банком или полицией — положите трубку и перезвоните сами по официальному номеру.",
            "Никому не называйте коды из SMS."]
        return CallRiskResult(score: (score * 10).rounded() / 10, level: .of(score), reasons: reasons.map(\.0), advice: advice)
    }
}

/// One-time codes in SMS ("Код: 482913", "Your code is 1234"). The code itself is only ever shown masked.
public enum SmsCode {
    static let keyword = try! NSRegularExpression(pattern: "(код|code|пароль|password|otp|parol|құпия|kod|подтвержд|никому)", options: [.caseInsensitive])
    static let codeRx = try! NSRegularExpression(pattern: "(?<![0-9])([0-9]{4,8})(?![0-9])")

    public struct Found { public let code: String; public let masked: String }

    public static func find(_ text: String) -> Found? {
        let t = text.lowercased(), ns = t as NSString, all = NSRange(location: 0, length: ns.length)
        guard keyword.firstMatch(in: t, range: all) != nil, let m = codeRx.firstMatch(in: t, range: all) else { return nil }
        let c = ns.substring(with: m.range(at: 1))
        return Found(code: c, masked: String(c.prefix(1)) + String(repeating: "•", count: max(1, c.count - 2)) + String(c.suffix(1)))
    }
}
