package com.opay.repository;

import com.opay.model.ProcessedTxnId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedTxnIdRepository extends JpaRepository<ProcessedTxnId, Long> {
    boolean existsByTxnId(Long txnId);
}
