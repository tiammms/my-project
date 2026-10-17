import CallKit
import SwiftUI
import UIKit
import UserNotifications

/// A warning to show on screen (the in-app equivalent of the Android overlay; iOS does not allow drawing over other apps).
struct Warning: Identifiable, Equatable {
    let id = UUID()
    var level: RiskLevel
    var title: String
    var reasons: [String]
    var advice: [String] = []
    var source: String
    var number: String? = nil
    var score: Double = 0
    var excerpt: String? = nil
    var demo = false
    var isTest = false
}

@MainActor
final class AppModel: ObservableObject {
    static let callDirectoryId = "ai.neuroshield.app.CallDirectory"

    @Published var alerts: [Alert] = []
    @Published var warning: Warning? = nil
    @Published var tab: String = "home"
    @Published var homePath: [String] = []       // pushed screens on the home tab: text, demo, face
    @Published var openAlert: Alert? = nil
    @Published var callDirectoryEnabled: Bool? = nil
    @Published var notificationsAllowed = false
    @Published private(set) var onboarded = SharedStore.shared.onboarded

    let store = SharedStore.shared

    init() {
        refresh()
        // launch arguments for screenshots / demos: -route demo -tab history -skipOnboarding YES
        let d = UserDefaults.standard
        if d.bool(forKey: "skipOnboarding") { store.onboarded = true; onboarded = true }
        if let t = d.string(forKey: "tab") { tab = t }
        if let r = d.string(forKey: "route") { homePath = [r] }
    }

    func refresh() { alerts = store.alerts() }

    func setOnboarded(_ v: Bool) {
        store.onboarded = v
        withAnimation { onboarded = v; if !v { tab = "home" } }
    }

    /// Show a warning in the app and as a notification; real warnings and demo warnings are written to the history.
    func show(_ w: Warning) {
        if !w.isTest {
            store.add(Alert(source: w.source, number: w.number, level: w.level, score: w.score, title: w.title, reasons: w.reasons,
                            excerpt: store.storeExcerpts ? w.excerpt.map { String($0.prefix(600)) } : nil))
            refresh()
        }
        withAnimation(.spring(response: 0.42, dampingFraction: 0.72)) { warning = w }
        if w.level != .low { notify(w) }
        #if canImport(UIKit)
        if w.level != .low { UINotificationFeedbackGenerator().notificationOccurred(w.level == .high ? .error : .warning) }
        #endif
    }

    func hideWarning() { withAnimation(.easeOut(duration: 0.18)) { warning = nil } }

    func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in
            Task { @MainActor in self.notificationsAllowed = ok }
        }
    }

    func checkNotifications() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let ok = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            Task { @MainActor in self.notificationsAllowed = ok }
        }
    }

    private func notify(_ w: Warning) {
        let c = UNMutableNotificationContent()
        c.title = (w.level == .high ? "⚠️ Возможно, мошенник!" : "Осторожно: есть признаки обмана") + (w.demo ? " · ДЕМО" : "")
        c.body = w.title + (w.advice.first.map { "\n" + $0 } ?? "")
        c.sound = .default
        if #available(iOS 15.0, *) { c.interruptionLevel = w.level == .high ? .timeSensitive : .active }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: w.id.uuidString, content: c, trigger: nil))
    }

    // ---- marked numbers -> Call Directory extension (label / block on the incoming-call screen)
    func mark(_ number: String, scam: Bool) {
        store.setScam(number, scam)
        reloadCallDirectory()
    }

    func reloadCallDirectory() {
        CXCallDirectoryManager.sharedInstance.reloadExtension(withIdentifier: Self.callDirectoryId) { _ in
            Task { @MainActor in self.checkCallDirectory() }
        }
    }

    func checkCallDirectory() {
        CXCallDirectoryManager.sharedInstance.getEnabledStatusForExtension(withIdentifier: Self.callDirectoryId) { status, error in
            Task { @MainActor in self.callDirectoryEnabled = error == nil ? (status == .enabled) : nil }
        }
    }
}
