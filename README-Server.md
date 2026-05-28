# OPay – Offline SMS-based UPI Payment System

## Architecture Overview

```
iPhone (iOS Client)
  └── SecureEnclaveManager  ← P-256 key in hardware Secure Enclave
  └── ECCPayloadEngine      ← ECDH + AES-256-GCM + zlib + Base85
  └── OfflineQueueManager   ← CoreData hash-chain "Promise" queue
  └── SMSGatewayClient      ← MFMessageComposeViewController / LAN HTTP
         │
         │  SMS (≤160 chars: "OPAY-TXN: <base85>")
         ▼
Android Phone (Wi-Fi SMS Gateway)
  └── Receives SMS over cellular
  └── POST /api/v1/sms/webhook → Java server over local Wi-Fi
  └── Receives HTTP POST → sends reply SMS to customer
         │
         │  HTTP/JSON on LAN (Wi-Fi)
         ▼
Java Spring Boot Server (macOS/Windows/Linux)
  └── SmsWebhookController  ← receives from Android gateway
  └── CryptoService         ← ECDH decrypt + ECDSA SE signature verify
  └── TransactionValidationService ← replay check, balance, commit
  └── AndroidGatewayService ← sends reply SMS via Android phone
  └── H2 Database           ← accounts, transactions, user_keys
```

## Payload Wire Format (24 bytes plaintext)

```
Offset  Size  Field
──────  ────  ──────────────────────────────────────────────────
  0      5    Sender account (BCD packed, 10 digits)
  5      5    Receiver account (BCD packed, 10 digits)
 10      4    Timestamp (UInt32 BE, Unix seconds)
 14      6    Transaction ID (48-bit random)
 20      4    Amount in paise (UInt32 BE, max ₹42,94,967)
──────  ────  ──────────────────────────────────────────────────
Total: 24 bytes

After AES-256-GCM (nonce 12 + ct 24 + tag 16): 52 bytes
+ compressed ephemeral P-256 pubkey: +33 bytes = 85 bytes
+ Secure Enclave P1363 ECDSA sig:   +64 bytes = 149 bytes
After zlib deflate (~0.85 ratio):   ~127 bytes
After Base85 encoding (×1.25):      ~159 chars
+ "OPAY-TXN: " prefix:              +10 chars = ~157 chars ✓
```

## SMS Character Budget

| Component              | Chars |
|------------------------|-------|
| Prefix "OPAY-TXN: "   | 10    |
| Base85(zlib(binary))   | ~147  |
| **Total**              | **~157** |
| GSM SMS limit          | 160   |
| **Headroom**           | **~3 chars** |

## Quick Start

### 1. Generate Server Keypair
```bash
cd java-server
mvn compile exec:java -Dexec.mainClass=com.opay.config.KeyGenTool
# Copy output into src/main/resources/application.properties
```

### 2. Start the Server
```bash
mvn spring-boot:run
# Server starts on http://localhost:8080
# H2 console: http://localhost:8080/h2-console
# Seed test data: POST http://localhost:8080/api/v1/admin/seed
```

### 3. Configure Android Gateway
Install [SMS Gateway for Android](https://github.com/capcom6/android-sms-gateway) on your
Android phone. Configure it to:
- Forward incoming SMS to: `http://YOUR_SERVER_IP:8080/api/v1/sms/webhook`
- Expose outgoing SMS API on port `8088`

Update `application.properties`:
```properties
opay.gateway.android.host=YOUR_ANDROID_PHONE_IP
opay.gateway.android.port=8088
```

### 4. Configure iOS App
In `Info.plist` (or Xcode build settings):
```
OPAY_GATEWAY_PHONE = +91XXXXXXXXXX   # Android phone's SIM number
OPAY_SERVER_URL    = http://192.168.1.100:8080
```

In `ECCPayloadEngine.swift`, call during app launch:
```swift
try ECCPayloadEngine.shared.configure(serverPublicKeyData: <base64 raw pubkey from KeyGenTool>)
```

### 5. Open iOS Project
```
open ios-client/OPayClient.xcodeproj
```
Build and run on physical device (Secure Enclave requires real hardware).

## Security Properties

| Property               | Implementation |
|------------------------|----------------|
| Forward Secrecy        | Ephemeral ECDH key per transaction; HKDF epoch rotates hourly |
| Device Binding         | Secure Enclave P-256 key (hardware, non-exportable) |
| Replay Prevention      | 300-second timestamp window + TxnID deduplication ledger |
| Tamper-proof Queue     | SHA-256 hash-chain in CoreData (append-only) |
| Carrier Spam Bypass    | "OPAY-TXN: " prefix pattern |
| Payload Size           | ≤157 chars (single SMS, no fragmentation) |

## Production Hardening (TODO)

- [ ] Replace H2 with PostgreSQL
- [ ] Add mTLS between Android gateway and server
- [ ] Server-side key rotation with graceful epoch overlap
- [ ] iOS: Biometric re-authentication for high-value transactions
- [ ] HSM for server private key storage
- [ ] Rate limiting on webhook endpoint
- [ ] Certificate pinning for server public key in iOS app
