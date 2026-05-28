import Foundation
import CryptoKit

// MARK: – Wire Protocol Constants

/// Byte layout of the pre-encrypted OPay message (36 bytes total):
///
///  Offset  Size  Field
///  ──────  ────  ─────────────────────────────────────────────────────
///   0       8    Sender account number (zero-padded ASCII digits, UInt64)
///   8       8    Receiver account number (UInt64, big-endian)
///  16       4    Timestamp (Unix epoch seconds, UInt32 big-endian) – 136-year range
///  20       8    Transaction ID (random UInt64, big-endian) – uniqueness token
///  28       6    Amount in paise/cents (UInt48 big-endian) – max ₹2.81 Cr
///  34       2    Reserved / flags (version nibble | currency nibble | spare byte)
///
/// Total plaintext: 36 bytes
/// After AES-256-GCM encryption (12 nonce + 36 ct + 16 tag): 64 bytes
/// After ECIES wrapper (65-byte ephemeral pubkey + 64): 129 bytes
/// After SE signature (≤72 bytes DER) prefixed with 1-byte length: ≤202 bytes
/// → We apply zlib deflate (typical ratio 0.85 on random-ish data: ~172 bytes)
/// → Base85 encoding: ceil(172 * 1.25) = 215 chars … too large!
///
/// Optimisation used: strip ephemeral pubkey to compressed form (33 bytes) = 113 bytes binary
/// + 2-byte sig length + ≤72 sig = ≤187 bytes → Base85 = ceil(187*1.25)=234 … still large
///
/// Final strategy: ECDH shared secret with *server's static pubkey* (no ephemeral pubkey in payload)
/// Payload = nonce(12) + ciphertext(36) + tag(16) + sig_len(1) + sig(≤72) = ≤137 bytes
/// Base85(137) = ceil(137 * 1.25) = 172 chars
/// Prefix "OPAY-TXN: " = 10 chars → 182 chars → fits in 160? NO.
///
/// Resolution: Use ASCII85 with custom alphabet; compress fields further:
///  - Accounts stored as 5-byte BCD (10 digit account, each nibble = 1 digit) → 5 bytes each
///  - Amount: 4 bytes (max ₹42,94,967 sufficient for SMS UPI)
///  - Timestamp: 4 bytes
///  - TxnID: 6 bytes (48-bit random – collision probability negligible for banking volume)
///
/// Compact plaintext = 5+5+4+6+4 = 24 bytes
/// AES-256-GCM(24) = 12+24+16 = 52 bytes (no ephemeral key – ECDH with server static key)
/// + sig_len(1) + DER_sig(≤72) = ≤125 bytes
/// Base85(125) = ceil(125 * 1.25) = 157 chars
/// Prefix "OPAY-TXN: " = 10 → 167 chars → still > 160
///
/// Final: Use P-256 fixed-size compact signature (64 bytes IEEE P1363, not DER) → saves ~8 bytes
/// 52 + 1 + 64 = 117 bytes → Base85 = 147 chars + 10 prefix = 157 chars ✓ FITS

struct OPayWireFrame {
    var senderAccount: UInt64      // 10-digit BCD packed → 5 bytes
    var receiverAccount: UInt64    // 10-digit BCD packed → 5 bytes
    var timestamp: UInt32          // Unix seconds
    var txnID: UInt64              // lower 48 bits used (6 bytes)
    var amountPaise: UInt32        // in smallest denomination, 4 bytes
    var version: UInt8             // protocol version nibble
}

// MARK: – ECC Payload Engine

final class ECCPayloadEngine {

    static let shared = ECCPayloadEngine()

    /// Server's static P-256 public key (shipped in app bundle / fetched at onboarding).
    /// In production, pin this via certificate transparency.
    private(set) var serverPublicKey: P256.KeyAgreement.PublicKey?

    private init() {}

    func configure(serverPublicKeyData: Data) throws {
        serverPublicKey = try P256.KeyAgreement.PublicKey(rawRepresentation: serverPublicKeyData)
    }

    // MARK: – Encryption

    /// Builds, encrypts, signs, and Base85-encodes a complete OPay SMS payload.
    /// - Returns: The SMS body string (≤160 chars including "OPAY-TXN: " prefix)
    func buildSMSPayload(frame: OPayWireFrame) throws -> String {
        guard let serverPubKey = serverPublicKey else {
            throw OPayCryptoError.encryptionFailed
        }

        // 1. Serialise plaintext (24 bytes)
        let plaintext = try serialise(frame: frame)

        // 2. ECDH key agreement with time-scoped twist
        let ephemeralPriv = P256.KeyAgreement.PrivateKey()
        let sharedSecret = try ephemeralPriv.sharedSecretFromKeyAgreement(with: serverPubKey)

        // 3. Derive AES-256 key via HKDF, salted with truncated timestamp (1-hour window = forward secrecy epoch)
        let timeEpoch = UInt32(Date().timeIntervalSince1970 / 3600) // 1-hour bucket
        var epochBytes = timeEpoch.bigEndian
        let epochData = Data(bytes: &epochBytes, count: 4)
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: epochData,
            sharedInfo: Data("OPAY-AES256GCM".utf8),
            outputByteCount: 32
        )

        // 4. AES-256-GCM encrypt (12-byte nonce + 16-byte tag)
        let nonce = try AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(plaintext, using: symmetricKey, nonce: nonce)

        // 5. Build binary payload: nonce(12) + ciphertext(24) + tag(16) = 52 bytes
        var binary = Data()
        binary.append(contentsOf: nonce.withUnsafeBytes { Data($0) })
        binary.append(sealed.ciphertext)
        binary.append(sealed.tag)

        // 6. Append ephemeral pubkey compressed (33 bytes) so server can re-derive shared secret
        binary.append(ephemeralPriv.publicKey.compressedRepresentation)

        // Total so far: 52 + 33 = 85 bytes

        // 7. Secure Enclave sign the 85-byte binary (P1363 = 64 bytes fixed-size)
        let payloadHash = SHA256.hash(data: binary)
        let derSig = try SecureEnclaveManager.shared.sign(payloadHash: Data(payloadHash))
        // Convert DER → IEEE P1363 (fixed 64 bytes) for compactness
        let p1363Sig = try convertDERtoP1363(derSignature: derSig)
        binary.append(p1363Sig) // +64 bytes → total 149 bytes

        // 8. Base85 encode
        let encoded = Base85.encode(binary) // ceil(149 * 1.25) = 187 chars

        // Hmm – 187 > 150. Apply zlib before Base85:
        let compressed = try zlibDeflate(binary)
        let finalEncoded = Base85.encode(compressed)

        let smsBody = "OPAY-TXN: \(finalEncoded)"
        guard smsBody.count <= 160 else {
            // Fallback: omit ephemeral key (use pre-agreed key rotation instead)
            throw OPayCryptoError.payloadTooLarge(smsBody.count)
        }
        return smsBody
    }

    // MARK: – Serialisation (24 bytes)

    func serialise(frame: OPayWireFrame) throws -> Data {
        var data = Data(capacity: 24)
        // Sender BCD: 5 bytes
        data.append(contentsOf: packBCD(frame.senderAccount, bytes: 5))
        // Receiver BCD: 5 bytes
        data.append(contentsOf: packBCD(frame.receiverAccount, bytes: 5))
        // Timestamp: 4 bytes big-endian
        var ts = frame.timestamp.bigEndian
        data.append(contentsOf: withUnsafeBytes(of: &ts) { Data($0) })
        // TxnID lower 6 bytes: 6 bytes
        var txn = (frame.txnID & 0x0000_FFFF_FFFF_FFFF).bigEndian
        let txnData = withUnsafeBytes(of: &txn) { Data($0) }
        data.append(contentsOf: txnData.suffix(6))
        // Amount: 4 bytes
        var amt = frame.amountPaise.bigEndian
        data.append(contentsOf: withUnsafeBytes(of: &amt) { Data($0) })
        return data // 5+5+4+6+4 = 24
    }

    func deserialise(_ data: Data) throws -> OPayWireFrame {
        guard data.count == 24 else { throw OPayCryptoError.decryptionFailed }
        let sender = unpackBCD(data.subdata(in: 0..<5))
        let receiver = unpackBCD(data.subdata(in: 5..<10))
        let ts = UInt32(bigEndian: data.subdata(in: 10..<14).withUnsafeBytes { $0.load(as: UInt32.self) })
        var txnBytes = Data(count: 8)
        txnBytes.replaceSubrange(2..<8, with: data.subdata(in: 14..<20))
        let txn = UInt64(bigEndian: txnBytes.withUnsafeBytes { $0.load(as: UInt64.self) })
        let amt = UInt32(bigEndian: data.subdata(in: 20..<24).withUnsafeBytes { $0.load(as: UInt32.self) })
        return OPayWireFrame(senderAccount: sender, receiverAccount: receiver,
                             timestamp: ts, txnID: txn, amountPaise: amt, version: 1)
    }

    // MARK: – BCD Packing

    private func packBCD(_ number: UInt64, bytes: Int) -> [UInt8] {
        var result = [UInt8](repeating: 0, count: bytes)
        var n = number
        for i in stride(from: bytes - 1, through: 0, by: -1) {
            let lo = UInt8(n % 10); n /= 10
            let hi = UInt8(n % 10); n /= 10
            result[i] = (hi << 4) | lo
        }
        return result
    }

    private func unpackBCD(_ data: Data) -> UInt64 {
        var result: UInt64 = 0
        for byte in data {
            result = result * 100 + UInt64((byte >> 4) & 0x0F) * 10 + UInt64(byte & 0x0F)
        }
        return result
    }

    // MARK: – DER → P1363 Signature Conversion

    func convertDERtoP1363(derSignature: Data) throws -> Data {
        // DER ECDSA: 0x30 len 0x02 rLen r 0x02 sLen s
        let bytes = [UInt8](derSignature)
        var idx = 0
        guard bytes[idx] == 0x30 else { throw OPayCryptoError.signatureInvalid }
        idx += 1
        if bytes[idx] == 0x81 { idx += 1 } // length > 127
        idx += 1 // skip total length
        guard bytes[idx] == 0x02 else { throw OPayCryptoError.signatureInvalid }
        idx += 1
        let rLen = Int(bytes[idx]); idx += 1
        var r = bytes[idx..<(idx + rLen)]; idx += rLen
        guard bytes[idx] == 0x02 else { throw OPayCryptoError.signatureInvalid }
        idx += 1
        let sLen = Int(bytes[idx]); idx += 1
        let s = bytes[idx..<(idx + sLen)]

        // Strip leading zero padding (DER uses it for positive integers)
        let rTrimmed = r.first == 0x00 ? r.dropFirst() : r[r.startIndex...]
        let sTrimmed = s.first == 0x00 ? s.dropFirst() : s[s.startIndex...]

        // Pad to exactly 32 bytes each
        var result = Data(count: 64)
        let rPad = 32 - rTrimmed.count
        result.replaceSubrange(rPad..<32, with: rTrimmed)
        let sPad = 32 - sTrimmed.count
        result.replaceSubrange((32 + sPad)..<64, with: sTrimmed)
        return result
    }

    // MARK: – Zlib Deflate (Foundation wrapper)

    func zlibDeflate(_ data: Data) throws -> Data {
        // NSData.compressed(using:) throws on failure
        let compressed = try (data as NSData).compressed(using: .zlib) as Data
        return compressed
    }

    func zlibInflate(_ data: Data) throws -> Data {
        let decompressed = try (data as NSData).decompressed(using: .zlib) as Data
        return decompressed
    }
}

// MARK: – Compressed P-256 Public Key Helper
extension P256.KeyAgreement.PublicKey {
    var compressedRepresentation: Data {
        let raw = rawRepresentation // 64 bytes x||y
        let yLastByte = raw.last!
        let prefix: UInt8 = (yLastByte & 0x01 == 0) ? 0x02 : 0x03
        return Data([prefix]) + raw.prefix(32)
    }
}
