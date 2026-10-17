import SwiftUI

struct HomeView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let alerts = model.alerts
        let today = alerts.filter { Calendar.current.isDateInToday($0.ts) }.count
        let high = alerts.filter { $0.level == .high }.count
        let modulesOn = (model.callDirectoryEnabled == true ? 1 : 0) + (model.notificationsAllowed ? 1 : 0)
        Group {
            HStack {
                Text("NeuroShield").font(.system(size: 26, weight: .bold)).foregroundColor(NS.text)
                    + Text(" AI").font(.system(size: 26, weight: .bold)).foregroundColor(NS.cyan)
                Spacer()
                Button { model.tab = "settings" } label: { Image(systemName: "gearshape").foregroundStyle(NS.text2) }
            }.padding(.top, 8)

            GlassCard(accent: modulesOn == 2 ? NS.safe : NS.warn, padding: 20) {
                HStack(spacing: 14) {
                    StatusOrb(color: modulesOn == 2 ? NS.safe : NS.warn, size: 92, system: modulesOn == 2 ? "checkmark.shield.fill" : "exclamationmark.shield.fill")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("СТАТУС ЗАЩИТЫ").font(.system(size: 11, weight: .semibold)).tracking(1).foregroundStyle(NS.muted)
                        Text(modulesOn == 2 ? "Под защитой" : "Частично").font(.system(size: 24, weight: .bold)).foregroundStyle(NS.text)
                        Text("Анализ текста и ссылок работает всегда. Фильтр SMS и метки номеров включаются в Настройках iPhone.")
                            .font(.system(size: 12.5)).foregroundStyle(NS.text2)
                    }
                }
                if modulesOn < 2 { PrimaryButton(title: "Включить модули", color: NS.warn) { model.tab = "protection" } }
            }

            HStack(spacing: 10) {
                stat("\(today)", "за сутки", NS.cyan)
                stat("\(high)", "высокий риск", NS.threat)
                stat("\(alerts.count)", "всего", NS.text2)
            }

            NavigationLink(value: "demo") {
                GlassCard(accent: NS.violet, padding: 16) {
                    HStack(spacing: 12) {
                        IconBadge(system: "play.circle.fill", tint: NS.violet, size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Демо-показ").font(.system(size: 17, weight: .semibold)).foregroundStyle(NS.text)
                            Text("Сценарий звонка мошенника по шагам — для презентации").font(.system(size: 13)).foregroundStyle(NS.text2)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(NS.violet)
                    }
                }
            }.buttonStyle(.plain)

            SectionTitle(text: "Быстрые проверки")
            HStack(spacing: 10) {
                NavigationLink(value: "text") { tile("text.magnifyingglass", "Текст", "SMS и ссылки", NS.cyan) }.buttonStyle(.plain)
                NavigationLink(value: "face") { tile("faceid", "Лицо", "Проверка живости", NS.safe) }.buttonStyle(.plain)
            }
            Button {
                model.show(Warning(level: .high, title: "Просят продиктовать код из SMS",
                                   reasons: ["Звонящий представился службой безопасности банка", "Просит назвать код из SMS"],
                                   advice: ["Положите трубку и позвоните в банк сами"], source: "test", number: "+7 700 000 00 00", score: 92, isTest: true))
            } label: { tile("exclamationmark.triangle.fill", "Тест", "Пример окна угрозы", NS.threat) }.buttonStyle(.plain)

            SectionTitle(text: "Последние угрозы")
            if alerts.isEmpty {
                GlassCard {
                    Text("Угроз не обнаружено").font(.headline).foregroundStyle(NS.text)
                    Text("NeuroShield предупредит, если сообщение или ссылка покажутся подозрительными").font(.subheadline).foregroundStyle(NS.text2)
                }
            } else {
                ForEach(alerts.prefix(3)) { a in
                    Button { model.openAlert = a; model.tab = "history" } label: { ThreatRow(a: a) }.buttonStyle(.plain)
                }
            }
        }
        .page()
        .navigationBarHidden(true)
        .navigationDestination(for: String.self) { r in
            switch r {
            case "demo": DemoView()
            case "text": TextCheckView()
            case "face": FaceCheckView()
            default: EmptyView()
            }
        }
        .onAppear { model.refresh() }
    }

    private func stat(_ v: String, _ label: String, _ c: Color) -> some View {
        Button { model.tab = "history" } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(v).font(.system(size: 24, weight: .bold)).foregroundStyle(c)
                Text(label).font(.system(size: 12)).foregroundStyle(NS.muted).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 18).fill(NS.surface2))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(NS.border))
        }.buttonStyle(.plain)
    }

    private func tile(_ icon: String, _ title: String, _ sub: String, _ c: Color) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            IconBadge(system: icon, tint: c)
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(NS.text)
            Text(sub).font(.system(size: 12.5)).foregroundStyle(NS.text2).lineLimit(1)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(NS.surface2))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(NS.border))
    }
}

struct ThreatRow: View {
    let a: Alert
    var body: some View {
        HStack(spacing: 12) {
            IconBadge(system: icon(a.source), tint: NS.level(a.level))
            VStack(alignment: .leading, spacing: 2) {
                Text(a.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(NS.text).lineLimit(2)
                Text(a.sourceRu + " · " + a.ts.formatted(date: .omitted, time: .shortened)).font(.system(size: 12)).foregroundStyle(NS.muted)
            }
            Spacer(minLength: 4)
            Pill(text: a.level.short, color: NS.level(a.level))
        }.padding(14)
            .background(RoundedRectangle(cornerRadius: 18).fill(NS.surface2))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(NS.border))
    }
    func icon(_ s: String) -> String {
        switch s {
        case "demo": return "play.circle.fill"
        case "face": return "faceid"
        case "manual_text": return "text.magnifyingglass"
        default: return "shield.fill"
        }
    }
}
