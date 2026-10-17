import Foundation

/// Risk level shared by every check. Thresholds are the same as in the Android app and on the server.
public enum RiskLevel: String, Codable, CaseIterable, Comparable {
    case low = "LOW", medium = "MEDIUM", high = "HIGH"
    public var ru: String {
        switch self {
        case .low: return "Низкий риск"
        case .medium: return "Средний риск"
        case .high: return "Высокий риск"
        }
    }
    public var short: String {
        switch self {
        case .low: return "Низкий"
        case .medium: return "Средний"
        case .high: return "Высокий"
        }
    }
    var rank: Int {
        switch self {
        case .low: return 0
        case .medium: return 1
        case .high: return 2
        }
    }
    public static func < (a: RiskLevel, b: RiskLevel) -> Bool { a.rank < b.rank }
    public static func of(_ score: Double) -> RiskLevel { score >= 70 ? .high : score >= 30 ? .medium : .low }
}

public struct Evidence: Codable, Hashable { public let match: String; public let context: String }
public struct Tactic: Codable, Hashable, Identifiable {
    public let id: String; public let title: String; public let advice: String; public let weight: Int; public let evidence: [Evidence]
}
public struct TextRisk: Codable {
    public let score: Double; public let level: RiskLevel; public let tactics: [Tactic]; public let words: Int
    public var summary: String { tactics.isEmpty ? "Признаков мошеннического сценария не найдено" : "Найдено признаков обмана: \(tactics.count)" }
}

/// Rule-based detector of social-engineering tactics in what is SAID or WRITTEN (Russian, Kazakh, English).
/// Transparent on purpose: every warning shows the phrase that triggered it. It judges the content, never the person.
/// Same rules as the Android app (core/ScamAnalyzer.kt) and the server (backend/app/biometrics/scam_text.py).
public enum ScamAnalyzer {
    struct Category { let id: String; let title: String; let advice: String; let weight: Int; let patterns: [String] }

    static let categories: [Category] = [
        Category(id: "credentials_request", title: "Просят код, пароль или данные карты",
                 advice: "Никому не сообщайте коды из SMS, пароли, PIN и CVV. Банк никогда их не спрашивает.", weight: 45, patterns: [
            #"код\w*\s+(из|с|в)\s+(смс|sms|эсэмэс|сообщени)"#,
            #"(продиктуй|назови|скажи|сообщи|скинь|отправь|подтверди|прочитай|зачитай)\w*\s+(мне\s+)?(пожалуйста\s+)?(этот\s+|ваш\s+|свой\s+)?код"#,
            #"код\w*\s+подтвержд"#,
            #"\bcvv\b|\bcvc\b|\bсвв\b|три\s+цифры\s+на\s+обороте|три\s+цифры\s+сзади"#,
            #"пин[\s-]?код|\bpin\b"#,
            #"(ваш|свой|назовите)\s+пароль|пароль\s+от"#,
            #"срок\s+действия\s+карты"#,
            #"номер\s+(вашей\s+)?карты"#,
            #"кодты?\s+(айт|жібер)"#,
            #"құпия\s+сөз"#,
            #"(tell|give|read|send)\s+(me\s+)?(the\s+|your\s+)?(code|password|pin)"#,
            #"verification\s+code|one[\s-]time\s+(code|password)"#,
        ]),
        Category(id: "safe_account_transfer", title: "Просят перевести деньги на «безопасный» или чужой счёт",
                 advice: "«Безопасных» и «резервных» счетов не существует. Не переводите деньги по просьбе звонящего.", weight: 40, patterns: [
            #"(безопасн|резервн|защищенн|специальн|страхов)\w*\s+(сч[её]т|ячейк)"#,
            #"(перевед|переведит|перечисл|отправ)\w*\s+(все\s+|ваши\s+)?(деньги|средства|сбережения|сумм)"#,
            #"(снимит|снять|обналичь)\w*\s+(все\s+)?(деньги|наличн|средства)"#,
            #"через\s+банкомат\s+(на|внес)"#,
            #"задеклариру"#,
            #"қауіпсіз\s+шот"#,
            #"ақша\w*\s+аудар"#,
            #"safe\s+account|secure\s+account|transfer\s+(all\s+)?(your\s+)?(money|funds|savings)"#,
        ]),
        Category(id: "urgency_pressure", title: "Давят срочностью",
                 advice: "Срочность — главный инструмент мошенников. Сделайте паузу: настоящий банк подождёт.", weight: 20, patterns: [
            #"\bсрочно\b"#,
            #"немедленно"#,
            #"прямо\s+сейчас"#,
            #"в\s+течени[ие]\s+(\d+|пят\w*|десят\w*|пятнадцат\w*)\s+минут"#,
            #"(у\s+вас\s+)?(нет|мало)\s+времени"#,
            #"иначе\s+(вы\s+)?(потеря|лишит|деньги|сч[её]т|будет)"#,
            #"пока\s+не\s+поздно"#,
            #"последн\w+\s+(шанс|возможност)"#,
            #"не\s+кладите\s+трубку"#,
            #"жедел|дереу|қазір\s+бірден"#,
            #"\burgent\b|immediately|right\s+now|within\s+\d+\s+minutes|before\s+it'?s\s+too\s+late"#,
        ]),
        Category(id: "authority_impersonation", title: "Представляются банком, полицией или госорганом",
                 advice: "Положите трубку и сами перезвоните в банк по номеру на обратной стороне карты.", weight: 25, patterns: [
            #"служб\w+\s+безопасности(\s+банка)?"#,
            #"(сотрудник|специалист|оператор)\w*\s+(банка|службы)"#,
            #"\bполици\w*"#,
            #"следовател"#,
            #"(национальн|центральн)\w*\s+банк|\bнацбанк|\bцб\s+рф|\bафм\b|\bкнб\b|\bфсб\b|прокуратур|\bмвд\b"#,
            #"банктің\s+қауіпсіздік|полиция\s+қызметкері"#,
            #"(bank'?s?\s+)?security\s+(department|team|service)|\bpolice\b|fraud\s+department"#,
        ]),
        Category(id: "secrecy_isolation", title: "Просят держать разговор в тайне",
                 advice: "Требование никому не говорить — тревожный признак. Обсудите ситуацию с близкими.", weight: 20, patterns: [
            #"никому\s+не\s+(говорите|рассказывайте|сообщайте)"#,
            #"(тайн\w+|секрет\w*)\s+следстви"#,
            #"не\s+(говорите|сообщайте)\s+(родственникам|близким|сотрудникам\s+банка)"#,
            #"это\s+конфиденциальн"#,
            #"ешкімге\s+айтпаңыз"#,
            #"don'?t\s+tell\s+(anyone|your\s+family|the\s+bank)|keep\s+(this|it)\s+(secret|confidential)"#,
        ]),
        Category(id: "remote_access", title: "Просят установить программу или показать экран",
                 advice: "Не устанавливайте программы удалённого доступа по просьбе звонящего.", weight: 35, patterns: [
            #"anydesk|эни\s?деск|teamviewer|тимвьюер|rustdesk|rust\s+desk|quick\s?support"#,
            #"(установит|скачайт|скачать|установить)\w*\s+(приложени|программ)"#,
            #"демонстраци\w+\s+экрана|покажите\s+(ваш\s+)?экран"#,
            #"screen\s+shar|install\s+(the\s+)?(app|application)|remote\s+access"#,
        ]),
        Category(id: "threat_fear", title: "Пугают: блокировка, кредит на ваше имя, уголовное дело",
                 advice: "Не принимайте решения в панике. Проверьте информацию сами в приложении банка.", weight: 15, patterns: [
            #"(ваш\w*\s+)?(сч[её]т|карт\w+)\s+(заблокир|будет\s+заблокир)"#,
            #"(оформ|взял|взят)\w*\s+кредит\s+на\s+(ваше|вас)"#,
            #"подозрительн\w+\s+(операци|перевод)"#,
            #"уголовн|\bарест|соучастни"#,
            #"мошенники\s+пытаются"#,
            #"your\s+(\w+\s+)?(account|card)\s+(is|has\s+been|will\s+be)\s+(blocked|frozen|compromised|suspended)|arrest|loan\s+in\s+your\s+name"#,
        ]),
    ]

    // ICU regex: \w and \b are Unicode-aware, so Cyrillic and Kazakh letters work as in the other versions.
    static let compiled: [(Category, [NSRegularExpression])] = categories.map { c in
        (c, c.patterns.compactMap { try? NSRegularExpression(pattern: $0, options: [.caseInsensitive]) })
    }

    public static let maxText = 20_000

    public static func normalize(_ t: String) -> String {
        t.lowercased().replacingOccurrences(of: "ё", with: "е")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public static func analyze(_ text: String) -> TextRisk {
        let raw = String(text.prefix(maxText))
        let t = normalize(raw)
        let ns = t as NSString
        var found: [Tactic] = []
        for (c, pats) in compiled {
            var hits: [Evidence] = []
            var spans: [NSRange] = []
            outer: for p in pats {
                for m in p.matches(in: t, range: NSRange(location: 0, length: ns.length)) {
                    let r = m.range
                    if spans.contains(where: { r.location < $0.location + $0.length && r.location + r.length > $0.location }) { continue }
                    spans.append(r)
                    let a = max(0, r.location - 40), b = min(ns.length, r.location + r.length + 40)
                    let ctx = (a > 0 ? "…" : "") + ns.substring(with: NSRange(location: a, length: b - a)) + (b < ns.length ? "…" : "")
                    hits.append(Evidence(match: ns.substring(with: r), context: ctx))
                    if hits.count >= 3 { break outer }
                }
            }
            if !hits.isEmpty { found.append(Tactic(id: c.id, title: c.title, advice: c.advice, weight: c.weight, evidence: hits)) }
        }
        // links: look-alike brands, shorteners, IP hosts, punycode, throw-away domains
        let links = LinkCheck.scan(raw).filter { $0.score >= 30 }
        if let worst = links.max(by: { $0.score < $1.score }) {
            found.append(Tactic(id: "phishing_link", title: "Подозрительная ссылка: \(worst.host)",
                                advice: "Не открывайте ссылку и не вводите на ней данные. Зайдите в приложение банка или на сайт сами.",
                                weight: min(60, max(30, worst.score)),
                                evidence: links.prefix(3).map { Evidence(match: $0.host, context: $0.reasons.joined(separator: "; ")) }))
        }
        let ids = Set(found.map(\.id))
        var score = found.isEmpty ? 0 : 100 * (1 - found.reduce(1.0) { $0 * (1 - Double($1.weight) / 100) })
        // A single tactic alone never yields HIGH — except a forged bank / government site.
        let forgedSite = links.contains { $0.score >= 70 }
        if ids.count <= 1 && !forgedSite { score = min(score, 45) }
        if forgedSite { score = max(score, 75) }
        let critical = ids.contains("credentials_request") || ids.contains("safe_account_transfer") || ids.contains("remote_access")
            || links.contains { $0.score >= 50 }
        if critical && ids.count >= 2 { score = max(score, 75) }
        let words = t.isEmpty ? 0 : t.split(separator: " ").count
        return TextRisk(score: (score * 10).rounded() / 10, level: .of(score), tactics: found, words: words)
    }
}
