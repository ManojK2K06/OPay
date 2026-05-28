import Foundation
import UIKit

final class TransactionManager: ObservableObject {

    static let shared = TransactionManager()
    private init() {}

    // MARK: – Initiate Transfer

    /// Called by TransferView when the user taps "Send".
    /// - If online: immediately presents SMS compose sheet.
    /// - If offline: enqueues to CoreData hash-chain queue for later dispatch.
    @MainActor
    func initiateTransfer(
        from senderAccount: UInt64,
        to receiverAccount: UInt64,
        amountPaise: UInt32,
        presentingVC: UIViewController
    ) async throws {
        // Build wire frame
        let frame = OPayWireFrame(
            senderAccount: senderAccount,
            receiverAccount: receiverAccount,
            timestamp: UInt32(Date().timeIntervalSince1970),
            txnID: UInt64.random(in: 0..<UInt64.max),
            amountPaise: amountPaise,
            version: 1
        )

        // Encrypt + sign → SMS payload
        let smsPayload = try ECCPayloadEngine.shared.buildSMSPayload(frame: frame)
        let gateway = ConfigManager.shared.gatewayPhoneNumber

        if OfflineQueueManager.shared.isOnline {
            // Online: dispatch immediately via SMS
            SMSGatewayClient.shared.presentSMSCompose(
                to: gateway,
                payload: smsPayload,
                from: presentingVC
            ) { sent in
                if !sent {
                    // User cancelled or error – enqueue as pending
                    try? OfflineQueueManager.shared.enqueue(frame: frame, smsPayload: smsPayload)
                }
            }
        } else {
            // Offline: store in hash-chain queue (The "Promise")
            try OfflineQueueManager.shared.enqueue(frame: frame, smsPayload: smsPayload)
            print("[OPay] Transaction queued offline. TxnID: \(frame.txnID)")
        }
    }

    // MARK: – Verify Queue

    func verifyQueueIntegrity() async throws -> Bool {
        return try OfflineQueueManager.shared.verifyChainIntegrity()
    }

    // MARK: – History

    func fetchTransactionHistory() throws -> [[String: Any]] {
        return try OfflineQueueManager.shared.fetchHistory()
    }
}
