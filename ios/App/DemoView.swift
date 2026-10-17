import SwiftUI

private struct StepResult { let level: RiskLevel; let score: Double; let headline: String; let details: [String] }

/// Presentation mode: a scripted scam call played step by step. Number, speech and messages are made up;
/// every verdict on screen is computed by the real analyzers.
struct DemoView: View {
    @EnvironmentObject var model: AppModel
    @State private var active = 0                  // 0 = not started, 1...5 = step, 6 = finished
    @State private var lines = 0
    @State private var results: [Int: StepResult] = [:]
    @State private var task: Task<Void, Never>? = nil
    @State private var running = false
    @State private var runId = 0

    var body: some View {
        Group {
            PageHeader(title: "Демо-показ", sub: "Сценарий звонка мошенника")
            GlassCard(accent: NS.violet) {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "info.circle.fill").foregroundStyle(NS.violet)
                    Text("Номер, речь и сообщения в этом сценарии выдуманы. Оценки считаются по-настоящему — теми же правилами, что в приложении для Android и на сайте. Записи в истории помечаются «Демо».")
                        .font(.system(size: 13)).foregroundStyle(NS.text2)
                }
            }
            if running {
                GhostButton(title: "■  Остановить", color: NS.threat) { stop() }
            } else {
                PrimaryButton(title: active == 0 ? "▶  Запустить весь сценарий" : "▶  Показать заново", color: NS.violet) { runAll() }
            }
            step(1, "phone.fill", "Звонок с незнакомого номера", "Оценка номера до ответа: \(DemoScenario.number)")
            step(2, "waveform", "Звонящий говорит", "Анализ речи по фразам: риск растёт с каждой репликой") {
                if lines > 0 { transcript }
            }
            step(3, "message.fill", "Приходит SMS с кодом", "«\(DemoScenario.sms)»")
            step(4, "link", "Сообщение с поддельной ссылкой", "«\(DemoScenario.phishing)»")
            step(5, "checkmark.seal.fill", "Для сравнения: обычное сообщение", "«\(DemoScenario.normal)»")
            if active == 6 {
                GlassCard(accent: NS.safe) {
                    Text("Сценарий завершён").font(.system(size: 17, weight: .semibold)).foregroundStyle(NS.text)
                    Bullet(text: "Звонок: предупреждение ещё до ответа")
                    Bullet(text: "Разговор: тревога, как только звонящий попросил код и перевод")
                    Bullet(text: "SMS с кодом во время звонка: высокий риск")
                    Bullet(text: "Поддельный сайт банка: высокий риск")
                    Bullet(text: "Обычное сообщение: без тревоги — ложной паники нет")
                }.transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            HStack(spacing: 10) {
                GhostButton(title: "Открыть историю") { model.tab = "history" }
                let n = model.alerts.filter { $0.source == "demo" }.count
                GhostButton(title: n > 0 ? "Удалить демо (\(n))" : "Демо-записей нет", color: NS.muted) {
                    if n > 0 { model.store.deleteSource("demo"); model.refresh() }
                }
            }
            Text("На iPhone приложение не может слышать звонки и читать SMS во время звонка — это закрыто Apple для всех приложений. Шаги 1–3 показывают, как работает анализ; на iPhone в реальной жизни действуют фильтр SMS от незнакомцев, проверка текста и ссылок и метки отмеченных номеров.")
                .font(.system(size: 12)).foregroundStyle(NS.muted)
        }
        .page()
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { stop() }
        .onAppear { if UserDefaults.standard.bool(forKey: "autoplay") && active == 0 { runAll() } }
    }

    // ---- steps (real analysis of the scripted input)
    private func warn(_ level: RiskLevel, _ score: Double, _ title: String, _ reasons: [String], _ advice: [String], _ number: String?, _ excerpt: String?) {
        model.show(Warning(level: level, title: "Демо · " + title, reasons: reasons, advice: advice, source: "demo",
                           number: number, score: score, excerpt: excerpt, demo: true))
    }

    private func stepCall() {
        active = 1
        let r = CallRisk.assess(DemoScenario.callFacts(home: model.store.homeCallingCode))
        results[1] = StepResult(level: r.level, score: r.score, headline: "Входящий звонок: " + r.level.ru.lowercased(), details: r.reasons)
        warn(r.level, r.score, "Подозрительный входящий звонок", r.reasons, r.advice, DemoScenario.number, nil)
    }

    private func stepSpeech(pace: UInt64, id: Int) async {
        active = 2; lines = 0
        model.hideWarning()
        var warned = false
        for i in 1...DemoScenario.call.count {
            try? await Task.sleep(nanoseconds: pace)
            if Task.isCancelled || id != runId { return }
            withAnimation(.easeOut(duration: 0.3)) { lines = i }
            let r = ScamAnalyzer.analyze(DemoScenario.callerText(i))
            results[2] = StepResult(level: r.level, score: r.score, headline: "Речь звонящего: " + r.level.ru.lowercased(), details: r.tactics.map(\.title))
            if r.level == .high && !warned {
                warned = true
                warn(r.level, r.score, r.tactics.first?.title ?? "Признаки обмана в разговоре", r.tactics.map(\.title), r.tactics.map(\.advice),
                     DemoScenario.number, DemoScenario.callerText(i))
            }
        }
    }

    private func stepSms() {
        active = 3
        guard let code = SmsCode.find(DemoScenario.sms) else { return }
        let reasons = ["Код \(code.masked) пришёл во время звонка с незнакомого номера", "Звонящий просил продиктовать код"]
        results[3] = StepResult(level: .high, score: 95, headline: "SMS с кодом во время звонка: высокий риск", details: reasons)
        warn(.high, 95, "Код из SMS во время звонка с незнакомым номером", reasons,
             ["Никому не называйте этот код. Банк его никогда не спрашивает."], DemoScenario.number, nil)
    }

    private func stepPhishing() {
        active = 4
        let r = ScamAnalyzer.analyze(DemoScenario.phishing)
        let link = r.tactics.first { $0.id == "phishing_link" }
        results[4] = StepResult(level: r.level, score: r.score, headline: "Сообщение со ссылкой: " + r.level.ru.lowercased(),
                                details: r.tactics.map(\.title) + (link?.evidence.map(\.context) ?? []))
        warn(r.level, r.score, link?.title ?? "Подозрительное сообщение", r.tactics.map(\.title), r.tactics.map(\.advice), nil, DemoScenario.phishing)
    }

    private func stepNormal() {
        active = 5
        model.hideWarning()
        let r = ScamAnalyzer.analyze(DemoScenario.normal)
        results[5] = StepResult(level: r.level, score: r.score, headline: "Обычное сообщение: " + r.level.ru.lowercased(),
                                details: [r.tactics.isEmpty ? "Тактик мошенников не найдено — предупреждения нет" : r.summary])
    }

    private func launch(_ body: @escaping (Int) async -> Void) {
        task?.cancel()
        runId += 1
        let id = runId
        running = true
        task = Task { @MainActor in
            await body(id)
            if id == runId { running = false }
        }
    }

    private func pause(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }

    private func runAll() {
        results = [:]; lines = 0
        launch { id in
            stepCall(); await pause(5); if id != runId || Task.isCancelled { return }
            await stepSpeech(pace: 2_200_000_000, id: id); await pause(4.5); if id != runId || Task.isCancelled { return }
            stepSms(); await pause(5); if id != runId || Task.isCancelled { return }
            stepPhishing(); await pause(5); if id != runId || Task.isCancelled { return }
            stepNormal(); await pause(2.5)
            if id == runId { withAnimation { active = 6 } }
        }
    }

    private func runOne(_ n: Int) {
        launch { id in
            switch n {
            case 1: stepCall()
            case 2: await stepSpeech(pace: 1_400_000_000, id: id)
            case 3: stepSms()
            case 4: stepPhishing()
            default: stepNormal()
            }
        }
    }

    private func stop() { task?.cancel(); runId += 1; running = false; model.hideWarning() }

    // ---- views
    private func step(_ n: Int, _ icon: String, _ title: String, _ sub: String) -> some View {
        step(n, icon, title, sub) { EmptyView() }
    }

    private func step<Extra: View>(_ n: Int, _ icon: String, _ title: String, _ sub: String, @ViewBuilder extra: () -> Extra) -> some View {
        let r = results[n]
        let isActive = active == n
        let c = r.map { NS.level($0.level) } ?? (isActive ? NS.violet : NS.muted)
        return GlassCard(accent: isActive || r != nil ? c : nil) {
            HStack(spacing: 10) {
                ZStack {
                    Circle().fill(c.opacity(0.16)); Circle().stroke(c.opacity(0.6))
                    if r != nil && !isActive { Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(c) }
                    else { Text("\(n)").font(.system(size: 14, weight: .bold)).foregroundStyle(c) }
                }.frame(width: 30, height: 30)
                Image(systemName: icon).foregroundStyle(NS.text2)
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(NS.text)
                Spacer(minLength: 4)
                if !running { Button("Показать") { runOne(n) }.font(.system(size: 13, weight: .semibold)).foregroundStyle(NS.cyan) }
            }
            Text(sub).font(.system(size: 13)).foregroundStyle(NS.text2).lineLimit(3)
            extra()
            if let r {
                DangerMeter(level: r.level, score: r.score)
                Text(r.headline).font(.system(size: 14, weight: .semibold)).foregroundStyle(NS.level(r.level))
                ForEach(Array(r.details.prefix(5).enumerated()), id: \.offset) { _, d in Bullet(text: d) }
            }
        }
    }

    private var transcript: some View {
        let r = ScamAnalyzer.analyze(DemoScenario.callerText(lines))
        let matches = r.tactics.flatMap { $0.evidence.map(\.match) }
        return VStack(spacing: 6) {
            ForEach(Array(DemoScenario.call.prefix(lines).enumerated()), id: \.offset) { _, l in
                let hit = l.caller && matches.contains { ScamAnalyzer.normalize(l.text).contains($0) }
                HStack {
                    if !l.caller { Spacer(minLength: 40) }
                    Text(l.text).font(.system(size: 13)).foregroundStyle(hit ? NS.text : NS.text2)
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(RoundedRectangle(cornerRadius: 12).fill(!l.caller ? NS.surface3 : hit ? NS.threat.opacity(0.16) : NS.surface2))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(hit ? NS.threat.opacity(0.5) : .clear))
                    if l.caller { Spacer(minLength: 40) }
                }.transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
    }
}
