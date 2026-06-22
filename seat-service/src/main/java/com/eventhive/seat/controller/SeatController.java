package com.eventhive.seat.controller;

import com.eventhive.seat.dto.LockSeatsRequest;
import com.eventhive.seat.entity.Seat;
import com.eventhive.seat.service.SeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @GetMapping("/events/{eventId}/seats")
    public ResponseEntity<List<Seat>> getSeats(@PathVariable UUID eventId) {
        return ResponseEntity.ok(seatService.getSeats(eventId));
    }

    @GetMapping("/events/{eventId}/seats/available")
    public ResponseEntity<List<Seat>> getAvailable(@PathVariable UUID eventId) {
        return ResponseEntity.ok(seatService.getAvailableSeats(eventId));
    }

    @PostMapping("/seats/lock")
    public ResponseEntity<?> lockSeats(@RequestBody LockSeatsRequest request) {
        boolean locked = seatService.lockSeats(request);
        if (locked) {
            return ResponseEntity.ok(Map.of("status", "LOCKED", "ttlSeconds", 300));
        }
        return ResponseEntity.status(409).body(Map.of("error", "One or more seats already locked"));
    }

    @PostMapping("/seats/release")
    public ResponseEntity<?> releaseSeats(@RequestBody Map<String, Object> request) {
        List<UUID> seatIds = ((List<String>) request.get("seatIds")).stream().map(UUID::fromString).toList();
        UUID userId = UUID.fromString((String) request.get("userId"));
        seatService.releaseSeats(seatIds, userId);
        return ResponseEntity.ok(Map.of("status", "RELEASED"));
    }
}
