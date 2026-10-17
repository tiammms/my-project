import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var model: AppModel
    @State private var filter: RiskLevel? = nil
    @State private var confirmClear = false

    var body: some View {
        let list = model.alerts.filter { filter == nil || $0.level == filter }
        let days = Dictionary(grouping: list) { Calendar.current.startOfDay(for: $0.ts) }.sorted { $0.key > $1.key }
        Group {
            HStack {
                PageHeader(title: "Угрозы", sub: "\(model.alerts.count) записей · хранятся только на этом iPhone")
                if !model.alerts.isEmpty {
                    Button { confirmClear = true } label: { Image(systemName: "trash").foregroundStyle(NS.muted) }
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip("Все · \(model.alerts.count)", nil)
                    ForEach([RiskLevel.high, .medium, .low], id: \.self) { l in chip("\(l.short) · \(model.alerts.filter { $0.level == l }.count)", l) }
                }
            }
            if list.isEmpty {
                GlassCard { Text("Записей нет").font(.headline).foregroundStyle(NS.text)
                    Text("Здесь появятся предупреждения: проверки текста, демо-сценарий, проверки лица.").font(.subheadline).foregroundStyle(NS.text2) }
            }
            ForEach(days, id: \.key) { day, items in
                SectionTitle(text: Calendar.current.isDateInToday(day) ? "Сегодня" : day.formatted(.dateTime.day().month(.wide)))
                ForEach(items) { a in
                    Button { model.openAlert = a } label: { ThreatRow(a: a) }.buttonStyle(.plain)
                }
            }
        }
        .page()
        .navigationBarHidden(true)
        .onAppear { model.refresh() }
        .sheet(item: $model.openAlert) { a in ThreatDetail(a: a).environmentObject(model).presentationDetents([.medium, .large]) }
        .confirmationDialog("Удалить всю историю угроз?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Удалить всё", role: .destructive) { model.store.clear(); model.refresh() }
        }
    }

    private func chip(_ text: String, _ l: RiskLevel?) -> some View {
        let on = filter == l
        let c = l.map(NS.level) ?? NS.cyan
        return Button { withAnimation { filter = l } } label: {
            Text(text).font(.system(size: 13, weight: .semibold)).foregroundStyle(on ? c : NS.text2)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(on ? c.opacity(0.14) : NS.surface2))
                .overlay(Capsule().stroke(on ? c.opacity(0.5) : NS.border))
        }.buttonStyle(.plain)
    }
}

struct ThreatDetail: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let a: Alert

    var body: some View {
        let scam = a.number.flatMap(CallRisk.normalize).map { model.store.scamNumbers().contains($0) } ?? false
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Pill(text: a.level.ru, color: NS.level(a.level))
                    Spacer()
                    Text(a.ts.formatted(date: .abbreviated, time: .shortened)).font(.system(size: 12)).foregroundStyle(NS.muted)
                }
                Text(a.title).font(.system(size: 20, weight: .bold)).foregroundStyle(NS.text)
                Text(a.sourceRu + (a.number.map { " · " + $0 } ?? "")).font(.system(size: 13)).foregroundStyle(NS.text2)
                DangerMeter(level: a.level, score: a.score)
                SectionTitle(text: "Почему")
                ForEach(a.reasons, id: \.self) { Bullet(text: $0) }
                if let e = a.excerpt {
                    SectionTitle(text: "Фрагмент")
                    Text("«\(e)»").font(.system(size: 13)).foregroundStyle(NS.text2).padding(10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(NS.surface3))
                }
                if let n = a.number, n.contains(where: \.isNumber), a.source != "demo" {
                    HStack(spacing: 10) {
                        if scam { GhostButton(title: "Снять отметку") { model.mark(n, scam: false) } }
                        else { PrimaryButton(title: "Это мошенник", color: NS.threat) { model.mark(n, scam: true) } }
                    }
                    Text("Отмеченные номера iPhone подпишет при звонке, а при включённой блокировке — сбросит (нужно включить NeuroShield в Настройки → Телефон → Блокировка и идентификация вызовов).")
                        .font(.system(size: 12)).foregroundStyle(NS.muted)
                }
                HStack(spacing: 10) {
                    ShareLink(item: report) {
                        Label("Поделиться", systemImage: "square.and.arrow.up").font(.system(size: 15, weight: .semibold))
                            .frame(maxWidth: .infinity).padding(.vertical, 13).foregroundStyle(NS.text)
                            .background(RoundedRectangle(cornerRadius: 16).stroke(NS.border))
                    }
                    GhostButton(title: "Удалить", color: NS.threat) { model.store.delete(a.id); model.refresh(); dismiss() }
                }
            }.padding(20)
        }.background(NS.bg.ignoresSafeArea())
    }

    private var report: String {
        "NeuroShield: \(a.level.ru)\n\(a.title)\n\(a.sourceRu)\(a.number.map { ", " + $0 } ?? "")\n" +
        a.reasons.map { "• " + $0 }.joined(separator: "\n") +
        "\n\nОценка показывает признаки обмана, а не называет человека мошенником."
    }
}
