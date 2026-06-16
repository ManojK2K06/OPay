import SwiftUI
import UIKit

// MARK: – Main Tab Container

struct MainTabView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        TabView {
            BalanceView()
                .tabItem { Label("Balance", systemImage: "indianrupeesign.circle.fill") }
            TransferView()
                .tabItem { Label("Transfer", systemImage: "arrow.right.arrow.left.circle.fill") }
            HistoryView()
                .tabItem { Label("History", systemImage: "clock.fill") }
        }
        .accentColor(Color(hex: "389B85"))
        .preferredColorScheme(.dark)
    }
}

// MARK: – Balance View

struct BalanceView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject private var queue = OfflineQueueManager.shared
    @State private var showRefreshAnimation = false

    var balanceFormatted: String {
        let rupees = Double(appState.balancePaise) / 100.0
        return String(format: "₹%.2f", rupees)
    }

    var body: some View {
        NavigationView {
            ZStack {
                backgroundGradient.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // Balance Card
                        VStack(spacing: 8) {
                            Text("Available Balance")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(Color.white.opacity(0.5))
                                .tracking(2)
                                .textCase(.uppercase)

                            if appState.isLoadingBalance {
                                ProgressView().tint(Color(hex: "389B85"))
                            } else {
                                Text(balanceFormatted)
                                    .font(.system(size: 52, weight: .heavy, design: .rounded))
                                    .foregroundStyle(
                                        LinearGradient(
                                            colors: [Color(hex: "389B85"), Color(hex: "226E5D")],
                                            startPoint: .leading, endPoint: .trailing
                                        )
                                    )
                            }

                            Text("A/C: \(appState.accountNumber)")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(Color.white.opacity(0.4))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(32)
                        .background(
                            RoundedRectangle(cornerRadius: 24)
                                .fill(Color.white.opacity(0.05))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 24)
                                        .stroke(Color(hex: "389B85").opacity(0.3), lineWidth: 1)
                                )
                        )

                        // Status Strip
                        HStack(spacing: 12) {
                            Circle()
                                .fill(queue.isOnline ? Color(hex: "389B85") : Color(hex: "FF4757"))
                                .frame(width: 8, height: 8)
                                .overlay(
                                    Circle()
                                        .stroke(queue.isOnline ? Color(hex: "389B85") : Color(hex: "FF4757"),
                                                lineWidth: 2)
                                        .scaleEffect(showRefreshAnimation ? 2 : 1)
                                        .opacity(showRefreshAnimation ? 0 : 0.6)
                                        .animation(.easeOut(duration: 1.2).repeatForever(autoreverses: false),
                                                   value: showRefreshAnimation)
                                )
                            Text(queue.isOnline ? "Online" : "Offline Mode")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(Color.white.opacity(0.6))
                            Spacer()
                            if queue.pendingCount > 0 {
                                Label("\(queue.pendingCount) pending", systemImage: "clock.arrow.2.circlepath")
                                    .font(.caption)
                                    .foregroundColor(Color(hex: "FFD700"))
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(Color.white.opacity(0.05))
                        .cornerRadius(12)

                        // Refresh button
                        OPayButton(title: "Check Balance via SMS") {
                            fetchBalance()
                        }
                    }
                    .padding(20)
                }
            }
            .navigationTitle("OPay")
            .navigationBarTitleDisplayMode(.large)
            .onAppear { showRefreshAnimation = true }
        }
    }

    private func fetchBalance() {
        guard let vc = UIApplication.shared.topViewController else { return }

        appState.isLoadingBalance = true
        Task {
            do {
                guard let senderAccount = UInt64(appState.accountNumber) else { return }
                
                let body = "OPAY-ENQ: \(senderAccount)"
                
                // Send the balance check SMS
                let smsSent = await withCheckedContinuation { continuation in
                    DispatchQueue.main.async {
                        SMSGatewayClient.shared.presentSMSCompose(
                            to: ConfigManager.shared.gatewayPhoneNumber,
                            payload: body, from: vc
                        ) { sent in
                            continuation.resume(returning: sent)
                        }
                    }
                }
                
                if smsSent {
                    // Poll the server directly via HTTP for balance
                    // Wait a few seconds for the SMS to reach the server
                    try await Task.sleep(nanoseconds: 5_000_000_000) // 5 second initial wait
                    
                    // Poll up to 10 times, every 3 seconds
                    for attempt in 1...10 {
                        print("[OPay] Balance poll attempt \(attempt)/10...")
                        if let balance = await SMSGatewayClient.shared.pollBalance(
                            account: appState.accountNumber
                        ) {
                            await MainActor.run {
                                self.appState.balancePaise = balance
                                self.appState.isLoadingBalance = false
                            }
                            print("[OPay] Balance updated: \(balance) paise")
                            return
                        }
                        try await Task.sleep(nanoseconds: 3_000_000_000) // 3 seconds between polls
                    }
                }
                
                // If we get here, polling timed out
                await MainActor.run {
                    self.appState.isLoadingBalance = false
                }
            } catch {
                await MainActor.run {
                    self.appState.isLoadingBalance = false
                }
            }
        }
    }
}

// MARK: – Transfer View

struct TransferView: View {
    @EnvironmentObject var appState: AppState
    @State private var receiverAccount: String = ""
    @State private var amountRupees: String = ""
    @State private var isProcessing: Bool = false
    @State private var resultMessage: String?
    @State private var showResult: Bool = false

    var body: some View {
        NavigationView {
            ZStack {
                backgroundGradient.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Form card
                        VStack(spacing: 16) {
                            Text("Transfer Funds")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity, alignment: .leading)

                            VStack(alignment: .leading, spacing: 6) {
                                fieldLabel("From")
                                Text(appState.accountNumber)
                                    .font(.system(size: 15, design: .monospaced))
                                    .foregroundColor(Color(hex: "389B85"))
                                    .padding()
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(Color.white.opacity(0.05))
                                    .cornerRadius(10)
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                fieldLabel("To (10-digit Account)")
                                OPayTextField(placeholder: "Receiver Account", text: $receiverAccount)
                                    .keyboardType(.numberPad)
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                fieldLabel("Amount (₹)")
                                OPayTextField(placeholder: "0.00", text: $amountRupees)
                                    .keyboardType(.decimalPad)
                            }
                        }
                        .padding(20)
                        .background(Color.white.opacity(0.05))
                        .cornerRadius(20)

                        // Payload size indicator
                        payloadEstimateView

                        OPayButton(title: isProcessing ? "Processing…" : "Send via SMS") {
                            Task { await sendTransfer() }
                        }
                        .disabled(isProcessing || !isFormValid)
                        .opacity(isFormValid ? 1 : 0.5)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Transfer")
            .alert(resultMessage ?? "", isPresented: $showResult) {
                Button("OK") {}
            }
        }
    }

    private var payloadEstimateView: some View {
        HStack(spacing: 8) {
            Image(systemName: "info.circle")
                .foregroundColor(Color(hex: "226E5D"))
            Text("Estimated SMS payload: ~157 chars (fits 160-char limit)")
                .font(.caption)
                .foregroundColor(Color.white.opacity(0.5))
        }
        .padding(.horizontal, 4)
    }

    private var isFormValid: Bool {
        receiverAccount.count == 10 && Double(amountRupees) != nil
    }

    private func sendTransfer() async {
        guard let amount = Double(amountRupees),
              let receiver = UInt64(receiverAccount),
              let sender = UInt64(appState.accountNumber) else { return }

        isProcessing = true
        defer { isProcessing = false }

        guard let vc = await UIApplication.shared.topViewController else { return }

        do {
            let sent = try await TransactionManager.shared.initiateTransfer(
                from: sender,
                to: receiver,
                amountPaise: UInt32(amount * 100),
                presentingVC: vc
            )
            
            if OfflineQueueManager.shared.isOnline {
                resultMessage = sent
                    ? "SMS sent! Transaction pending confirmation."
                    : "Cancelled. Transaction queued for retry."
            } else {
                resultMessage = "Queued offline. Will auto-send when signal returns."
            }
        } catch {
            resultMessage = "Error: \(error.localizedDescription)"
        }
        showResult = true
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(Color.white.opacity(0.4))
            .tracking(1.5)
            .textCase(.uppercase)
    }
}

// MARK: – History View

struct HistoryView: View {
    @ObservedObject private var queue = OfflineQueueManager.shared
    @State private var transactions: [[String: Any]] = []
    @State private var chainValid: Bool? = nil

    var body: some View {
        NavigationView {
            ZStack {
                backgroundGradient.ignoresSafeArea()

                List {
                    // Chain integrity banner
                    Section {
                        HStack {
                            Image(systemName: chainValid == true
                                  ? "checkmark.shield.fill" : "exclamationmark.shield.fill")
                                .foregroundColor(chainValid == true
                                                 ? Color(hex: "389B85") : Color(hex: "FF4757"))
                            VStack(alignment: .leading) {
                                Text(chainValid == true ? "Chain Integrity: Valid" : "Chain Integrity: Checking…")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(.white)
                                Text("Hash-linked audit trail")
                                    .font(.caption)
                                    .foregroundColor(Color.white.opacity(0.4))
                            }
                        }
                    }
                    .listRowBackground(Color.white.opacity(0.05))

                    // Transaction rows
                    Section("Transactions") {
                        ForEach(transactions.indices, id: \.self) { i in
                            txnRow(transactions[i])
                        }
                    }
                    .listRowBackground(Color.white.opacity(0.03))
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
                .refreshable { loadData() }
            }
            .navigationTitle("History")
            .onAppear { loadData() }
        }
    }

    private func txnRow(_ txn: [String: Any]) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(statusColor(txn["status"] as? String ?? ""))
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text("→ \(txn["receiverAccount"] as? String ?? "?")")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                Text(txn["chainHash"] as? String ?? "")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundColor(Color.white.opacity(0.3))
                    .lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("₹\(String(format: "%.2f", Double((txn["amountPaise"] as? Int64 ?? 0)) / 100))")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(Color(hex: "389B85"))
                Text((txn["status"] as? String ?? "").uppercased())
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundColor(statusColor(txn["status"] as? String ?? ""))
            }
        }
        .padding(.vertical, 4)
    }

    private func statusColor(_ status: String) -> Color {
        switch status {
        case "confirmed": return Color(hex: "389B85")
        case "dispatched": return Color(hex: "226E5D")
        case "pending": return Color(hex: "FFD700")
        default: return Color(hex: "FF4757")
        }
    }

    private func loadData() {
        transactions = (try? OfflineQueueManager.shared.fetchHistory()) ?? []
        Task {
            chainValid = try? await TransactionManager.shared.verifyQueueIntegrity()
        }
    }
}

// MARK: – Shared Background

private var backgroundGradient: some View {
    LinearGradient(
        colors: [Color(hex: "0A1128"), Color(hex: "1C2541")],
        startPoint: .top, endPoint: .bottom
    )
}
extension UIApplication {
    var topViewController: UIViewController? {
        var top = connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .filter({ $0.activationState == .foregroundActive })
            .first?.windows
            .filter({ $0.isKeyWindow }).first?.rootViewController
        
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
