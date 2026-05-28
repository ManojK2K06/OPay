package com.opay.service;

import com.opay.model.AuditLog;
import com.opay.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Comprehensive audit logging service for all OPay operations.
 * Persists every event to the audit_logs table for compliance and debugging.
 *
 * Events logged:
 *  - SMS_RECEIVED: Every incoming SMS webhook call
 *  - TXN_SUCCESS: Successfully committed transaction
 *  - TXN_FAILED_*: Failed transactions (with reason)
 *  - KEY_REGISTERED: New device public key registered
 *  - KEY_REVOKED: Existing device key revoked
 *  - BALANCE_QUERY: Balance enquiry processed
 *  - GATEWAY_HEALTH: Android gateway health check result
 *  - SEED_DB: Test accounts seeded
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Log an audit event. Runs asynchronously to avoid slowing down the main request.
     */
    public void logEvent(String eventType, String detail, String sourceAccount,
                         String sourcePhone, String rawPayload, boolean success) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEventType(eventType);
            entry.setDetail(detail);
            entry.setSourceAccount(sourceAccount);
            entry.setSourcePhone(sourcePhone);
            entry.setRawPayload(rawPayload);
            entry.setSuccess(success);
            entry.setTimestamp(Instant.now());
            auditLogRepository.save(entry);
            log.debug("[Audit] {} | account={} | success={} | {}", eventType, sourceAccount, success, detail);
        } catch (Exception e) {
            // Never let audit logging break the main flow
            log.error("[Audit] Failed to persist audit log: {}", e.getMessage());
        }
    }

    /** Shorthand for successful events */
    public void logSuccess(String eventType, String detail, String sourceAccount, String sourcePhone) {
        logEvent(eventType, detail, sourceAccount, sourcePhone, null, true);
    }

    /** Shorthand for failed events */
    public void logFailure(String eventType, String detail, String sourceAccount, String sourcePhone, String rawPayload) {
        logEvent(eventType, detail, sourceAccount, sourcePhone, rawPayload, false);
    }
}
