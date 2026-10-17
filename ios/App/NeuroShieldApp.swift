import SwiftUI
import UIKit

@main
struct NeuroShieldApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model).preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            if !model.onboarded {
                OnboardingView { model.setOnboarded(true); model.requestNotifications() }
            } else {
                TabView(selection: $model.tab) {
                    NavigationStack(path: $model.homePath) { HomeView() }.tag("home")
                        .tabItem { Label("Обзор", systemImage: "square.grid.2x2") }
                    NavigationStack { ProtectionView() }.tag("protection")
                        .tabItem { Label("Защита", systemImage: "shield.lefthalf.filled") }
                    NavigationStack { HistoryView() }.tag("history")
                        .tabItem { Label("Угрозы", systemImage: "dot.radiowaves.left.and.right") }
                    NavigationStack { SettingsView() }.tag("settings")
                        .tabItem { Label("Настройки", systemImage: "slider.horizontal.3") }
                }
                .tint(NS.cyan)
            }
            if let w = model.warning {
                Color.black.opacity(0.45).ignoresSafeArea().onTapGesture { model.hideWarning() }.transition(.opacity)
                WarningCard(w: w, onDetails: {
                    model.hideWarning(); model.tab = "history"
                    model.openAlert = model.alerts.first(where: { $0.title == w.title })
                }, onClose: { model.hideWarning() })
                .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.94)),
                                        removal: .opacity.combined(with: .offset(y: -24))))
                .zIndex(2)
            }
        }
        .background(NS.bg.ignoresSafeArea())
        .onAppear {
            let a = UITabBarAppearance(); a.configureWithOpaqueBackground()
            a.backgroundColor = UIColor(NS.surface); UITabBar.appearance().standardAppearance = a; UITabBar.appearance().scrollEdgeAppearance = a
            model.checkNotifications(); model.checkCallDirectory()
        }
    }
}

struct OnboardingView: View {
    let onDone: () -> Void
    @State private var page = 0
    private let slides: [(String, Color, String, String)] = [
        ("checkmark.shield.fill", NS.cyan, "Защита от мошенников",
         "NeuroShield проверяет сообщения и ссылки, подписывает и блокирует номера, которые вы отметили как мошеннические."),
        ("message.badge.filled.fill", NS.threat, "Фильтр SMS от незнакомцев",
         "Сообщения с признаками обмана — «продиктуйте код», поддельный сайт банка — уходят в папку «Нежелательные»."),
        ("play.circle.fill", NS.violet, "Демо-показ",
         "Сценарий звонка мошенника по шагам: как приложение оценивает номер, слова звонящего, SMS и ссылки."),
        ("lock.fill", NS.safe, "Всё на вашем iPhone",
         "Анализ работает без интернета. Сообщения, звук и кадры никуда не отправляются."),
    ]
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("NeuroShield").font(.title2.bold()).foregroundColor(NS.text) + Text(" AI").font(.title2.bold()).foregroundColor(NS.cyan)
                Spacer()
                Button("Пропустить", action: onDone).foregroundStyle(NS.muted)
            }.padding(.horizontal, 24).padding(.top, 12)
            TabView(selection: $page) {
                ForEach(slides.indices, id: \.self) { i in
                    let s = slides[i]
                    VStack(spacing: 24) {
                        StatusOrb(color: s.1, size: 180, system: s.0)
                        Text(s.2).font(.system(size: 26, weight: .bold)).foregroundStyle(NS.text).multilineTextAlignment(.center)
                        Text(s.3).font(.system(size: 17)).foregroundStyle(NS.text2).multilineTextAlignment(.center)
                    }.padding(.horizontal, 28).tag(i)
                }
            }.tabViewStyle(.page(indexDisplayMode: .always))
            PrimaryButton(title: page == slides.count - 1 ? "Включить защиту" : "Далее") {
                if page == slides.count - 1 { onDone() } else { withAnimation { page += 1 } }
            }.padding(24)
        }.background(NS.bg.ignoresSafeArea())
    }
}
