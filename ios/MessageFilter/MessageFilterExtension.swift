import IdentityLookup

/// iOS hands this extension the text of SMS / iMessage from senders NOT in the user's contacts
/// (after the user turns it on: Settings → Apps → Messages → Unknown & Spam → NeuroShield).
/// Messages with a HIGH risk go to the «Junk» folder; nothing is sent anywhere and nothing is stored
/// (Apple does not let this extension write data or reach the network without an explicit server setup).
final class MessageFilterExtension: ILMessageFilterExtension {}

extension MessageFilterExtension: ILMessageFilterQueryHandling {
    func handle(_ queryRequest: ILMessageFilterQueryRequest, context: ILMessageFilterExtensionContext,
                completion: @escaping (ILMessageFilterQueryResponse) -> Void) {
        let response = ILMessageFilterQueryResponse()
        let risk = ScamAnalyzer.analyze(queryRequest.messageBody ?? "")
        response.action = risk.level == .high ? .junk : .none
        completion(response)
    }
}
