import Foundation

/// Phishing-link check for SMS and messages. Rule-based, offline, never opens the link.
/// Same rules as the Android app (core/LinkCheck.kt) and the server (link_check.py).
public enum LinkCheck {
    public struct Finding: Hashable { public let url: String; public let host: String; public let score: Int; public let reasons: [String] }

    static let urlRx = try! NSRegularExpression(pattern:
        #"\b((?:https?://|www\.)[^\s<>"'«»]+|(?:[a-z0-9-]+\.)+(?:kz|ru|com|net|org|top|xyz|click|icu|live|online|site|shop|buzz|rest|info|link|io|me)(?:/[^\s<>"'«»]*)?)"#,
        options: [.caseInsensitive])

    /// brand keyword -> its official registrable domains (first = shown to the user)
    static let brands: [(String, [String])] = [
        ("kaspi", ["kaspi.kz"]), ("halyk", ["halykbank.kz", "homebank.kz"]), ("homebank", ["homebank.kz"]),
        ("forte", ["forte.kz", "fortebank.com"]), ("jusan", ["jusan.kz"]), ("bcc", ["bcc.kz"]),
        ("freedom", ["freedombank.kz", "ffin.kz"]), ("egov", ["egov.kz"]), ("olx", ["olx.kz"]),
        ("kolesa", ["kolesa.kz"]), ("krisha", ["krisha.kz"]), ("sber", ["sberbank.ru", "sber.ru", "sberbank.kz"]),
        ("tinkoff", ["tinkoff.ru", "tbank.ru"]), ("gosuslugi", ["gosuslugi.ru"]), ("whatsapp", ["whatsapp.com", "wa.me"]),
        ("telegram", ["telegram.org", "t.me"]), ("google", ["google.com", "google.kz"]), ("apple", ["apple.com"]),
    ]
    static let shorteners: Set<String> = ["bit.ly", "clck.ru", "tinyurl.com", "goo.su", "cutt.ly", "is.gd", "t.co", "rb.gy", "u.to", "vk.cc", "shorturl.at"]
    static let riskyTlds: Set<String> = ["top", "xyz", "click", "icu", "live", "online", "site", "shop", "buzz", "rest", "link", "info", "cfd", "sbs"]
    static let credWords = try! NSRegularExpression(pattern:
        "(login|signin|verify|verif|secure|account|update|confirm|bonus|prize|vyigrysh|kompensac|vozvrat|parol|password)", options: [.caseInsensitive])
    static let ipRx = try! NSRegularExpression(pattern: #"^\d{1,3}(\.\d{1,3}){3}$"#)

    public static func extract(_ text: String) -> [String] {
        let ns = text as NSString
        var out: [String] = []
        for m in urlRx.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            var u = ns.substring(with: m.range)
            while let last = u.last, ".,)!?;".contains(last) { u.removeLast() }
            if !out.contains(u) { out.append(u) }
            if out.count >= 10 { break }
        }
        return out
    }

    public static func host(_ url: String) -> String {
        var s = url.replacingOccurrences(of: #"(?i)^https?://"#, with: "", options: .regularExpression)
        for sep in ["/", "?", "#"] { s = String(s.split(separator: Character(sep), maxSplits: 1, omittingEmptySubsequences: false).first ?? "") }
        s = String(s.split(separator: "@", omittingEmptySubsequences: false).last ?? "")
        s = String(s.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        return s.lowercased()
    }

    public static func registrable(_ host: String) -> String { host.split(separator: ".").suffix(2).joined(separator: ".") }

    static func has(_ rx: NSRegularExpression, _ s: String) -> Bool {
        rx.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }

    public static func check(_ url: String) -> Finding {
        let h = host(url), reg = registrable(h)
        var reasons: [String] = []
        var score = 0
        if shorteners.contains(h) || shorteners.contains(reg) { score += 35; reasons.append("Короткая ссылка скрывает настоящий адрес") }
        if has(ipRx, h) { score += 50; reasons.append("Вместо адреса сайта — IP-адрес") }
        if h.split(separator: ".").contains(where: { $0.hasPrefix("xn--") }) {
            score += 45; reasons.append("Адрес с похожими буквами (punycode) — так подделывают известные сайты")
        }
        let tld = String(h.split(separator: ".").last ?? "")
        if riskyTlds.contains(tld) { score += 25; reasons.append("Домен .\(tld) часто используют для одноразовых мошеннических сайтов") }
        for (brand, official) in brands where h.contains(brand) && !official.contains(reg) && !official.contains(where: { h == $0 || h.hasSuffix("." + $0) }) {
            score += 55; reasons.append("Похоже на «\(brand.prefix(1).uppercased() + brand.dropFirst())», но это не официальный сайт (\(official[0]))")
            break
        }
        if h.filter({ $0 == "-" }).count >= 2 { score += 10; reasons.append("Много дефисов в адресе") }
        if has(credWords, url) && score > 0 { score += 15; reasons.append("Страница просит войти или «подтвердить» данные") }
        if url.contains("@") { score += 20; reasons.append("Символ @ в ссылке маскирует настоящий адрес") }
        return Finding(url: url, host: h, score: min(score, 100), reasons: reasons)
    }

    public static func scan(_ text: String) -> [Finding] { extract(text).map(check).filter { $0.score > 0 } }
}
