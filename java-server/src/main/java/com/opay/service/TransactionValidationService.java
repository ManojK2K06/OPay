package com.opay.service;

import com.opay.crypto.CryptoService;
import com.opay.crypto.OPayWireFrame;
import com.opay.model.*;
import com.opay.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionValidationService {

    private final CryptoService cryptoService;
    private final AccountRepository accountRepository;
    private final UserKeyRepository userKeyRepository;
    private final TransactionRepository transactionRepository;
    private final ProcessedTxnIdRepository processedTxnIdRepository;
    private final AndroidGatewayService gatewayService;
    private final AuditService auditService;

    // ── Process Incoming SMS ─────────────────────────────────

    /**
     * Full validation pipeline for an incoming encrypted SMS transaction.
     * Called by SmsWebhookController.
     *
     * Steps:
     *  1. Look up sender public key
     *  2. Decrypt + verify Secure Enclave signature
     *  3. Replay attack check (duplicate TxnID)
     *  4. Timestamp freshness check (handled inside CryptoService)
     *  5. Account existence check
     *  6. Sufficient balance check
     *  7. Atomic debit/credit + persist transaction
     *  8. Send reply SMS
     */
    @Transactional
    public ValidationResult processTransaction(String senderPhone, String smsBody) {
        log.info("[OPay] Processing SMS from {} | body length={}", senderPhone, smsBody.length());

        // Audit: log incoming SMS
        auditService.logEvent("SMS_RECEIVED", "Incoming transaction SMS",
                null, senderPhone, smsBody, true);

        // 1. Find sender account by phone (efficient indexed query)
        Account senderAccount = accountRepository
                .findByPhoneNumber(senderPhone)
                .orElse(null);

        if (senderAccount == null) {
            log.warn("[OPay] Unknown sender phone: {}", senderPhone);
            auditService.logFailure("TXN_FAILED_UNKNOWN_SENDER",
                    "No account registered for phone", null, senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: ERROR UNKNOWN_SENDER");
            return ValidationResult.fail("UNKNOWN_SENDER");
        }

        // 2. Get Secure Enclave public key
        UserKey userKey = userKeyRepository
                .findByAccountNumberAndRevokedFalse(senderAccount.getAccountNumber())
                .orElse(null);

        if (userKey == null) {
            auditService.logFailure("TXN_FAILED_NO_KEY",
                    "No active public key registered", senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: ERROR NO_KEY_REGISTERED");
            return ValidationResult.fail("NO_KEY_REGISTERED");
        }

        // 3. Decrypt + verify signature
        CryptoService.DecryptionResult decrypted;
        try {
            decrypted = cryptoService.decrypt(smsBody, userKey.getPublicKeyBase64());
        } catch (SecurityException e) {
            log.error("[OPay] Security violation: {}", e.getMessage());
            persistFailed(senderAccount.getAccountNumber(), null, TxnStatus.FAILED_INVALID_SIGNATURE,
                    e.getMessage(), smsBody, senderPhone);
            auditService.logFailure("TXN_FAILED_SIGNATURE",
                    e.getMessage(), senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: REJECTED " + e.getMessage());
            return ValidationResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[OPay] Decryption error: {}", e.getMessage());
            persistFailed(senderAccount.getAccountNumber(), null, TxnStatus.FAILED_DECRYPTION,
                    e.getMessage(), smsBody, senderPhone);
            auditService.logFailure("TXN_FAILED_DECRYPTION",
                    e.getMessage(), senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: ERROR DECRYPT_FAIL");
            return ValidationResult.fail("DECRYPT_FAIL");
        }

        OPayWireFrame frame = decrypted.frame();

        // 4. Replay check – idempotent TxnID
        if (processedTxnIdRepository.existsByTxnId(frame.txnId())) {
            log.warn("[OPay] Duplicate TxnID: {}", frame.txnId());
            auditService.logFailure("TXN_FAILED_REPLAY",
                    "Duplicate TxnID: " + frame.txnId(), senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: REJECTED REPLAY");
            return ValidationResult.fail("REPLAY");
        }

        // Validate sender account number matches registered phone
        if (frame.senderAccount() != Long.parseLong(senderAccount.getAccountNumber())) {
            log.error("[OPay] Account mismatch: payload={} registered={}",
                    frame.senderAccount(), senderAccount.getAccountNumber());
            auditService.logFailure("TXN_FAILED_ACCOUNT_MISMATCH",
                    "Payload sender " + frame.senderAccount() + " != registered " + senderAccount.getAccountNumber(),
                    senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: REJECTED ACCOUNT_MISMATCH");
            return ValidationResult.fail("ACCOUNT_MISMATCH");
        }

        // 5. Look up receiver
        Account receiverAccount = accountRepository
                .findByAccountNumber(String.valueOf(frame.receiverAccount()))
                .orElse(null);

        if (receiverAccount == null) {
            persistFailed(senderAccount.getAccountNumber(), frame, TxnStatus.FAILED_ACCOUNT_NOT_FOUND,
                    "Receiver not found", smsBody, senderPhone);
            auditService.logFailure("TXN_FAILED_RECEIVER_NOT_FOUND",
                    "Receiver account " + frame.receiverAccount() + " not found",
                    senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: FAILED RECEIVER_NOT_FOUND");
            return ValidationResult.fail("RECEIVER_NOT_FOUND");
        }

        // 6. Balance check
        if (senderAccount.getBalancePaise() < frame.amountPaise()) {
            persistFailed(senderAccount.getAccountNumber(), frame, TxnStatus.FAILED_INSUFFICIENT_FUNDS,
                    "Insufficient funds", smsBody, senderPhone);
            auditService.logFailure("TXN_FAILED_INSUFFICIENT_FUNDS",
                    String.format("Required %d paise, available %d paise", frame.amountPaise(), senderAccount.getBalancePaise()),
                    senderAccount.getAccountNumber(), senderPhone, smsBody);
            String reply = String.format("OPAY-RSP: FAILED INSUFFICIENT_FUNDS BAL=%.2f",
                    senderAccount.getBalancePaise() / 100.0);
            gatewayService.sendSMS(senderPhone, reply);
            return ValidationResult.fail("INSUFFICIENT_FUNDS");
        }

        // 7. Atomic debit / credit
        senderAccount.setBalancePaise(senderAccount.getBalancePaise() - frame.amountPaise());
        receiverAccount.setBalancePaise(receiverAccount.getBalancePaise() + frame.amountPaise());
        accountRepository.save(senderAccount);
        accountRepository.save(receiverAccount);

        // Persist committed transaction
        Transaction txn = new Transaction();
        txn.setTxnId(frame.txnId());
        txn.setSenderAccount(String.valueOf(frame.senderAccount()));
        txn.setReceiverAccount(String.valueOf(frame.receiverAccount()));
        txn.setAmountPaise(frame.amountPaise());
        txn.setPayloadTimestamp(frame.timestamp());
        txn.setStatus(TxnStatus.SUCCESS);
        txn.setRawSmsPayload(smsBody);
        txn.setSenderPhone(senderPhone);
        transactionRepository.save(txn);

        // Mark TxnID as processed (idempotency guard)
        processedTxnIdRepository.save(new ProcessedTxnId(null, frame.txnId(), Instant.now()));

        // Audit: log successful transaction
        auditService.logSuccess("TXN_SUCCESS",
                String.format("TxnID=%d, %s → %s, ₹%.2f",
                        frame.txnId(), frame.senderAccount(), frame.receiverAccount(), frame.amountPaise() / 100.0),
                senderAccount.getAccountNumber(), senderPhone);

        // 8. Confirmation SMS
        String confirm = String.format(
                "OPAY-RSP: SUCCESS TXN=%d AMT=%.2f BAL=%.2f",
                frame.txnId() & 0xFFFF,   // short display ID
                frame.amountPaise() / 100.0,
                senderAccount.getBalancePaise() / 100.0
        );
        gatewayService.sendSMS(senderPhone, confirm);

        log.info("[OPay] TXN {} committed: {} → {} ₹{}",
                frame.txnId(), frame.senderAccount(), frame.receiverAccount(),
                frame.amountPaise() / 100.0);

        return ValidationResult.success(frame.txnId(), frame.amountPaise(),
                senderAccount.getBalancePaise());
    }

    // ── Balance Query ────────────────────────────────────────

    @Transactional(readOnly = true)
    public void processBalanceQuery(String senderPhone, String smsBody) {
        // Format: "OPAY-BAL: 1234567890"
        String accountNum = smsBody.replaceFirst("^OPAY-BAL:\\s*", "").trim();
        Account account = accountRepository.findByAccountNumber(accountNum).orElse(null);

        if (account == null || !account.getPhoneNumber().equals(senderPhone)) {
            auditService.logFailure("BALANCE_QUERY_FAILED",
                    "Invalid account or phone mismatch for account " + accountNum, accountNum, senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: ERROR INVALID_ACCOUNT");
            return;
        }
        auditService.logSuccess("BALANCE_QUERY",
                String.format("Balance query for %s: ₹%.2f", accountNum, account.getBalancePaise() / 100.0),
                accountNum, senderPhone);
        String reply = String.format("OPAY-RSP: BAL=%.2f ACC=%s",
                account.getBalancePaise() / 100.0, accountNum);
        gatewayService.sendSMS(senderPhone, reply);
    }

    // ── Helpers ──────────────────────────────────────────────

    private void persistFailed(String senderAcc, OPayWireFrame frame, TxnStatus status,
                                String reason, String raw, String phone) {
        Transaction txn = new Transaction();
        txn.setTxnId(frame != null ? frame.txnId() : -1L);
        txn.setSenderAccount(senderAcc);
        txn.setReceiverAccount(frame != null ? String.valueOf(frame.receiverAccount()) : "UNKNOWN");
        txn.setAmountPaise(frame != null ? frame.amountPaise() : 0L);
        txn.setPayloadTimestamp(Instant.now().getEpochSecond());
        txn.setStatus(status);
        txn.setFailureReason(reason);
        txn.setRawSmsPayload(raw);
        txn.setSenderPhone(phone);
        transactionRepository.save(txn);
    }

    // ── Result ───────────────────────────────────────────────

    public record ValidationResult(
            boolean success, Long txnId, Long amountPaise,
            Long newBalancePaise, String failureReason
    ) {
        static ValidationResult success(long txnId, long amount, long balance) {
            return new ValidationResult(true, txnId, amount, balance, null);
        }
        static ValidationResult fail(String reason) {
            return new ValidationResult(false, null, null, null, reason);
        }
    }
}
