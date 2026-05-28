package com.opay.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = @Index(columnList = "txnId", unique = true))
@Data @NoArgsConstructor @AllArgsConstructor
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_id", nullable = false, unique = true)
    private Long txnId;

    @Column(name = "sender_account", nullable = false)
    private String senderAccount;

    @Column(name = "receiver_account", nullable = false)
    private String receiverAccount;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "payload_timestamp", nullable = false)
    private Long payloadTimestamp;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TxnStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "raw_sms_payload", columnDefinition = "TEXT")
    private String rawSmsPayload;

    @Column(name = "sender_phone")
    private String senderPhone;

    @CreationTimestamp
    private Instant processedAt;
}
