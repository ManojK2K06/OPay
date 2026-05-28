import Foundation
import CoreData
import CryptoKit
import Network

// MARK: – CoreData Stack

final class PersistenceController {
    static let shared = PersistenceController()

    let container: NSPersistentContainer

    init(inMemory: Bool = false) {
        container = NSPersistentContainer(name: "OPayModel")
        if inMemory {
            container.persistentStoreDescriptions.first?.url = URL(fileURLWithPath: "/dev/null")
        }
        container.loadPersistentStores { _, error in
            if let error = error { fatalError("CoreData load failed: \(error)") }
        }
        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
    }
}

// MARK: – Offline Transaction Entity (maps to OPayPendingTxn NSManagedObject)
// The NSManagedObject subclass is auto-generated from OPayModel.xcdatamodeld.
// Entity: OPayPendingTxn
//   uuid          : String  (immutable PK)
//   senderAccount : String
//   receiverAccount: String
//   amountPaise   : Int64
//   timestamp     : Date
//   smsPayload    : String  (Base85-encoded encrypted payload)
//   status        : String  (pending | dispatched | confirmed | failed)
//   chainHash     : String  (SHA-256 of prev chainHash + this txn fields – hex)
//   retryCount    : Int16
//   createdAt     : Date

// MARK: – Offline Queue Manager

final class OfflineQueueManager: ObservableObject {

    static let shared = OfflineQueueManager()

    @Published var pendingCount: Int = 0
    @Published var isOnline: Bool = false

    private let context: NSManagedObjectContext
    private let monitor = NWPathMonitor(requiredInterfaceType: .cellular)
    private let monitorQueue = DispatchQueue(label: "com.opay.netmonitor")

    private init() {
        context = PersistenceController.shared.container.newBackgroundContext()
        context.mergePolicy = NSMergeByPropertyStoreTrumpMergePolicy
        startNetworkMonitor()
        refreshPendingCount()
    }

    // MARK: – Enqueue

    /// Appends a new pending transaction to the immutable hash-chain queue.
    func enqueue(frame: OPayWireFrame, smsPayload: String) throws {
        try context.performAndWait {
            let entity = NSEntityDescription.insertNewObject(
                forEntityName: "OPayPendingTxn",
                into: context
            )
            let uuid = UUID().uuidString
            entity.setValue(uuid, forKey: "uuid")
            entity.setValue(String(frame.senderAccount), forKey: "senderAccount")
            entity.setValue(String(frame.receiverAccount), forKey: "receiverAccount")
            entity.setValue(Int64(frame.amountPaise), forKey: "amountPaise")
            entity.setValue(Date(timeIntervalSince1970: TimeInterval(frame.timestamp)), forKey: "timestamp")
            entity.setValue(smsPayload, forKey: "smsPayload")
            entity.setValue("pending", forKey: "status")
            entity.setValue(Date(), forKey: "createdAt")
            entity.setValue(Int16(0), forKey: "retryCount")

            // Hash-chain: SHA256(prevHash || uuid || sender || receiver || amount || timestamp)
            let prevHash = try self.latestChainHash()
            let chainInput = prevHash + uuid
                + String(frame.senderAccount) + String(frame.receiverAccount)
                + String(frame.amountPaise) + String(frame.timestamp)
            let hash = SHA256.hash(data: Data(chainInput.utf8))
            let hexHash = hash.compactMap { String(format: "%02x", $0) }.joined()
            entity.setValue(hexHash, forKey: "chainHash")

            try context.save()
        }
        refreshPendingCount()
    }

    // MARK: – Chain Integrity Verification

    /// Verifies the entire hash-chain integrity of the offline queue.
    func verifyChainIntegrity() throws -> Bool {
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "OPayPendingTxn")
            request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
            let items = try context.fetch(request)

            var prevHash = "GENESIS"
            for item in items {
                let uuid = item.value(forKey: "uuid") as! String
                let sender = item.value(forKey: "senderAccount") as! String
                let receiver = item.value(forKey: "receiverAccount") as! String
                let amount = item.value(forKey: "amountPaise") as! Int64
                let ts = (item.value(forKey: "timestamp") as! Date).timeIntervalSince1970
                let storedHash = item.value(forKey: "chainHash") as! String

                let chainInput = prevHash + uuid + sender + receiver + String(amount) + String(Int(ts))
                let computedHash = SHA256.hash(data: Data(chainInput.utf8))
                    .compactMap { String(format: "%02x", $0) }.joined()

                guard computedHash == storedHash else { return false }
                prevHash = storedHash
            }
            return true
        }
    }

    // MARK: – Dispatch Pending

    /// Attempts to dispatch all pending transactions via SMS.
    func dispatchPending(smsDispatcher: @escaping (String, String) -> Void) {
        context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "OPayPendingTxn")
            request.predicate = NSPredicate(format: "status == %@", "pending")
            request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
            guard let items = try? self.context.fetch(request) else { return }

            for item in items {
                let payload = item.value(forKey: "smsPayload") as! String
                let sender = item.value(forKey: "senderAccount") as! String
                // In a real deployment, the gateway number is configured per-network.
                let gatewayNumber = ConfigManager.shared.gatewayPhoneNumber
                smsDispatcher(gatewayNumber, payload)
                item.setValue("dispatched", forKey: "status")
                let retry = (item.value(forKey: "retryCount") as! Int16) + 1
                item.setValue(retry, forKey: "retryCount")
            }
            try? self.context.save()
            self.refreshPendingCount()
        }
    }

    // MARK: – Network Monitor

    private func startNetworkMonitor() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            DispatchQueue.main.async {
                self?.isOnline = online
                if online {
                    // Auto-dispatch pending queue when cellular returns
                    self?.dispatchPending { number, payload in
                        SMSGatewayClient.shared.send(to: number, payload: payload)
                    }
                }
            }
        }
        monitor.start(queue: monitorQueue)
    }

    // MARK: – Helpers

    private func latestChainHash() throws -> String {
        let request = NSFetchRequest<NSManagedObject>(entityName: "OPayPendingTxn")
        request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: false)]
        request.fetchLimit = 1
        let items = try context.fetch(request)
        return items.first.flatMap { $0.value(forKey: "chainHash") as? String } ?? "GENESIS"
    }

    private func refreshPendingCount() {
        context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "OPayPendingTxn")
            request.predicate = NSPredicate(format: "status == %@", "pending")
            let count = (try? self.context.count(for: request)) ?? 0
            DispatchQueue.main.async { self.pendingCount = count }
        }
    }

    // MARK: – History Fetch

    func fetchHistory(limit: Int = 50) throws -> [[String: Any]] {
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "OPayPendingTxn")
            request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: false)]
            request.fetchLimit = limit
            let items = try context.fetch(request)
            return items.map { obj in
                [
                    "uuid": obj.value(forKey: "uuid") as! String,
                    "senderAccount": obj.value(forKey: "senderAccount") as! String,
                    "receiverAccount": obj.value(forKey: "receiverAccount") as! String,
                    "amountPaise": obj.value(forKey: "amountPaise") as! Int64,
                    "status": obj.value(forKey: "status") as! String,
                    "createdAt": obj.value(forKey: "createdAt") as! Date,
                    "chainHash": obj.value(forKey: "chainHash") as! String
                ]
            }
        }
    }
}
