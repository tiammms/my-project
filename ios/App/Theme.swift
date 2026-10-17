import SwiftUI

/// Dark premium cybersecurity palette — the same tokens as the Android app.
enum NS {
    static let bg = Color(hex: 0x07080A)
    static let surface = Color(hex: 0x0F1115)
    static let surface2 = Color(hex: 0x161A21)
    static let surface3 = Color(hex: 0x1D2129)
    static let border = Color(hex: 0x252B36)
    static let text = Color(hex: 0xF2F4F7)
    static let text2 = Color(hex: 0xB4BCC8)
    static let muted = Color(hex: 0x7A8494)
    static let cyan = Color(hex: 0x22D3EE)
    static let threat = Color(hex: 0xFF3B5C)
    static let warn = Color(hex: 0xFFB020)
    static let safe = Color(hex: 0x2EE59D)
    static let violet = Color(hex: 0x8B7CF6)

    static func level(_ l: RiskLevel) -> Color {
        switch l {
        case .high: return threat
        case .medium: return warn
        case .low: return safe
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

struct GlassCard<Content: View>: View {
    var accent: Color? = nil
    var padding: CGFloat = 16
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(LinearGradient(colors: [NS.surface2, NS.surface], startPoint: .top, endPoint: .bottom)))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(accent.map { $0.opacity(0.55) } ?? NS.border, lineWidth: 1))
            .shadow(color: (accent ?? .clear).opacity(0.18), radius: 18, y: 6)
    }
}

struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.system(size: 12, weight: .semibold)).tracking(1.2).foregroundStyle(NS.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
    }
}

struct IconBadge: View {
    let system: String; let tint: Color; var size: CGFloat = 40
    var body: some View {
        Image(systemName: system).font(.system(size: size * 0.45, weight: .semibold)).foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.32).fill(tint.opacity(0.14)))
            .overlay(RoundedRectangle(cornerRadius: size * 0.32).stroke(tint.opacity(0.35), lineWidth: 1))
    }
}

struct Pill: View {
    let text: String; let color: Color
    var body: some View {
        Text(text).font(.system(size: 12, weight: .semibold)).foregroundStyle(color)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Capsule().fill(color.opacity(0.14))).overlay(Capsule().stroke(color.opacity(0.4), lineWidth: 1))
    }
}

struct PrimaryButton: View {
    let title: String; var color: Color = NS.cyan; var enabled = true; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 16, weight: .bold)).foregroundStyle(Color(hex: 0x07080A))
                .frame(maxWidth: .infinity).padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 16).fill(color.opacity(enabled ? 1 : 0.35)))
        }.disabled(!enabled).buttonStyle(.plain)
    }
}

struct GhostButton: View {
    let title: String; var color: Color = NS.text; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(color)
                .frame(maxWidth: .infinity).padding(.vertical, 13)
                .background(RoundedRectangle(cornerRadius: 16).stroke(NS.border, lineWidth: 1))
        }.buttonStyle(.plain)
    }
}

/// Three neon segments + level + score.
struct DangerMeter: View {
    let level: RiskLevel; var score: Double? = nil
    var body: some View {
        let c = NS.level(level)
        let on = level == .high ? 3 : level == .medium ? 2 : 1
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { i in
                Capsule().fill(i < on ? c : c.opacity(0.16)).frame(height: 6)
            }
            Text(level.short + (score.map { " · \(Int($0))" } ?? "")).font(.system(size: 13, weight: .bold)).foregroundStyle(c)
                .fixedSize()
        }
    }
}

struct RingGauge: View {
    let progress: Double; let color: Color; var size: CGFloat = 76; var label: String
    var body: some View {
        ZStack {
            Circle().stroke(NS.surface3, lineWidth: 8)
            Circle().trim(from: 0, to: max(0.02, min(1, progress))).stroke(color, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(label).font(.system(size: 20, weight: .bold)).foregroundStyle(NS.text)
        }.frame(width: size, height: size)
    }
}

/// Glowing orb with a pulsing ring.
struct StatusOrb: View {
    let color: Color; var size: CGFloat = 96; var system = "checkmark.shield.fill"
    @State private var pulse = false
    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.5), lineWidth: 2).scaleEffect(pulse ? 1.35 : 1).opacity(pulse ? 0 : 0.9)
            Circle().fill(RadialGradient(colors: [color.opacity(0.35), color.opacity(0.05)], center: .center, startRadius: 2, endRadius: size / 2))
            Circle().stroke(color.opacity(0.7), lineWidth: 1.5)
            Image(systemName: system).font(.system(size: size * 0.38, weight: .semibold)).foregroundStyle(color)
        }
        .frame(width: size, height: size)
        .shadow(color: color.opacity(0.45), radius: 22)
        .onAppear { withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) { pulse = true } }
    }
}

struct Bullet: View {
    let text: String; var color: Color = NS.text2
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle().fill(NS.cyan).frame(width: 5, height: 5).offset(y: -2)
            Text(text).font(.system(size: 14)).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct PageHeader: View {
    let title: String; var sub: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 30, weight: .bold)).foregroundStyle(NS.text)
            if let sub { Text(sub).font(.system(size: 14)).foregroundStyle(NS.text2) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
    }
}

extension View {
    /// Standard scrolling page with the app background.
    func page() -> some View {
        ScrollView { VStack(spacing: 14) { self }.padding(.horizontal, 18).padding(.bottom, 30) }
            .background(NS.bg.ignoresSafeArea())
            .scrollDismissesKeyboard(.interactively)
    }
}
