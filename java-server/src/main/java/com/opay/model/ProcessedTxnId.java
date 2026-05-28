package com.opay.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "processed_txn_ids", indexes = @Index(columnList = "txnId", unique = true))
@Data @NoArgsConstructor @AllArgsConstructor
public class ProcessedTxnId {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_id", nullable = false, unique = true)
    private Long txnId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
