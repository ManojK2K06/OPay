import Foundation
import MessageUI
import UIKit

// MARK: – Configuration

final class ConfigManager {
    static let shared = ConfigManager()
    private init() {}

    var gatewayPhoneNumber: String {
        Bundle.main.infoDictionary?["OPAY_GATEWAY_PHONE"] as? String ?? "+919380865986"
    }
    var serverBaseURL: String {
        Bundle.main.infoDictionary?["OPAY_SERVER_URL"] as? String ?? "http://192.168.1.100:8080"
    }
}

// MARK: – SMS Gateway Client

/// Sends SMS via MFMessageComposeViewController (requires user confirmation)
/// or falls back to a direct Android Wi-Fi Gateway HTTP call on LAN.
final class SMSGatewayClient: NSObject {

    static let shared = SMSGatewayClient()
    private override init() {}

    /// Primary path: present MFMessageComposeViewController to send the SMS.
    /// The SMS body is the encrypted Base85 payload (≤160 chars).
    @MainActor
    func presentSMSCompose(to recipient: String,
                           payload: String,
                           from viewController: UIViewController,
                           completion: @escaping (Bool) -> Void) {
        guard MFMessageComposeViewController.canSendText() else {
            // Fallback to LAN gateway when SMS hardware unavailable (simulator)
            send(to: recipient, payload: payload)
            completion(false)
            return
        }

        let composer = MFMessageComposeViewController()
        composer.recipients = [recipient]
        composer.body = payload
        composer.messageComposeDelegate = self

        self._pendingCompletion = completion
        viewController.present(composer, animated: true)
    }

    private var _pendingCompletion: ((Bool) -> Void)?

    /// Secondary path: HTTP POST to Android Wi-Fi SMS Gateway (no user prompt, LAN only).
    func send(to recipient: String, payload: String) {
        let urlString = "\(ConfigManager.shared.serverBaseURL)/api/v1/sms/send"
        guard let url = URL(string: urlString) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: String] = ["to": recipient, "message": payload]
        request.httpBody = try? JSONEncoder().encode(body)
        request.timeoutInterval = 10

        URLSession.shared.dataTask(with: request) { _, response, error in
            if let error = error {
                print("[OPay SMS] LAN gateway error: \(error.localizedDescription)")
            }
        }.resume()
    }

    // MARK: – Balance Check SMS

    /// Sends a balance enquiry SMS (plain, no encryption needed for request).
    func sendBalanceCheck(account: String, to recipient: String,
                          from vc: UIViewController) async {
        let body = "OPAY-BAL: \(account)"
        await MainActor.run {
            presentSMSCompose(to: recipient, payload: body, from: vc) { _ in }
        }
    }
}

// MARK: – MFMessageComposeViewControllerDelegate

extension SMSGatewayClient: MFMessageComposeViewControllerDelegate {
    func messageComposeViewController(
        _ controller: MFMessageComposeViewController,
        didFinishWith result: MessageComposeResult
    ) {
        controller.dismiss(animated: true)
        _pendingCompletion?(result == .sent)
        _pendingCompletion = nil
    }
}
