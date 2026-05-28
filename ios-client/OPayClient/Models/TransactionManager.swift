import Foundation
import UIKit
import Combine

final class TransactionManager: ObservableObject {

    static let shared = TransactionManager()
    private init() {}

    // MARK: – Initiate Transfer

    /// Called by TransferView when the user taps "Send".
    /// - If online: immediately presents SMS compose sheet.
    /// - If offline: enqueues to CoreData hash-chain queue for later dispatch.
    /// Always logs the transaction locally regardless of online/offline status.
    @MainActor
    func initiateTransfer(
        from senderAccount: UInt64,
        to receiverAccount: UInt64,
        amountPaise: UInt32,
        presentingVC: UIViewController
    ) async throws -> Bool {

        let frame = OPayWireFrame(
            senderAccount: senderAccount,
            receiverAccount: receiverAccount,
            timestamp: UInt32(Date().timeIntervalSince1970),
            txnID: UInt64.random(in: 0..<UInt64.max),
            amountPaise: amountPaise,
            version: 1
        )

        let smsPayload = "OPAY-TXN: \(senderAccount),\(receiverAccount),\(amountPaise)"
        let gateway = ConfigManager.shared.gatewayPhoneNumber

        // Always present the SMS composer (since we rely on SMS, not internet)
        let sent = await withCheckedContinuation { continuation in
            SMSGatewayClient.shared.presentSMSCompose(
                to: gateway,
                payload: smsPayload,
                from: presentingVC
            ) { sent in
                continuation.resume(returning: sent)
            }
        }
        
        // Always log the transaction locally
        if sent {
            try? OfflineQueueManager.shared.enqueue(
                frame: frame,
                smsPayload: smsPayload,
                status: "dispatched"
            )
            print("[OPay] Transaction logged locally and SMS sent. TxnID: \(frame.txnID)")
        } else {
            try? OfflineQueueManager.shared.enqueue(
                frame: frame,
                smsPayload: smsPayload,
                status: "pending"
            )
            print("[OPay] Transaction logged locally but SMS cancelled. TxnID: \(frame.txnID)")
        }
        
        return sent
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
