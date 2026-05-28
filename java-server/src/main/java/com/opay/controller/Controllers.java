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
    private final AndroidGatewayService gatewayService;

    // DTO for incoming webhook payload
    record SmsGatePayload(
            @NotBlank String sender,
            @NotBlank String message
    ) {}

    record IncomingSmsPayload(
            SmsGatePayload payload
    ) {}

    /**
     * Incoming SMS webhook from Android gateway.
     * Routes to transaction processor or balance enquiry handler.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> handleIncomingSms(
            @Valid @RequestBody IncomingSmsPayload payload
    ) {
        String sender = payload.payload() != null ? payload.payload().sender() : "";
        String msg = payload.payload() != null ? payload.payload().message().trim() : "";
        return processSms(sender, msg);
    }

    /**
     * Incoming SMS webhook from SMSSync (Ushahidi).
     * SMSSync sends URL-encoded form data with 'from' and 'message'.
     */
    @RequestMapping(value = "/smssync", method = {org.springframework.web.bind.annotation.RequestMethod.POST, org.springframework.web.bind.annotation.RequestMethod.GET})
    public ResponseEntity<Map<String, String>> handleSmsSync(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "message", required = false) String message
    ) {
        return processSms(from != null ? from : "", message != null ? message.trim() : "");
    }

    private ResponseEntity<Map<String, String>> processSms(String sender, String msg) {
        log.info("[SMS Webhook] From={} MsgLen={}", sender, msg.length());

        // Normalise iOS autocorrect: OPAL -> OPAY
        String normalised = msg;
        if (normalised.startsWith("OPAL-")) {
            normalised = "OPAY-" + normalised.substring(5);
            log.info("[SMS Webhook] Autocorrect fix: OPAL->OPAY | normalised={}", normalised);
        }

        if (normalised.startsWith("OPAY-TXN:")) {
            // Transaction
            TransactionValidationService.ValidationResult result =
                    validationService.processTransaction(sender, normalised);

            return ResponseEntity.ok(Map.of(
                    "status", result.isSuccess() ? "accepted" : "rejected",
                    "reason", result.failureReason() != null ? result.failureReason() : "none"
            ));

        } else if (normalised.startsWith("OPAY-BAL:") || normalised.startsWith("OPAY-ENQ:")) {
            // Balance enquiry
            validationService.processBalanceQuery(sender, normalised);
            return ResponseEntity.ok(Map.of("payload", Map.of("success", "true", "error", "null").toString(), "status", "processed"));

        } else {
            log.warn("[SMS Webhook] Unrecognised prefix from {}: {}", sender,
                    msg.substring(0, Math.min(20, msg.length())));
            auditService.logFailure("SMS_UNKNOWN_PREFIX",
                    "Unrecognised SMS prefix", null, sender, msg);
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

    /**
     * Endpoint for Termux script to poll for outgoing SMS messages.
     */
    @GetMapping("/outgoing")
    public ResponseEntity<Map<String, String>> pollOutgoing() {
        Map<String, String> msg = gatewayService.popOutgoingSms();
        if (msg != null) {
            return ResponseEntity.ok(msg);
        }
        return ResponseEntity.noContent().build();
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
            // Auto-create a new account for demonstration purposes
            Account newAccount = new Account(null, req.account(), 10_000_00L, "New User", 
                    "+910000000000", true, null, null);
            accountRepository.save(newAccount);
            log.info("[OPay Register] Auto-created new account {} with ₹10,000 balance", req.account());
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
