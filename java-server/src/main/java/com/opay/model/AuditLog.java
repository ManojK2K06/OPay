package com.opay.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(columnList = "eventType"),
        @Index(columnList = "timestamp"),
        @Index(columnList = "sourceAccount")
})
@Data @NoArgsConstructor @AllArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "source_account")
    private String sourceAccount;

    @Column(name = "source_phone")
    private String sourcePhone;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;
}
