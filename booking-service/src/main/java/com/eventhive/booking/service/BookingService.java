package com.eventhive.booking.service;

import com.eventhive.booking.dto.CreateBookingRequest;
import com.eventhive.booking.entity.Booking;
import com.eventhive.booking.entity.BookingSeat;
import com.eventhive.booking.repository.BookingRepository;
import com.eventhive.booking.repository.BookingSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Start booking saga: create booking record and publish BookingCreated event.
     * Payment service will pick this up and process payment.
     */
    @Transactional
    public Booking createBooking(CreateBookingRequest request) {
        Booking booking = new Booking();
        booking.setUserId(request.getUserId());
        booking.setEventId(request.getEventId());
        booking.setTotalAmount(request.getTotalAmount());
        booking.setStatus("PENDING");
        bookingRepository.save(booking);

        // Save seat associations
        for (UUID seatId : request.getSeatIds()) {
            BookingSeat bs = new BookingSeat();
            bs.setBookingId(booking.getId());
            bs.setSeatId(seatId);
            bookingSeatRepository.save(bs);
        }

        // Publish saga start event
        Map<String, Object> event = new HashMap<>();
        event.put("type", "BOOKING_CREATED");
        event.put("bookingId", booking.getId().toString());
        event.put("userId", request.getUserId().toString());
        event.put("eventId", request.getEventId().toString());
        event.put("seatIds", request.getSeatIds().stream().map(UUID::toString).toList());
        event.put("amount", request.getTotalAmount());
        event.put("timestamp", System.currentTimeMillis());

        kafkaTemplate.send("booking-events", event);
        log.info("Booking saga started: {}", booking.getId());
        return booking;
    }

    public Booking getBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
    }

    public List<Booking> getUserBookings(UUID userId) {
        return bookingRepository.findByUserId(userId);
    }

    /**
     * Saga compensation: cancel booking and trigger seat release.
     */
    @Transactional
    public void cancelBooking(UUID bookingId) {
        Booking booking = getBooking(bookingId);
        booking.setStatus("CANCELLED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);

        List<BookingSeat> seats = bookingSeatRepository.findByBookingId(bookingId);
        List<String> seatIds = seats.stream().map(bs -> bs.getSeatId().toString()).toList();

        // Publish cancellation for seat service to release locks
        Map<String, Object> event = new HashMap<>();
        event.put("type", "BOOKING_CANCELLED");
        event.put("bookingId", bookingId.toString());
        event.put("userId", booking.getUserId().toString());
        event.put("seatIds", seatIds);

        kafkaTemplate.send("booking-events", event);
        log.info("Booking cancelled: {}", bookingId);
    }
}
