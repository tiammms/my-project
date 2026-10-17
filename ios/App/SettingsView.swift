import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var homeCode = SharedStore.shared.homeCallingCode
    @State private var trustedName = SharedStore.shared.trustedName
    @State private var trustedNumber = SharedStore.shared.trustedNumber
    @State private var bankNumber = SharedStore.shared.bankNumber
    @State private var blockMarked = SharedStore.shared.blockMarked
    @State private var excerpts = SharedStore.shared.storeExcerpts
    @State private var newScam = ""
    @State private var numbers = SharedStore.shared.scamNumbers()
    @State private var about = false

    var body: some View {
        Group {
            PageHeader(title: "Настройки")
            SectionTitle(text: "Номера")
            GlassCard {
                field("Код страны", $homeCode, "+7", .phonePad) { model.store.homeCallingCode = $0 }
                toggleRow("Блокировать отмеченных мошенников", "iPhone сбросит звонок с отмеченного номера", $blockMarked) {
                    model.store.blockMarked = $0; model.reloadCallDirectory()
                }
                Text("Отмеченные номера (\(numbers.count))").font(.system(size: 13, weight: .semibold)).foregroundStyle(NS.text2)
                ForEach(numbers, id: \.self) { n in
                    HStack {
                        Text(n).foregroundStyle(NS.text)
                        Spacer()
                        Button { model.mark(n, scam: false); numbers = model.store.scamNumbers() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(NS.muted) }
                    }
                }
                HStack {
                    TextField("", text: $newScam, prompt: Text("+7 700 123 45 67").foregroundColor(NS.muted))
                        .keyboardType(.phonePad).foregroundStyle(NS.text).padding(10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(NS.surface3))
                    Button("Добавить") {
                        model.mark(newScam, scam: true); newScam = ""; numbers = model.store.scamNumbers()
                    }.disabled(CallRisk.normalize(newScam) == nil).foregroundStyle(NS.cyan)
                }
            }
            SectionTitle(text: "Экстренная помощь")
            GlassCard {
                Text("Кнопки «Позвонить близкому» и «Позвонить в банк» в предупреждении о высоком риске. Номер открывается в телефоне — звонок подтверждаете вы.")
                    .font(.system(size: 12.5)).foregroundStyle(NS.text2)
                field("Имя близкого", $trustedName, "Мама") { model.store.trustedName = $0 }
                field("Номер близкого", $trustedNumber, "+7 701 000 00 00", .phonePad) { model.store.trustedNumber = $0 }
                field("Номер банка", $bankNumber, "Номер с обратной стороны карты", .phonePad) { model.store.bankNumber = $0 }
            }
            SectionTitle(text: "Конфиденциальность")
            GlassCard {
                toggleRow("Сохранять фрагменты текста", "В историю попадёт до 600 символов проверенного текста", $excerpts) { model.store.storeExcerpts = $0 }
                Text("Анализ выполняется на iPhone. Сообщения, звук и кадры не отправляются в интернет. История хранится только на этом устройстве.")
                    .font(.system(size: 12.5)).foregroundStyle(NS.muted)
            }
            SectionTitle(text: "О приложении")
            GlassCard {
                Button { withAnimation { about.toggle() } } label: {
                    HStack { Text("Saqtan для iPhone").foregroundStyle(NS.text); Spacer()
                        Image(systemName: about ? "chevron.up" : "chevron.down").foregroundStyle(NS.muted) }
                }.buttonStyle(.plain)
                if about {
                    Bullet(text: "Проверка текста и ссылок: правила на русском, казахском и английском — те же, что в Android-версии и на сайте.")
                    Bullet(text: "Оценка показывает приёмы обмана, а не называет человека мошенником.")
                    Bullet(text: "Не распознаёт поддельный (сгенерированный) голос и дипфейки.")
                    Bullet(text: "Исследовательский прототип: не сертифицирован, не подключён к банкам.")
                }
                Button("Показать знакомство снова") { model.setOnboarded(false) }.foregroundStyle(NS.cyan)
            }
        }
        .page()
        .navigationBarHidden(true)
        .onAppear { numbers = model.store.scamNumbers() }
    }

    private func field(_ label: String, _ value: Binding<String>, _ hint: String, _ kb: UIKeyboardType = .default, save: @escaping (String) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.system(size: 12)).foregroundStyle(NS.muted)
            TextField("", text: value, prompt: Text(hint).foregroundColor(NS.muted)).keyboardType(kb).foregroundStyle(NS.text)
                .padding(10).background(RoundedRectangle(cornerRadius: 12).fill(NS.surface3))
                .onChange(of: value.wrappedValue) { save($0) }
        }
    }

    private func toggleRow(_ title: String, _ sub: String, _ value: Binding<Bool>, save: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: value) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(NS.text)
                Text(sub).font(.system(size: 12)).foregroundStyle(NS.text2)
            }
        }.tint(NS.cyan).onChange(of: value.wrappedValue) { save($0) }
    }
}
