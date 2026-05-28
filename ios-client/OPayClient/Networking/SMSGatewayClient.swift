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
        Bundle.main.infoDictionary?["OPAY_SERVER_URL"] as? String ?? "http://192.168.29.106:8080"
    }
    
    /// The Cloudflare tunnel URL for direct HTTP communication with the server.
    /// Update this whenever you start a new tunnel session.
    var tunnelURL: String {
        get {
            UserDefaults.standard.string(forKey: "opay.tunnelURL")
                ?? "https://haven-soma-organizer-elimination.trycloudflare.com"
        }
        set { UserDefaults.standard.set(newValue, forKey: "opay.tunnelURL") }
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

    // MARK: – HTTP Balance Poll
    
    /// Polls the server's HTTP balance endpoint directly via the Cloudflare tunnel.
    /// This is much more reliable than deep links since iOS blocks custom URL schemes.
    func pollBalance(account: String) async -> Int64? {
        let urlString = "\(ConfigManager.shared.tunnelURL)/api/v1/admin/balance/\(account)"
        guard let url = URL(string: urlString) else {
            print("[OPay] Invalid balance URL: \(urlString)")
            return nil
        }
        
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue("true", forHTTPHeaderField: "bypass-tunnel-reminder")
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                print("[OPay] Balance poll failed with status: \((response as? HTTPURLResponse)?.statusCode ?? -1)")
                return nil
            }
            
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let balancePaise = json["balancePaise"] as? Int64 {
                print("[OPay] Balance poll success: \(balancePaise) paise")
                return balancePaise
            } else if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let balancePaise = json["balancePaise"] as? Int {
                print("[OPay] Balance poll success: \(balancePaise) paise")
                return Int64(balancePaise)
            }
        } catch {
            print("[OPay] Balance poll error: \(error.localizedDescription)")
        }
        return nil
    }

    // MARK: – Balance Check SMS

    /// Sends a balance enquiry SMS (plain, no encryption needed for request).
    func sendBalanceCheck(account: String, to recipient: String,
                          from vc: UIViewController) async {
        let body = "OPAY-ENQ: \(account)"
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
