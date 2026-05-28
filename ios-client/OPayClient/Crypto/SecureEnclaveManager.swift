import Foundation
import CryptoKit
import Security

/// Manages Secure Enclave key generation, persistence, and signing.
/// All private key material never leaves the Secure Enclave hardware boundary.
final class SecureEnclaveManager {

    static let shared = SecureEnclaveManager()
    private let keyTag = "com.opay.client.secp256r1.devicekey"

    private init() {}

    // MARK: – Key Lifecycle

    /// Returns the existing Secure Enclave private key or creates a new one.
    func getOrCreateDeviceKey() throws -> SecureEnclave.P256.Signing.PrivateKey {
        if let existing = try? loadKey() { return existing }
        return try createAndStoreKey()
    }

    private func createAndStoreKey() throws -> SecureEnclave.P256.Signing.PrivateKey {
        let accessControl = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            [.privateKeyUsage, .biometryCurrentSet],
            nil
        )!
        let key = try SecureEnclave.P256.Signing.PrivateKey(
            accessControl: accessControl
        )
        // Persist the data representation (SE reference blob, not raw key)
        try storeKeyBlob(key.dataRepresentation)
        return key
    }

    private func loadKey() throws -> SecureEnclave.P256.Signing.PrivateKey? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: keyTag,
            kSecReturnData: true
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: data)
    }

    private func storeKeyBlob(_ data: Data) throws {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: keyTag,
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        SecItemDelete(query as CFDictionary) // remove stale entry
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw OPayCryptoError.keychainStoreFailed(status)
        }
    }

    // MARK: – Signing

    /// Signs a payload hash using the Secure Enclave P-256 private key.
    /// Returns a DER-encoded ECDSA signature (max 72 bytes, typically 70-71).
    func sign(payloadHash: Data) throws -> Data {
        let privateKey = try getOrCreateDeviceKey()
        let signature = try privateKey.signature(for: payloadHash)
        return signature.derRepresentation
    }

    // MARK: – Public Key Export

    /// Returns the raw (uncompressed, 65-byte) public key for server registration.
    func exportPublicKeyBytes() throws -> Data {
        let key = try getOrCreateDeviceKey()
        return key.publicKey.rawRepresentation // 64 bytes (x||y, no 0x04 prefix)
    }

    /// Returns the compressed (33-byte) public key for on-chain / compact use.
    func exportCompressedPublicKey() throws -> Data {
        let raw = try exportPublicKeyBytes() // 64 bytes x||y
        let x = raw.prefix(32)
        let yLastByte = raw.last!
        let prefix: UInt8 = (yLastByte & 0x01 == 0) ? 0x02 : 0x03
        return Data([prefix]) + x // 33 bytes
    }
}

// MARK: – Verification Helper (for unit tests / server mirroring)
extension SecureEnclaveManager {
    static func verify(signature: Data, payloadHash: Data, publicKeyBytes: Data) throws -> Bool {
        let pubKey = try P256.Signing.PublicKey(rawRepresentation: publicKeyBytes)
        let sig = try P256.Signing.ECDSASignature(derRepresentation: signature)
        return pubKey.isValidSignature(sig, for: payloadHash)
    }
}

// MARK: – Errors
enum OPayCryptoError: LocalizedError {
    case keychainStoreFailed(OSStatus)
    case encryptionFailed
    case decryptionFailed
    case signatureInvalid
    case payloadTooLarge(Int)
    case replayAttack

    var errorDescription: String? {
        switch self {
        case .keychainStoreFailed(let s): return "Keychain store failed: \(s)"
        case .encryptionFailed: return "ECC encryption failed"
        case .decryptionFailed: return "ECC decryption failed"
        case .signatureInvalid: return "Secure Enclave signature invalid"
        case .payloadTooLarge(let n): return "SMS payload \(n) chars exceeds 160-char limit"
        case .replayAttack: return "Replay attack detected – timestamp expired"
        }
    }
}
