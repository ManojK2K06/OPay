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

    public record ValidationResult(boolean isSuccess, String failureReason, Long txnId, Long amountPaise, Long newBalance) {
        public static ValidationResult success() { return new ValidationResult(true, null, null, null, null); }
        public static ValidationResult success(Long txnId, Long amount, Long bal) { return new ValidationResult(true, null, txnId, amount, bal); }
        public static ValidationResult fail(String reason) { return new ValidationResult(false, reason, null, null, null); }
    }

    @Transactional
    public ValidationResult processTransaction(String senderPhone, String smsBody) {
        log.info("[OPay] Processing SMS from {} | body length={}", senderPhone, smsBody.length());

        auditService.logEvent("SMS_RECEIVED", "Incoming transaction SMS",
                null, senderPhone, smsBody, true);

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

        UserKey userKey = userKeyRepository
                .findByAccountNumberAndRevokedFalse(senderAccount.getAccountNumber())
                .orElse(null);

        if (userKey == null) {
            auditService.logFailure("TXN_FAILED_NO_KEY",
                    "No active public key registered", senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: ERROR NO_KEY_REGISTERED");
            return ValidationResult.fail("NO_KEY_REGISTERED");
        }

        OPayWireFrame frame = null;
        if (smsBody.startsWith("OPAY-TXN:")) {
            try {
                String clean = smsBody.replace("OPAY-TXN:", "").trim();
                String[] parts = clean.split(",");
                long s = Long.parseLong(parts[0].trim());
                long r = Long.parseLong(parts[1].trim());
                long a = Long.parseLong(parts[2].trim());
                frame = new OPayWireFrame(s, r, Instant.now().getEpochSecond(), Instant.now().toEpochMilli(), a);
            } catch (Exception e) {
                return ValidationResult.fail("PARSE_FAIL");
            }
        } else {
            return ValidationResult.fail("NOT_A_TXN");
        }

        if (processedTxnIdRepository.existsByTxnId(frame.txnId())) {
            log.warn("[OPay] Duplicate TxnID: {}", frame.txnId());
            auditService.logFailure("TXN_FAILED_REPLAY",
                    "Duplicate TxnID: " + frame.txnId(), senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: REJECTED REPLAY");
            return ValidationResult.fail("REPLAY");
        }

        if (frame.senderAccount() != Long.parseLong(senderAccount.getAccountNumber())) {
            log.error("[OPay] Account mismatch: payload={} registered={}",
                    frame.senderAccount(), senderAccount.getAccountNumber());
            auditService.logFailure("TXN_FAILED_ACCOUNT_MISMATCH",
                    "Payload sender " + frame.senderAccount() + " != registered " + senderAccount.getAccountNumber(),
                    senderAccount.getAccountNumber(), senderPhone, smsBody);
            gatewayService.sendSMS(senderPhone, "OPAY-RSP: REJECTED ACCOUNT_MISMATCH");
            return ValidationResult.fail("ACCOUNT_MISMATCH");
        }

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

        senderAccount.setBalancePaise(senderAccount.getBalancePaise() - frame.amountPaise());
        receiverAccount.setBalancePaise(receiverAccount.getBalancePaise() + frame.amountPaise());
        accountRepository.save(senderAccount);
        accountRepository.save(receiverAccount);

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

        processedTxnIdRepository.save(new ProcessedTxnId(null, frame.txnId(), Instant.now()));

        auditService.logSuccess("TXN_SUCCESS",
                String.format("TxnID=%d, %s -> %s, ?%.2f",
                        frame.txnId(), frame.senderAccount(), frame.receiverAccount(), frame.amountPaise() / 100.0),
                senderAccount.getAccountNumber(), senderPhone);

        OPayWireFrame responseFrame = new OPayWireFrame(
                frame.senderAccount(),
                frame.receiverAccount(),
                (long) (Instant.now().getEpochSecond() & 0xFFFFFFFFL),
                frame.txnId(),
                senderAccount.getBalancePaise()
        );

        try {
            String reply = String.format("OPay: Transfer successful! New balance: Rs %.2f\nopay://txn?d=SUCCESS,%d",
                    senderAccount.getBalancePaise() / 100.0, senderAccount.getBalancePaise());
            gatewayService.sendSMS(senderPhone, reply);
            log.info("[OPay] Plaintext txn success sent via Deep Link to {}", senderPhone);
        } catch (Exception e) {
            log.error("[OPay] Failed to send response: {}", e.getMessage());
        }

        return ValidationResult.success(frame.txnId(), frame.amountPaise(),
                senderAccount.getBalancePaise());
    }

    @Transactional(readOnly = true)
    public void processBalanceQuery(String senderPhone, String smsBody) {
        Account senderAccount = accountRepository.findByPhoneNumber(senderPhone).orElse(null);
        if (senderAccount == null) {
            log.warn("[OPay BAL] Unknown sender phone: {}", senderPhone);
            log.error("[OPay BAL] Silently returning! smsBody: " + smsBody); return;
        }

        UserKey userKey = userKeyRepository
                .findByAccountNumberAndRevokedFalse(senderAccount.getAccountNumber())
                .orElse(null);
        
        if (userKey == null) {
            log.warn("[OPay BAL] No key for account: {}", senderAccount.getAccountNumber());
            log.error("[OPay BAL] Silently returning! smsBody: " + smsBody); return;
        }

        OPayWireFrame frame = null;
        if (smsBody.startsWith("OPAY-BAL:") || smsBody.startsWith("OPAY-ENQ:") || smsBody.startsWith("OPAL-BAL:") || smsBody.startsWith("OPAL-ENQ:")) {
            try {
                String clean = smsBody.replace("OPAY-BAL:", "").replace("OPAY-ENQ:", "")
                                      .replace("OPAL-BAL:", "").replace("OPAL-ENQ:", "").trim();
                long s = Long.parseLong(clean);
                frame = new OPayWireFrame(s, 0, Instant.now().getEpochSecond(), Instant.now().toEpochMilli(), 0);
            } catch (Exception e) {
                log.error("[OPay BAL] Silently returning! smsBody: " + smsBody); return;
            }
        } else {
            log.error("[OPay BAL] Silently returning! smsBody: " + smsBody); return;
        }
        if (frame.senderAccount() != Long.parseLong(senderAccount.getAccountNumber())) {
            auditService.logFailure("BAL_FAILED_ACCOUNT_MISMATCH", "Mismatch", senderAccount.getAccountNumber(), senderPhone, smsBody);
            log.error("[OPay BAL] Silently returning! smsBody: " + smsBody); return;
        }

        auditService.logSuccess("BALANCE_QUERY_ENCRYPTED",
                String.format("Balance query for %s: ?%.2f", senderAccount.getAccountNumber(), senderAccount.getBalancePaise() / 100.0),
                senderAccount.getAccountNumber(), senderPhone);

        OPayWireFrame responseFrame = new OPayWireFrame(
                frame.senderAccount(),
                0L,
                (long) (Instant.now().getEpochSecond() & 0xFFFFFFFFL),
                frame.txnId(),
                senderAccount.getBalancePaise()
        );

        try {
            String reply = String.format("OPay: Your balance is Rs %.2f\nopay://bal?d=SUCCESS,%d",
                    senderAccount.getBalancePaise() / 100.0, senderAccount.getBalancePaise());
            gatewayService.sendSMS(senderPhone, reply);
            log.info("[OPay BAL] Plaintext balance sent via Deep Link to {}", senderPhone);
        } catch (Exception e) {
            log.error("[OPay BAL] Failed to send response: {}", e.getMessage());
        }
    }

    private void persistFailed(String senderAcc, OPayWireFrame frame, TxnStatus status, String reason, String payload, String phone) {
        Transaction txn = new Transaction();
        txn.setSenderAccount(senderAcc);
        if (frame != null) {
            txn.setTxnId(frame.txnId());
            txn.setReceiverAccount(String.valueOf(frame.receiverAccount()));
            txn.setAmountPaise(frame.amountPaise());
            txn.setPayloadTimestamp(frame.timestamp());
        }
        txn.setStatus(status);
        txn.setFailureReason(reason);
        txn.setRawSmsPayload(payload);
        txn.setSenderPhone(phone);
        transactionRepository.save(txn);
    }
}
