package com.opay.controller;

import com.opay.service.TransactionValidationService;
import com.opay.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.opay.model.UserKey;
import com.opay.repository.UserKeyRepository;
import com.opay.repository.AccountRepository;
import com.opay.model.Account;
import com.opay.service.AndroidGatewayService;

import java.util.Map;

// ─────────────────────────────────────────────────────────────
// SMS WEBHOOK CONTROLLER
// Receives incoming SMS forwarded from the Android Wi-Fi Gateway.
//
// The Android SMS Gateway app posts to this endpoint whenever
// it receives an SMS on the local SIM:
//   POST /api/v1/sms/webhook
//   Content-Type: application/json
//   { "sender": "+919876543210", "message": "OPAY-TXN: <base85>" }
// ─────────────────────────────────────────────────────────────
@Slf4j
@RestController
@RequestMapping("/api/v1/sms")
@RequiredArgsConstructor
class SmsWebhookController {

    private final TransactionValidationService validationService;
    private final AuditService auditService;

    // DTO for incoming webhook payload
    record IncomingSmsPayload(
            @NotBlank String sender,
            @NotBlank String message
    ) {}

    /**
     * Incoming SMS webhook from Android gateway.
     * Routes to transaction processor or balance enquiry handler.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> handleIncomingSms(
            @Valid @RequestBody IncomingSmsPayload payload
    ) {
        log.info("[SMS Webhook] From={} MsgLen={}", payload.sender(), payload.message().length());

        String msg = payload.message().trim();

        if (msg.startsWith("OPAY-TXN:")) {
            // Encrypted transaction
            TransactionValidationService.ValidationResult result =
                    validationService.processTransaction(payload.sender(), msg);

            return ResponseEntity.ok(Map.of(
                    "status", result.success() ? "accepted" : "rejected",
                    "reason", result.failureReason() != null ? result.failureReason() : "none"
            ));

        } else if (msg.startsWith("OPAY-BAL:")) {
            // Balance enquiry
            validationService.processBalanceQuery(payload.sender(), msg);
            return ResponseEntity.ok(Map.of("status", "processed"));

        } else {
            log.warn("[SMS Webhook] Unrecognised prefix from {}: {}", payload.sender(),
                    msg.substring(0, Math.min(20, msg.length())));
            auditService.logFailure("SMS_UNKNOWN_PREFIX",
                    "Unrecognised SMS prefix", null, payload.sender(), msg);
            return ResponseEntity.badRequest().body(Map.of("status", "ignored", "reason", "unknown_prefix"));
        }
    }

    /**
     * Test endpoint for verifying gateway connectivity.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "online", "service", "OPay SMS Gateway"));
    }
}

// ─────────────────────────────────────────────────────────────
// USER REGISTRATION CONTROLLER
// Called by iOS app during onboarding to register the
// Secure Enclave public key.
// ─────────────────────────────────────────────────────────────
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
class UserRegistrationController {

    private final UserKeyRepository userKeyRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    record RegistrationRequest(
            @NotBlank String account,
            @NotBlank String publicKey  // base64 raw 64-byte P-256 pubkey
    ) {}

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegistrationRequest req
    ) {
        if (accountRepository.findByAccountNumber(req.account()).isEmpty()) {
            auditService.logFailure("KEY_REGISTER_FAILED",
                    "Account not found: " + req.account(), req.account(), null, null);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ACCOUNT_NOT_FOUND"));
        }

        // Revoke any previous key and register new one
        userKeyRepository.findByAccountNumberAndRevokedFalse(req.account())
                .ifPresent(k -> {
                    k.setRevoked(true);
                    userKeyRepository.save(k);
                    auditService.logSuccess("KEY_REVOKED",
                            "Previous key revoked for account " + req.account(), req.account(), null);
                });

        UserKey key = new UserKey(null, req.account(), req.publicKey(), null, false);
        userKeyRepository.save(key);

        auditService.logSuccess("KEY_REGISTERED",
                "New SE public key registered for account " + req.account(), req.account(), null);
        log.info("[OPay Register] Account {} registered SE public key", req.account());
        return ResponseEntity.ok(Map.of("status", "registered"));
    }
}

// ─────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────
// HEALTH + ADMIN CONTROLLER
// ─────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
class AdminController {

    private final AndroidGatewayService gatewayService;
    private final AccountRepository accountRepository;
    private final com.opay.repository.AuditLogRepository auditLogRepository;
    private final AuditService auditService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean gatewayUp = gatewayService.isGatewayReachable();
        auditService.logEvent("GATEWAY_HEALTH", "Gateway " + (gatewayUp ? "UP" : "DOWN"),
                null, null, null, gatewayUp);
        return ResponseEntity.ok(Map.of(
                "server", "UP",
                "androidGateway", gatewayUp ? "UP" : "DOWN",
                "timestamp", System.currentTimeMillis()
        ));
    }

    @GetMapping("/balance/{account}")
    public ResponseEntity<Map<String, Object>> balance(@PathVariable String account) {
        return accountRepository.findByAccountNumber(account)
                .map(a -> ResponseEntity.ok(Map.<String, Object>of(
                        "account", account,
                        "balancePaise", a.getBalancePaise(),
                        "balanceRupees", a.getBalancePaise() / 100.0
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Seed test accounts (dev only) */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, String>> seed() {
        if (accountRepository.count() > 0) {
            return ResponseEntity.ok(Map.of("status", "already_seeded"));
        }
        accountRepository.save(new Account(null, "1234567890", 100_000_00L, "Alice",
                "+911111111111", true, null, null));
        accountRepository.save(new Account(null, "0987654321", 50_000_00L, "Bob",
                "+912222222222", true, null, null));
        auditService.logSuccess("SEED_DB", "Seeded 2 test accounts", null, null);
        return ResponseEntity.ok(Map.of("status", "seeded"));
    }

    // ── NEW: Admin Dashboard API ──────────────────────────────

    @GetMapping("/accounts")
    public ResponseEntity<java.util.List<Account>> getAllAccounts() {
        return ResponseEntity.ok(accountRepository.findAll());
    }

    record CreateAccountRequest(
            @NotBlank String accountNumber,
            @NotBlank String name,
            @NotBlank String phoneNumber,
            Long balancePaise
    ) {}

    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(@Valid @RequestBody CreateAccountRequest req) {
        if (accountRepository.findByAccountNumber(req.accountNumber()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account already exists"));
        }
        Account acc = new Account(null, req.accountNumber(), req.balancePaise() != null ? req.balancePaise() : 0L,
                req.name(), req.phoneNumber(), true, null, null);
        accountRepository.save(acc);
        auditService.logSuccess("ADMIN_ACCOUNT_CREATED", "Admin created account " + req.accountNumber(), req.accountNumber(), null);
        return ResponseEntity.ok(acc);
    }

    record UpdateBalanceRequest(Long balancePaise) {}

    @PutMapping("/accounts/{account}/balance")
    public ResponseEntity<?> updateBalance(@PathVariable String account, @RequestBody UpdateBalanceRequest req) {
        return accountRepository.findByAccountNumber(account).map(acc -> {
            long oldBalance = acc.getBalancePaise();
            acc.setBalancePaise(req.balancePaise());
            accountRepository.save(acc);
            auditService.logSuccess("ADMIN_BALANCE_UPDATED",
                    "Balance changed from " + oldBalance + " to " + req.balancePaise(), account, null);
            return ResponseEntity.ok(acc);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/accounts/{account}")
    public ResponseEntity<?> deleteAccount(@PathVariable String account) {
        return accountRepository.findByAccountNumber(account).map(acc -> {
            accountRepository.delete(acc);
            auditService.logSuccess("ADMIN_ACCOUNT_DELETED", "Admin deleted account", account, null);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/audit")
    public ResponseEntity<?> getAuditLogs() {
        return ResponseEntity.ok(auditLogRepository.findTop100ByOrderByTimestampDesc());
    }
}
