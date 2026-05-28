# Technical Architecture Document
**Project:** OPay - Offline SMS Payment System

## 1. Database Management System (DBMS) Aspects

OPay utilizes a dual-database architecture to handle both local device persistence (when offline) and centralized server validation (the source of truth).

### 1.1 Server-Side Database (Java Spring Boot)
*   **DBMS Choice:** H2 Database Engine (Relational Database).
*   **Storage Mode:** File-based persistence (`opaydb.mv.db`) ensuring data is preserved across server restarts, while remaining lightweight and requiring zero manual installation or configuration.
*   **ACID Compliance:** The system leverages Spring Data JPA with `@Transactional` annotations on service methods (e.g., `TransactionValidationService.java`). This guarantees Atomicity, Consistency, Isolation, and Durability. If a transaction fails mid-process (e.g., due to insufficient funds), the entire database state is rolled back, preventing orphaned records or money duplication.
*   **Schema Design:**
    *   `OPayAccount`: Stores user account numbers, public keys (for future ECC encryption), and current balance (stored as `balancePaise` to avoid floating-point inaccuracies).
    *   `OPayTransaction`: Logs every validated transfer, storing the sender, receiver, amount, timestamp, and a globally unique transaction ID.
    *   `OPayAuditLog`: An append-only security log tracking system events, failed routing attempts, and gateway health.

### 1.2 Client-Side Database (iOS CoreData)
*   **DBMS Choice:** Apple CoreData (backed by SQLite).
*   **Purpose:** To provide a robust, persistent local queue for transaction history and offline dispatching.
*   **Entity Structure:** The `OPayPendingTxn` entity tracks the sender, receiver, amount, timestamp, SMS payload, and the dispatch status (`pending` vs `dispatched`).

---

## 2. Optimization Algorithms & Concepts

### 2.1 Cryptographic Hash-Chaining (Local Integrity Algorithm)
To prevent malicious users from tampering with the local SQLite database on their jailbroken iPhones (e.g., artificially altering a queued offline transaction before it is dispatched), OPay implements an immutable Hash-Chain algorithm in `OfflineQueueManager.swift`.
*   **How it works:** Each new transaction queued in CoreData generates a SHA-256 hash that incorporates the data of the *current* transaction PLUS the `chainHash` of the *previous* transaction.
*   **Optimization:** This provides blockchain-like immutability with O(1) insertion time. When the app boots, it can verify the integrity of the entire offline queue in O(N) time by walking the chain. If any record was altered externally, the hashes will break, and the app can invalidate the queue.

### 2.2 Dynamic Polling & Backoff Algorithm
When an iOS device has internet access but initiates an SMS transaction (hybrid state), waiting for an SMS reply can take anywhere from 5 to 30 seconds due to cellular network delays.
*   **Optimization:** The iOS app implements a proactive, bounded HTTP polling loop.
*   **Algorithm:** 
    1.  Send the SMS.
    2.  `Task.sleep` for exactly 5 seconds (initial buffer to allow the Android gateway to process the text).
    3.  Enter a loop with a strict limit (e.g., max 10 iterations).
    4.  Poll the server via the Cloudflare Tunnel. If the server has processed the transaction and updated the balance, update the UI instantly in O(1) time and break the loop.
    5.  If not, `Task.sleep` for 3 seconds and retry.
*   **Result:** This drastically reduces perceived latency for the user from ~15 seconds (waiting for an SMS reply) to ~6 seconds, while minimizing unnecessary server load and preventing infinite HTTP requests.

### 2.3 Non-Blocking Asynchronous Webhooks
On the Android Termux Gateway, incoming SMS messages are read via the `termux-sms-list` command.
*   **Optimization:** Instead of sequentially processing each SMS and blocking the thread waiting for the Java server to reply, the Python script uses concurrent API dispatching. It forwards the payload to the webhook and immediately loops back to check for new SMS messages.
*   **Result:** High throughput capability, preventing SMS bottlenecks on the gateway device during high traffic periods.
