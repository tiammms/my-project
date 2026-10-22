import Foundation

/// Scripted presentation scenario (same as Android core/DemoScenario.kt and the server demo_scenario.py).
/// The INPUT is made up; every RESULT comes from the real analyzers, nothing is hard-coded.
public enum DemoScenario {
    public static let number = "+447700900123"     // range reserved for drama / fiction

    public struct Line: Hashable { public let caller: Bool; public let text: String }

    public static let call: [Line] = [
        Line(caller: true, text: "Здравствуйте! Это служба безопасности Kaspi банка."),
        Line(caller: false, text: "Здравствуйте, слушаю."),
        Line(caller: true, text: "По вашей карте подозрительная операция: на ваше имя пытаются оформить кредит."),
        Line(caller: true, text: "Действовать нужно срочно, у вас нет времени, иначе деньги спишут."),
        Line(caller: false, text: "Что мне делать?"),
        Line(caller: true, text: "Вам придёт СМС. Продиктуйте мне код из СМС."),
        Line(caller: true, text: "И никому не говорите об этом звонке, это тайна следствия."),
        Line(caller: true, text: "Потом переведите все деньги на безопасный счёт, я продиктую номер."),
    ]

    public static let sms = "Kaspi.kz: код подтверждения 4821. Никому не сообщайте этот код."
    public static let phishing = "Kaspi: ваша карта заблокирована. Чтобы разблокировать, подтвердите данные: https://kaspi-bonus.example/verify"
    public static let normal = "Привет! Это Айгерим. Во сколько завтра встречаемся у университета? Я возьму конспекты."

    public static func callerText(_ upTo: Int) -> String {
        call.prefix(upTo).filter(\.caller).map(\.text).joined(separator: " ")
    }

    public static func callFacts(home: String = "+7") -> CallFacts { CallFacts(number: number, homeCallingCode: home) }
}
