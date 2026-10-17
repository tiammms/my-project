import SwiftUI
import UIKit

struct ProtectionView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let callDir = model.callDirectoryEnabled == true
        let on = (callDir ? 1 : 0) + (model.notificationsAllowed ? 1 : 0) + 1
        Group {
            PageHeader(title: "Защита", sub: "Что работает на iPhone и как это включить")
            GlassCard(accent: on == 3 ? NS.safe : NS.warn, padding: 20) {
                HStack(spacing: 16) {
                    RingGauge(progress: Double(on) / 3, color: on == 3 ? NS.safe : NS.warn, size: 84, label: "\(on)/3")
                    VStack(alignment: .leading, spacing: 4) {
                        Text(on == 3 ? "Всё включено" : "Включите модули").font(.system(size: 20, weight: .bold)).foregroundStyle(NS.text)
                        Text("Фильтр SMS iOS не сообщает приложению — проверьте его в Настройках сами.").font(.system(size: 12.5)).foregroundStyle(NS.text2)
                    }
                }
            }
            module("text.magnifyingglass", "Проверка текста и ссылок", "Всегда работает, без интернета", ok: true, how: nil)
            module("message.badge.filled.fill", "Фильтр SMS от незнакомцев",
                   "Сообщения с высоким риском уходят в «Нежелательные»", ok: nil,
                   how: "Настройки → Приложения → Сообщения → Неизвестные и спам → NeuroShield")
            module("phone.badge.checkmark", "Метки и блокировка номеров",
                   "Номера, отмеченные вами как мошеннические", ok: model.callDirectoryEnabled,
                   how: "Настройки → Приложения → Телефон → Блокировка и идентификация вызовов → NeuroShield")
            module("bell.badge.fill", "Уведомления", "Предупреждения на экране блокировки", ok: model.notificationsAllowed,
                   how: model.notificationsAllowed ? nil : "Нажмите «Открыть Настройки» и разрешите уведомления")
            PrimaryButton(title: "Открыть Настройки iPhone") {
                if !model.notificationsAllowed { model.requestNotifications() }
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            SectionTitle(text: "Чего iPhone не позволяет")
            GlassCard {
                Bullet(text: "Слушать телефонные звонки и звонки в WhatsApp — звук закрыт для всех приложений.")
                Bullet(text: "Показывать окно поверх экрана звонка.")
                Bullet(text: "Читать SMS от ваших контактов и коды во время звонка.")
                Bullet(text: "Оценивать номер незнакомца до ответа — iOS показывает только метки из списка.")
                Text("Поэтому на iPhone защита строится на фильтре сообщений, проверке текста и ссылок и метках номеров. Полный набор (анализ звонка, окно поверх звонка, код из SMS во время звонка) есть в версии для Android.")
                    .font(.system(size: 12.5)).foregroundStyle(NS.muted)
            }
        }
        .page()
        .navigationBarHidden(true)
        .onAppear { model.checkCallDirectory(); model.checkNotifications() }
        .onChange(of: phase) { p in if p == .active { model.checkCallDirectory(); model.checkNotifications() } }
    }

    private func module(_ icon: String, _ title: String, _ why: String, ok: Bool?, how: String?) -> some View {
        let c: Color = ok == true ? NS.safe : ok == false ? NS.warn : NS.cyan
        return GlassCard(padding: 14) {
            HStack(spacing: 12) {
                IconBadge(system: icon, tint: c)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(NS.text)
                    Text(why).font(.system(size: 12.5)).foregroundStyle(NS.text2)
                }
                Spacer(minLength: 4)
                Pill(text: ok == true ? "Вкл" : ok == false ? "Выкл" : "Проверьте", color: c)
            }
            if let how, ok != true { Text(how).font(.system(size: 12)).foregroundStyle(NS.muted) }
        }
    }
}
