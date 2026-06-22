package com.eventhive.payment.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(nullable = false)
    private BigDecimal amount;

    private String status = "PENDING"; // PENDING, COMPLETED, FAILED, REFUNDED

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "gateway_txn_id")
    private String gatewayTxnId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
