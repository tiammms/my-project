import Foundation

/// One warning in the local history. No audio, no message bodies unless the user enabled excerpts.
public struct Alert: Codable, Identifiable, Hashable {
    public let id: UUID
    public let ts: Date
    public let source: String
    public let number: String?
    public let level: RiskLevel
    public let score: Double
    public let title: String
    public let reasons: [String]
    public let excerpt: String?

    public init(source: String, number: String?, level: RiskLevel, score: Double, title: String, reasons: [String], excerpt: String? = nil) {
        id = UUID(); ts = Date(); self.source = source; self.number = number; self.level = level
        self.score = score; self.title = title; self.reasons = reasons; self.excerpt = excerpt
    }

    public var sourceRu: String {
        switch source {
        case "sms_filter": return "SMS от незнакомого отправителя"
        case "manual_text": return "Проверка текста"
        case "face": return "Проверка лица"
        case "demo": return "Демо-сценарий (учебные данные)"
        default: return source
        }
    }
}

/// Storage shared by the app and its extensions through the App Group (falls back to the app's own defaults
/// when the group is not available, e.g. an unsigned simulator build).
public final class SharedStore {
    public static let groupId = "group.ai.neuroshield.app"
    public static let shared = SharedStore()

    public let defaults: UserDefaults
    public let isShared: Bool

    init() {
        if let d = UserDefaults(suiteName: SharedStore.groupId),
           FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedStore.groupId) != nil {
            defaults = d; isShared = true
        } else {
            defaults = .standard; isShared = false
        }
    }

    // ---- history
    public func alerts() -> [Alert] {
        guard let data = defaults.data(forKey: "alerts"), let list = try? JSONDecoder().decode([Alert].self, from: data) else { return [] }
        return list
    }
    private func save(_ list: [Alert]) {
        if let data = try? JSONEncoder().encode(Array(list.prefix(300))) { defaults.set(data, forKey: "alerts") }
    }
    @discardableResult public func add(_ a: Alert) -> Alert { save([a] + alerts()); return a }
    public func delete(_ id: UUID) { save(alerts().filter { $0.id != id }) }
    public func deleteSource(_ source: String) { save(alerts().filter { $0.source != source }) }
    public func clear() { save([]) }

    // ---- numbers the user marked (used by the Call Directory extension to block / label)
    public func scamNumbers() -> [String] { defaults.stringArray(forKey: "scam_numbers") ?? [] }
    public func setScam(_ number: String, _ on: Bool) {
        guard let n = CallRisk.normalize(number) else { return }
        var s = Set(scamNumbers()); if on { s.insert(n) } else { s.remove(n) }
        defaults.set(s.sorted(), forKey: "scam_numbers")
    }
    public var blockMarked: Bool {
        get { defaults.object(forKey: "block_marked") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "block_marked") }
    }

    // ---- settings
    public var homeCallingCode: String {
        get { defaults.string(forKey: "home_code") ?? "+7" }
        set { defaults.set(newValue, forKey: "home_code") }
    }
    public var storeExcerpts: Bool {
        get { defaults.bool(forKey: "store_excerpts") }
        set { defaults.set(newValue, forKey: "store_excerpts") }
    }
    public var trustedName: String { get { defaults.string(forKey: "trusted_name") ?? "" } set { defaults.set(newValue, forKey: "trusted_name") } }
    public var trustedNumber: String { get { defaults.string(forKey: "trusted_number") ?? "" } set { defaults.set(newValue, forKey: "trusted_number") } }
    public var bankNumber: String { get { defaults.string(forKey: "bank_number") ?? "" } set { defaults.set(newValue, forKey: "bank_number") } }
    public var onboarded: Bool { get { defaults.bool(forKey: "onboarded") } set { defaults.set(newValue, forKey: "onboarded") } }
}

/// E.164 digits for CallKit: "+7 701 555 01 23" -> 77015550123
public func callKitNumber(_ s: String) -> Int64? {
    let d = s.filter(\.isNumber)
    return d.isEmpty ? nil : Int64(d)
}
