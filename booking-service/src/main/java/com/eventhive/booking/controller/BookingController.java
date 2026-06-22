package com.eventhive.booking.controller;

import com.eventhive.booking.dto.CreateBookingRequest;
import com.eventhive.booking.entity.Booking;
import com.eventhive.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<Booking> create(@RequestBody CreateBookingRequest request) {
        return ResponseEntity.ok(bookingService.createBooking(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Booking> get(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.getBooking(id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, String>> status(@PathVariable UUID id) {
        Booking booking = bookingService.getBooking(id);
        return ResponseEntity.ok(Map.of("bookingId", id.toString(), "status", booking.getStatus()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable UUID id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.ok(Map.of("status", "CANCELLED"));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Booking>> userBookings(@PathVariable UUID userId) {
        return ResponseEntity.ok(bookingService.getUserBookings(userId));
    }
}
