import SwiftUI
import UIKit

/// Compact threat card, readable in 1–2 seconds: what (level + headline), why (one reason), what to do (one action).
struct WarningCard: View {
    let w: Warning
    let onDetails: () -> Void
    let onClose: () -> Void
    @State private var pulse = false
    @State private var fill = false
    private let store = SharedStore.shared

    var body: some View {
        let c = NS.level(w.level)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().stroke(c, lineWidth: 2).scaleEffect(pulse ? 1.45 : 1).opacity(pulse ? 0 : 0.9)
                    Circle().fill(c.opacity(0.16)).overlay(Circle().stroke(c.opacity(0.5), lineWidth: 1))
                    Text(w.level == .low ? "i" : "!").font(.system(size: 24, weight: .black)).foregroundStyle(c)
                }.frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label + (w.isTest ? " · ТЕСТ" : w.demo ? " · ДЕМО" : "")).font(.system(size: 11, weight: .bold)).tracking(1.1).foregroundStyle(c)
                    Text(headline).font(.system(size: 22, weight: .bold)).foregroundStyle(NS.text).lineLimit(2)
                }
            }
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { i in
                    Capsule().fill(i <= levelIndex ? c : c.opacity(0.16)).frame(height: 6)
                        .scaleEffect(x: fill ? 1 : 0, anchor: .leading)
                        .animation(.easeOut(duration: 0.26).delay(0.25 + Double(i) * 0.11), value: fill)
                }
                Text(w.level.short + (w.score > 0 ? " · \(Int(w.score))" : "")).font(.system(size: 13, weight: .bold)).foregroundStyle(c).fixedSize()
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(w.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(NS.text).lineLimit(2)
                if let n = w.number, n.contains(where: \.isNumber) { Text(n).font(.system(size: 13)).foregroundStyle(NS.muted) }
            }
            if let action = w.advice.first ?? w.reasons.first {
                HStack(alignment: .top, spacing: 10) {
                    Text("→").font(.system(size: 16, weight: .bold)).foregroundStyle(NS.cyan)
                    Text(action).font(.system(size: 14)).foregroundStyle(NS.text2).lineLimit(3)
                }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 14).fill(NS.surface3))
            }
            if w.level == .high || w.isTest, !helpButtons.isEmpty {
                HStack(spacing: 10) {
                    ForEach(helpButtons, id: \.0) { label, number in
                        Button { call(number) } label: {
                            Text(label).font(.system(size: 13, weight: .semibold)).foregroundStyle(NS.text).lineLimit(1)
                                .frame(maxWidth: .infinity).frame(height: 42).background(RoundedRectangle(cornerRadius: 14).fill(NS.surface3))
                        }.buttonStyle(.plain)
                    }
                }
            }
            HStack(spacing: 10) {
                Button(action: onDetails) {
                    Text("Подробнее").font(.system(size: 15, weight: .bold)).foregroundStyle(NS.text)
                        .frame(maxWidth: .infinity).frame(height: 46).background(RoundedRectangle(cornerRadius: 14).stroke(Color(hex: 0x2F3644)))
                }.buttonStyle(.plain)
                Button(action: onClose) {
                    Text("Понятно").font(.system(size: 15, weight: .bold)).foregroundStyle(Color(hex: 0x07080A))
                        .frame(maxWidth: .infinity).frame(height: 46).background(RoundedRectangle(cornerRadius: 14).fill(c))
                }.buttonStyle(.plain)
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color(hex: 0x0F1115).opacity(0.97)))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(c.opacity(0.85), lineWidth: 1.5))
        .shadow(color: .black.opacity(0.6), radius: 30, y: 12)
        .padding(.horizontal, 14)
        .onAppear {
            fill = true
            if w.level != .low { withAnimation(.easeOut(duration: 1.3).repeatForever(autoreverses: false)) { pulse = true } }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(label). \(headline). \(w.title)")
    }

    private var levelIndex: Int { w.level == .high ? 2 : w.level == .medium ? 1 : 0 }
    private var label: String { w.level == .high ? "⚠ ВНИМАНИЕ" : w.level == .medium ? "ОСТОРОЖНО" : "NEUROSHIELD" }
    private var headline: String { w.level == .high ? "Возможно, мошенник!" : w.level == .medium ? "Есть признаки обмана" : "Незнакомый номер" }
    private var helpButtons: [(String, String)] {
        var out: [(String, String)] = []
        if !store.trustedNumber.isEmpty { out.append(("Позвонить: " + (store.trustedName.isEmpty ? "близкому" : store.trustedName), store.trustedNumber)) }
        if !store.bankNumber.isEmpty { out.append(("Позвонить в банк", store.bankNumber)) }
        return out
    }
    private func call(_ number: String) {
        // opens the dialer with the number; the user confirms the call themselves
        let digits = number.filter { $0.isNumber || $0 == "+" }
        if let url = URL(string: "tel://" + digits) { UIApplication.shared.open(url) }
        onClose()
    }
}
