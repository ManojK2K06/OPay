package com.opay.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "user_keys")
@Data @NoArgsConstructor @AllArgsConstructor
public class UserKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "public_key_bytes", nullable = false, length = 512)
    private String publicKeyBase64;

    @Column(name = "registered_at")
    @CreationTimestamp
    private Instant registeredAt;

    @Column(name = "revoked")
    private boolean revoked = false;
}
