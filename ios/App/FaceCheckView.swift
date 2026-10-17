import AVFoundation
import SwiftUI
import UIKit
import Vision

/// Active liveness on the device: the person turns the head to one side and then to the other.
/// A photo or a still screen cannot do that. Uses Apple Vision face detection (yaw angle); frames stay in memory.
final class LivenessCamera: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    enum Phase: Equatable { case idle, noCamera, denied, center, firstTurn, secondTurn, passed, failed(String) }

    @Published var phase: Phase = .idle
    @Published var faceFound = false
    @Published var yaw: Double = 0
    @Published var secondsLeft = 15

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "liveness.camera")
    private var firstSign: Double = 0
    private var started = Date()
    private var framesWithoutFace = 0
    private var configured = false

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configureAndRun()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { if ok { self.configureAndRun() } else { self.phase = .denied } }
            }
        default: phase = .denied
        }
    }

    func stop() { queue.async { if self.session.isRunning { self.session.stopRunning() } } }

    private func configureAndRun() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device) else { phase = .noCamera; return }
        queue.async {
            if !self.configured {
                self.session.beginConfiguration()
                self.session.sessionPreset = .vga640x480
                if self.session.canAddInput(input) { self.session.addInput(input) }
                let out = AVCaptureVideoDataOutput()
                out.alwaysDiscardsLateVideoFrames = true
                out.setSampleBufferDelegate(self, queue: self.queue)
                if self.session.canAddOutput(out) { self.session.addOutput(out) }
                self.session.commitConfiguration()
                self.configured = true
            }
            self.session.startRunning()
            DispatchQueue.main.async { self.begin() }
        }
    }

    func begin() {
        firstSign = 0; framesWithoutFace = 0; started = Date(); secondsLeft = 15
        phase = .center
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let request = VNDetectFaceRectanglesRequest()
        request.revision = VNDetectFaceRectanglesRequestRevision3      // provides yaw
        try? VNImageRequestHandler(cvPixelBuffer: pixels, orientation: .leftMirrored).perform([request])
        let face = (request.results ?? []).max { $0.boundingBox.width < $1.boundingBox.width }
        let y = face?.yaw?.doubleValue ?? 0
        DispatchQueue.main.async { self.update(face: face != nil, yaw: y) }
    }

    private func update(face: Bool, yaw y: Double) {
        faceFound = face; yaw = y
        let elapsed = Date().timeIntervalSince(started)
        secondsLeft = max(0, 15 - Int(elapsed))
        switch phase {
        case .center, .firstTurn, .secondTurn: break
        default: return
        }
        if elapsed > 15 { phase = .failed("Время вышло: поворотов головы не было"); return }
        framesWithoutFace = face ? 0 : framesWithoutFace + 1
        if framesWithoutFace > 45 { phase = .failed("Лицо пропало из кадра"); return }
        guard face else { return }
        switch phase {
        case .center: if abs(y) < 0.15 { phase = .firstTurn }
        case .firstTurn: if abs(y) > 0.3 { firstSign = y > 0 ? 1 : -1; phase = .secondTurn }
        case .secondTurn: if y * firstSign < -0.3 { phase = .passed; stop() }
        default: break
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView(); v.previewLayer.session = session; v.previewLayer.videoGravity = .resizeAspectFill; return v
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

struct FaceCheckView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var cam = LivenessCamera()
    @State private var saved = false

    var body: some View {
        Group {
            PageHeader(title: "Проверка лица", sub: "Живой ли человек перед камерой")
            ZStack {
                RoundedRectangle(cornerRadius: 26).fill(NS.surface)
                if [.center, .firstTurn, .secondTurn].contains(cam.phase) {
                    CameraPreview(session: cam.session).clipShape(RoundedRectangle(cornerRadius: 26))
                    Ellipse().stroke(cam.faceFound ? NS.safe : NS.warn, lineWidth: 3).frame(width: 200, height: 260)
                } else {
                    StatusOrb(color: color, size: 120, system: icon)
                }
            }
            .frame(height: 360)
            .overlay(RoundedRectangle(cornerRadius: 26).stroke(color.opacity(0.6)))
            GlassCard(accent: color) {
                Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(NS.text)
                Text(hint).font(.system(size: 14)).foregroundStyle(NS.text2)
                if [.center, .firstTurn, .secondTurn].contains(cam.phase) {
                    HStack {
                        Pill(text: cam.faceFound ? "Лицо в кадре" : "Лицо не найдено", color: cam.faceFound ? NS.safe : NS.warn)
                        Spacer()
                        Text("\(cam.secondsLeft) с").font(.system(size: 14, weight: .semibold)).foregroundStyle(NS.muted)
                    }
                }
            }
            switch cam.phase {
            case .idle, .passed, .failed:
                PrimaryButton(title: cam.phase == .idle ? "Начать проверку" : "Проверить ещё раз") { saved = false; cam.start() }
            case .denied:
                PrimaryButton(title: "Разрешить камеру в Настройках") {
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }
            default: EmptyView()
            }
            Text("Кадры обрабатываются в памяти телефона и не сохраняются. Проверка отличает живого человека от фото; она не распознаёт дипфейки и не сравнивает лицо с документами.")
                .font(.system(size: 12)).foregroundStyle(NS.muted)
        }
        .page()
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { cam.stop() }
        .onChange(of: cam.phase) { p in
            guard !saved else { return }
            if p == .passed {
                saved = true
                model.store.add(Alert(source: "face", number: nil, level: .low, score: 0, title: "Проверка живости пройдена",
                                      reasons: ["Голова повернулась в обе стороны по заданию"]))
                model.refresh()
            } else if case .failed(let why) = p {
                saved = true
                model.store.add(Alert(source: "face", number: nil, level: .medium, score: 50, title: "Проверка живости не пройдена", reasons: [why]))
                model.refresh()
            }
        }
    }

    private var color: Color {
        switch cam.phase {
        case .passed: return NS.safe
        case .failed, .denied, .noCamera: return NS.warn
        default: return NS.cyan
        }
    }
    private var icon: String {
        switch cam.phase {
        case .passed: return "checkmark.seal.fill"
        case .failed, .denied, .noCamera: return "exclamationmark.triangle.fill"
        default: return "faceid"
        }
    }
    private var title: String {
        switch cam.phase {
        case .idle: return "Готово к проверке"
        case .noCamera: return "Камера недоступна"
        case .denied: return "Нет доступа к камере"
        case .center: return "Посмотрите прямо в камеру"
        case .firstTurn: return "Медленно поверните голову в сторону"
        case .secondTurn: return "Теперь — в другую сторону"
        case .passed: return "Живой человек"
        case .failed: return "Проверка не пройдена"
        }
    }
    private var hint: String {
        switch cam.phase {
        case .idle: return "Нужно будет посмотреть в камеру и повернуть голову в одну сторону, затем в другую. Фото или экран так не смогут."
        case .noCamera: return "На этом устройстве нет фронтальной камеры (например, в симуляторе)."
        case .denied: return "Разрешите доступ к камере для NeuroShield в Настройках iPhone."
        case .center, .firstTurn, .secondTurn: return "Держите лицо в овале. Угол поворота: \(Int(cam.yaw * 180 / .pi))°"
        case .passed: return "Голова повернулась в обе стороны — это не фото и не неподвижный экран."
        case .failed(let why): return why
        }
    }
}
