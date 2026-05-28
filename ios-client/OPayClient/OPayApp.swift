import SwiftUI
import Combine
import LocalAuthentication

@main
struct OPayApp: App {
    @StateObject private var appState = AppState()

    init() {
        // Configure the ECCPayloadEngine with the server's static P-256 public key.
        let pubKeyB64 = "WCramjF6BGxEArKKXLiFuW3ZfInmlGdTVZNG3GCZagEFrGIw/F8dDaaibOhlobQSmDQK7PkVNbF2563mVokCjw=="
        if let data = Data(base64Encoded: pubKeyB64) {
            try? ECCPayloadEngine.shared.configure(serverPublicKeyData: data)
        }
    }

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase) { phase in
                    if phase == .background {
                        // Automatically lock the app when it goes to the background
                        appState.isUnlocked = false
                    }
                }
                .onOpenURL { url in
                    // Intercept Server SMS Reply: opay://bal?d=<base85>
                    guard url.scheme == "opay", url.host == "bal" else { return }
                    if let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                       let dataItem = components.queryItems?.first(where: { $0.name == "d" }),
                       let b85Data = dataItem.value {
                        
                        Task {
                            do {
                                let frame = try ECCPayloadEngine.shared.decryptSMSResponse(base85String: b85Data)
                                DispatchQueue.main.async {
                                    self.appState.balancePaise = Int64(frame.amountPaise)
                                }
                                print("[OPayApp] Successfully decrypted SMS balance response!")
                            } catch {
                                print("[OPayApp] Failed to decrypt deep link balance response: \(error)")
                            }
                        }
                    }
                }
        }
    }
}

// MARK: – AppState

@MainActor
final class AppState: ObservableObject {
    @Published var isOnboarded: Bool
    @Published var isUnlocked: Bool = false
    @Published var accountNumber: String
    @Published var balancePaise: Int64 = 0
    @Published var isLoadingBalance: Bool = false

    init() {
        self.isOnboarded = UserDefaults.standard.bool(forKey: "opay.onboarded")
        self.accountNumber = UserDefaults.standard.string(forKey: "opay.account") ?? ""
        // If they aren't onboarded, leave it unlocked so they can do onboarding
        self.isUnlocked = !self.isOnboarded
    }

    func completeOnboarding(account: String) {
        accountNumber = account
        UserDefaults.standard.set(account, forKey: "opay.account")
        UserDefaults.standard.set(true, forKey: "opay.onboarded")
        isOnboarded = true
    }
}

// MARK: – Root Router

struct RootView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        if appState.isOnboarded {
            if appState.isUnlocked {
                MainTabView()
            } else {
                LockView()
            }
        } else {
            OnboardingView()
        }
    }
}

// MARK: – Lock View

struct LockView: View {
    @EnvironmentObject var appState: AppState
    @State private var authError: String? = nil

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(hex: "0D0D0D"), Color(hex: "1A1A2E")],
                startPoint: .topLeading, endPoint: .bottomTrailing
            ).ignoresSafeArea()

            VStack(spacing: 30) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 80))
                    .foregroundStyle(
                        LinearGradient(colors: [Color(hex: "00D4AA"), Color(hex: "00A8FF")],
                                       startPoint: .top, endPoint: .bottom)
                    )
                
                Text("OPay is Locked")
                    .font(.title.bold())
                    .foregroundColor(.white)
                
                if let err = authError {
                    Text(err)
                        .font(.caption)
                        .foregroundColor(Color(hex: "FF4757"))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Button(action: authenticate) {
                    Text("Unlock with Face ID / PIN")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(
                            LinearGradient(colors: [Color(hex: "00D4AA"), Color(hex: "00A8FF")],
                                           startPoint: .leading, endPoint: .trailing)
                        )
                        .cornerRadius(14)
                }
                .padding(.horizontal, 40)
            }
        }
        .onAppear {
            authenticate()
        }
    }

    private func authenticate() {
        let context = LAContext()
        var error: NSError?

        // Check if device supports authentication (Biometrics or Passcode)
        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
            let reason = "Unlock OPay to access your account."
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, authenticationError in
                DispatchQueue.main.async {
                    if success {
                        self.appState.isUnlocked = true
                    } else {
                        self.authError = "Authentication failed. Please try again."
                    }
                }
            }
        } else {
            // No passcode/biometrics set on the device
            DispatchQueue.main.async {
                self.authError = "No device passcode set. Please set a passcode in your iPhone settings to secure OPay."
            }
        }
    }
}
