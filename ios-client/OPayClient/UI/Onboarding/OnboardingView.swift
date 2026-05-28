import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject var appState: AppState
    @State private var accountInput: String = ""
    @State private var isRegistering: Bool = false
    @State private var errorMessage: String?
    @State private var step: OnboardingStep = .welcome

    enum OnboardingStep { case welcome, enterAccount, registering, done }

    var body: some View {
        ZStack {
            // Background gradient
            LinearGradient(
                colors: [Color(hex: "0D0D0D"), Color(hex: "1A1A2E"), Color(hex: "16213E")],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                // Logo
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(LinearGradient(
                                colors: [Color(hex: "00D4AA"), Color(hex: "00A8FF")],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            ))
                            .frame(width: 80, height: 80)
                        Text("₹")
                            .font(.system(size: 40, weight: .bold))
                            .foregroundColor(.white)
                    }
                    Text("OPay")
                        .font(.system(size: 42, weight: .heavy))
                        .foregroundStyle(
                            LinearGradient(colors: [Color(hex: "00D4AA"), Color(hex: "00A8FF")],
                                           startPoint: .leading, endPoint: .trailing)
                        )
                    Text("Offline-First Payments")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(Color.white.opacity(0.5))
                        .tracking(3)
                        .textCase(.uppercase)
                }
                .padding(.bottom, 60)

                switch step {
                case .welcome:
                    welcomeContent
                case .enterAccount:
                    accountEntryContent
                case .registering:
                    registeringContent
                case .done:
                    doneContent
                }

                Spacer()
                Spacer()
            }
            .padding(.horizontal, 32)
        }
        .animation(.spring(response: 0.5, dampingFraction: 0.8), value: step)
    }

    // MARK: – Step Views

    private var welcomeContent: some View {
        VStack(spacing: 24) {
            featureRow(icon: "lock.shield.fill", title: "Secure Enclave Protected",
                       subtitle: "Your keys never leave the hardware")
            featureRow(icon: "wifi.slash", title: "Works Offline",
                       subtitle: "Queue transactions without signal")
            featureRow(icon: "message.fill", title: "SMS-Powered",
                       subtitle: "Bank-grade payments over simple SMS")

            OPayButton(title: "Get Started", action: { step = .enterAccount })
                .padding(.top, 16)
        }
    }

    private var accountEntryContent: some View {
        VStack(spacing: 20) {
            Text("Enter your 10-digit account number")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(Color.white.opacity(0.7))
                .multilineTextAlignment(.center)

            OPayTextField(placeholder: "Account Number", text: $accountInput)
                .keyboardType(.numberPad)

            if let error = errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundColor(Color(hex: "FF4757"))
            }

            OPayButton(title: "Register Device") {
                Task { await registerDevice() }
            }
            .disabled(accountInput.count < 10)
        }
    }

    private var registeringContent: some View {
        VStack(spacing: 20) {
            ProgressView()
                .tint(Color(hex: "00D4AA"))
                .scaleEffect(1.5)
            Text("Generating Secure Enclave keypair\nand registering with server…")
                .font(.system(size: 15))
                .foregroundColor(Color.white.opacity(0.7))
                .multilineTextAlignment(.center)
        }
    }

    private var doneContent: some View {
        VStack(spacing: 20) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 60))
                .foregroundColor(Color(hex: "00D4AA"))
            Text("Device Registered!")
                .font(.title2.bold())
                .foregroundColor(.white)
            OPayButton(title: "Start Banking") {
                appState.completeOnboarding(account: accountInput)
            }
        }
    }

    // MARK: – Registration

    private func registerDevice() async {
        guard accountInput.count == 10 else {
            errorMessage = "Account number must be exactly 10 digits"
            return
        }
        step = .registering
        do {
            let pubKeyBytes = try SecureEnclaveManager.shared.exportPublicKeyBytes()
            let pubKeyB64 = pubKeyBytes.base64EncodedString()
            // POST to server: {"account": "...", "publicKey": "..."}
            guard let url = URL(string: "\(ConfigManager.shared.serverBaseURL)/api/v1/users/register") else {
                throw OPayCryptoError.encryptionFailed
            }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(["account": accountInput, "publicKey": pubKeyB64])

            _ = try await URLSession.shared.data(for: req)
            step = .done
        } catch {
            step = .enterAccount
            errorMessage = error.localizedDescription
        }
    }

    // MARK: – Helpers

    private func featureRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(Color(hex: "00D4AA"))
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                Text(subtitle).font(.caption).foregroundColor(Color.white.opacity(0.5))
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

// MARK: – Shared UI Components

struct OPayButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.black)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(
                    LinearGradient(
                        colors: [Color(hex: "00D4AA"), Color(hex: "00A8FF")],
                        startPoint: .leading, endPoint: .trailing
                    )
                )
                .cornerRadius(14)
        }
    }
}

struct OPayTextField: View {
    let placeholder: String
    @Binding var text: String

    var body: some View {
        TextField(placeholder, text: $text)
            .font(.system(size: 16, weight: .medium, design: .monospaced))
            .foregroundColor(.white)
            .padding()
            .background(Color.white.opacity(0.08))
            .cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.15)))
    }
}

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: .alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let r = Double((int >> 16) & 0xFF) / 255
        let g = Double((int >> 8) & 0xFF) / 255
        let b = Double(int & 0xFF) / 255
        self.init(red: r, green: g, blue: b)
    }
}
