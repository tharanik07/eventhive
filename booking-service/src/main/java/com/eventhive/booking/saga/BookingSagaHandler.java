package com.eventhive.booking.saga;

import com.eventhive.booking.entity.Booking;
import com.eventhive.booking.repository.BookingRepository;
import com.eventhive.booking.repository.BookingSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Saga event handler: listens to payment-events and completes/compensates the booking saga.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingSagaHandler {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "payment-events", groupId = "booking-service")
    @Transactional
    public void handlePaymentEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        String bookingId = (String) event.get("bookingId");

        if ("PAYMENT_COMPLETED".equals(type)) {
            confirmBooking(UUID.fromString(bookingId));
        } else if ("PAYMENT_FAILED".equals(type)) {
            failBooking(UUID.fromString(bookingId));
        }
    }

    private void confirmBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || !"PENDING".equals(booking.getStatus())) return;

        booking.setStatus("CONFIRMED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);

        List<String> seatIds = bookingSeatRepository.findByBookingId(bookingId)
                .stream().map(bs -> bs.getSeatId().toString()).toList();

        // Tell seat service to mark seats as BOOKED
        Map<String, Object> confirmEvent = new HashMap<>();
        confirmEvent.put("type", "BOOKING_CONFIRMED");
        confirmEvent.put("bookingId", bookingId.toString());
        confirmEvent.put("userId", booking.getUserId().toString());
        confirmEvent.put("eventId", booking.getEventId().toString());
        confirmEvent.put("seatIds", seatIds);

        kafkaTemplate.send("booking-events", confirmEvent);
        log.info("Booking confirmed via saga: {}", bookingId);
    }

    private void failBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || !"PENDING".equals(booking.getStatus())) return;

        booking.setStatus("FAILED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);

        List<String> seatIds = bookingSeatRepository.findByBookingId(bookingId)
                .stream().map(bs -> bs.getSeatId().toString()).toList();

        // Compensate: release seats
        Map<String, Object> failEvent = new HashMap<>();
        failEvent.put("type", "BOOKING_FAILED");
        failEvent.put("bookingId", bookingId.toString());
        failEvent.put("userId", booking.getUserId().toString());
        failEvent.put("seatIds", seatIds);

        kafkaTemplate.send("booking-events", failEvent);
        log.info("Booking failed, compensation triggered: {}", bookingId);
    }
}
