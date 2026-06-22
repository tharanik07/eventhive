package com.eventhive.payment.controller;

import com.eventhive.payment.entity.Payment;
import com.eventhive.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/refund")
    public ResponseEntity<Payment> refund(@RequestBody Map<String, String> request) {
        UUID bookingId = UUID.fromString(request.get("bookingId"));
        return ResponseEntity.ok(paymentService.refund(bookingId));
    }
}
