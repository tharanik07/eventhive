package com.eventhive.payment.service;

import com.eventhive.payment.entity.Payment;
import com.eventhive.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Payment Service: listens to booking-events, processes payment, publishes result.
 * Uses idempotency keys to prevent duplicate charges.
 * Simulates payment gateway (configurable failure rate for testing saga compensation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${payment.simulate-failure-rate:0.1}")
    private double failureRate;

    @KafkaListener(topics = "booking-events", groupId = "payment-service")
    @Transactional
    public void handleBookingEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        if (!"BOOKING_CREATED".equals(type)) return;

        String bookingId = (String) event.get("bookingId");
        String idempotencyKey = "payment-" + bookingId;

        // Idempotency check: don't process duplicate events
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Payment already processed for booking {}, skipping", bookingId);
            return;
        }

        BigDecimal amount = new BigDecimal(event.get("amount").toString());

        Payment payment = new Payment();
        payment.setBookingId(UUID.fromString(bookingId));
        payment.setAmount(amount);
        payment.setIdempotencyKey(idempotencyKey);

        // Simulate payment gateway call
        boolean success = simulatePaymentGateway();

        if (success) {
            payment.setStatus("COMPLETED");
            payment.setGatewayTxnId("TXN-" + UUID.randomUUID().toString().substring(0, 8));
            paymentRepository.save(payment);

            kafkaTemplate.send("payment-events", Map.of(
                    "type", "PAYMENT_COMPLETED",
                    "bookingId", bookingId,
                    "paymentId", payment.getId().toString(),
                    "gatewayTxnId", payment.getGatewayTxnId()
            ));
            log.info("Payment successful for booking {}: {}", bookingId, payment.getGatewayTxnId());
        } else {
            payment.setStatus("FAILED");
            paymentRepository.save(payment);

            kafkaTemplate.send("payment-events", Map.of(
                    "type", "PAYMENT_FAILED",
                    "bookingId", bookingId,
                    "reason", "Payment gateway declined"
            ));
            log.warn("Payment failed for booking {}", bookingId);
        }
    }

    /**
     * Simulates a payment gateway. Fails based on configured failure rate.
     */
    private boolean simulatePaymentGateway() {
        try {
            Thread.sleep(500); // simulate network latency
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.random() > failureRate;
    }

    @Transactional
    public Payment refund(UUID bookingId) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("Payment not found for booking"));

        if (!"COMPLETED".equals(payment.getStatus())) {
            throw new RuntimeException("Cannot refund payment in status: " + payment.getStatus());
        }

        payment.setStatus("REFUNDED");
        paymentRepository.save(payment);

        kafkaTemplate.send("payment-events", Map.of(
                "type", "PAYMENT_REFUNDED",
                "bookingId", bookingId.toString(),
                "paymentId", payment.getId().toString()
        ));
        log.info("Payment refunded for booking {}", bookingId);
        return payment;
    }
}
