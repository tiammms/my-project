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
    public var summary: String {
        let n = tactics.filter { !ScamAnalyzer.isWeak($0.id) }.count
        return n == 0 ? "Признаков мошеннического сценария не найдено" : "Найдено признаков обмана: \(n)"
    }
}

/// Rule-based detector of social-engineering tactics in what is SAID or WRITTEN (Russian, Kazakh, English).
/// Transparent on purpose: every warning shows the phrase that triggered it. It judges the content, never the person.
/// Same rules as the Android app (core/ScamAnalyzer.kt) and the server (backend/app/biometrics/scam_text.py).
public enum ScamAnalyzer {
    // The phrase rules live in shared/scam_rules.json (one source for iOS, Android and the server);
    // ScamRules.swift is generated from it by scripts/gen_scam_rules.py.

    // ICU regex: \w and \b are Unicode-aware, so Cyrillic and Kazakh letters work as in the other versions.
    static let compiled: [(ScamRule, [NSRegularExpression])] = ScamRules.categories.map { c in
        (c, c.patterns.compactMap { try? NSRegularExpression(pattern: $0, options: [.caseInsensitive]) })
    }

    public static let maxText = 20_000

    static let weakIds = Set(ScamRules.categories.filter(\.weak).map(\.id))
    public static func isWeak(_ id: String) -> Bool { weakIds.contains(id) }

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
        // "weak" categories (only mentioning a bank, a card, a code) are never counted as a separate tactic
        let ids = Set(found.map(\.id).filter { !isWeak($0) })
        // ...and never change the risk level: they only move the score within the level the real tactics give
        func comb(_ l: [Tactic]) -> Double { l.isEmpty ? 0 : 100 * (1 - l.reduce(1.0) { $0 * (1 - Double($1.weight) / 100) }) }
        let base = comb(found.filter { !isWeak($0.id) }), full = comb(found)
        var score = RiskLevel.of(full) == RiskLevel.of(base) ? full : base
        // A single tactic alone never yields HIGH — except a forged bank / government site.
        let forgedSite = links.contains { $0.score >= 70 }
        if ids.count <= 1 && !forgedSite { score = min(score, 45) }
        if forgedSite { score = max(score, 75) }
        let critical = !ids.isDisjoint(with: ScamRules.critical) || links.contains { $0.score >= 50 }
        if critical && ids.count >= 2 { score = max(score, 75) }
        let words = t.isEmpty ? 0 : t.split(separator: " ").count
        return TextRisk(score: (score * 10).rounded() / 10, level: .of(score), tactics: found, words: words)
    }
}
