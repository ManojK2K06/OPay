import SwiftUI

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

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .preferredColorScheme(.dark)
        }
    }
}

// MARK: – AppState

final class AppState: ObservableObject {
    @Published var isOnboarded: Bool = UserDefaults.standard.bool(forKey: "opay.onboarded")
    @Published var accountNumber: String = UserDefaults.standard.string(forKey: "opay.account") ?? ""
    @Published var balancePaise: Int64 = 0
    @Published var isLoadingBalance: Bool = false

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
            MainTabView()
        } else {
            OnboardingView()
        }
    }
}
