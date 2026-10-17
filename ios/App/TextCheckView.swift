import SwiftUI

struct TextCheckView: View {
    @EnvironmentObject var model: AppModel
    @State private var text = ""
    @State private var result: TextRisk? = nil
    @FocusState private var focused: Bool

    var body: some View {
        Group {
            PageHeader(title: "Проверка текста", sub: "SMS, сообщение, ссылка или слова звонящего")
            ZStack(alignment: .topLeading) {
                if text.isEmpty {
                    Text("«Это служба безопасности банка, продиктуйте код из СМС…»").foregroundStyle(NS.muted).padding(14)
                }
                TextEditor(text: $text).focused($focused).scrollContentBackground(.hidden).foregroundStyle(NS.text)
                    .padding(8).frame(height: 170)
                    .onChange(of: text) { v in if v.count > ScamAnalyzer.maxText { text = String(v.prefix(ScamAnalyzer.maxText)) } }
            }
            .background(RoundedRectangle(cornerRadius: 18).fill(NS.surface))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(focused ? NS.cyan : NS.border))
            HStack(spacing: 10) {
                PrimaryButton(title: "Проверить", enabled: !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                    focused = false
                    let r = ScamAnalyzer.analyze(text); withAnimation { result = r }
                    if r.level != .low {
                        model.store.add(Alert(source: "manual_text", number: nil, level: r.level, score: r.score,
                                              title: "Проверка текста: " + r.summary.lowercased(), reasons: r.tactics.map(\.title),
                                              excerpt: model.store.storeExcerpts ? String(text.prefix(600)) : nil))
                        model.refresh()
                    }
                }
                GhostButton(title: "Пример") {
                    text = "Здравствуйте, вас беспокоит служба безопасности банка. С вашей карты пытаются списать деньги. Срочно продиктуйте код из СМС и никому не говорите об этом звонке."
                }.frame(width: 120)
            }
            if let r = result {
                GlassCard(accent: NS.level(r.level)) {
                    HStack(spacing: 14) {
                        RingGauge(progress: r.score / 100, color: NS.level(r.level), label: "\(Int(r.score))")
                        VStack(alignment: .leading, spacing: 4) {
                            Text(r.level.ru.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1).foregroundStyle(NS.level(r.level))
                            Text(r.summary).font(.system(size: 17, weight: .semibold)).foregroundStyle(NS.text)
                            if r.words < 8 { Text("Текст короткий — оценка неточная").font(.system(size: 12)).foregroundStyle(NS.muted) }
                        }
                    }
                }
                RiskDetails(r: r)
            }
        }
        .page()
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct RiskDetails: View {
    let r: TextRisk
    var body: some View {
        ForEach(r.tactics) { t in
            GlassCard {
                Text(t.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(NS.text)
                ForEach(t.evidence, id: \.self) { e in
                    Text("«\(e.context)»").font(.system(size: 13)).foregroundStyle(NS.text2)
                        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 12).fill(NS.surface3))
                }
                HStack(alignment: .top, spacing: 8) {
                    Text("→").foregroundStyle(NS.cyan).bold()
                    Text(t.advice).font(.system(size: 14)).foregroundStyle(NS.text2)
                }
            }
        }
        if !r.tactics.isEmpty {
            Text("Оценивается содержание, а не человек: приложение не называет собеседника мошенником, а показывает приёмы обмана.")
                .font(.system(size: 12)).foregroundStyle(NS.muted)
        }
    }
}
