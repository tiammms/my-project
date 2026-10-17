import CallKit
import Foundation

/// Numbers the user marked as scammers in NeuroShield: iOS shows the label «NeuroShield: отмечен вами как мошенник»
/// on the incoming-call screen and, if blocking is on, rejects the call.
/// Turned on by the user: Settings → Apps → Phone → Call Blocking & Identification → NeuroShield.
final class CallDirectoryHandler: CXCallDirectoryProvider {
    override func beginRequest(with context: CXCallDirectoryExtensionContext) {
        let store = SharedStore.shared
        let numbers = Array(Set(store.scamNumbers().compactMap(callKitNumber))).sorted()   // CallKit requires ascending order
        if store.blockMarked {
            for n in numbers { context.addBlockingEntry(withNextSequentialPhoneNumber: n) }
        }
        for n in numbers {
            context.addIdentificationEntry(withNextSequentialPhoneNumber: n, label: "NeuroShield: отмечен вами как мошенник")
        }
        context.completeRequest()
    }
}
