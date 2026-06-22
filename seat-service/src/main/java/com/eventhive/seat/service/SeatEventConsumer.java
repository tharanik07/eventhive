package com.eventhive.seat.service;

import com.eventhive.seat.dto.SeatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatEventConsumer {

    private final SeatService seatService;

    @KafkaListener(topics = "booking-events", groupId = "seat-service")
    public void handleBookingEvent(Map<String, Object> event) {
        String type = (String) event.get("type");

        if ("BOOKING_CONFIRMED".equals(type)) {
            List<String> seatIdStrings = (List<String>) event.get("seatIds");
            List<UUID> seatIds = seatIdStrings.stream().map(UUID::fromString).toList();
            seatService.confirmSeats(seatIds);
            log.info("Seats confirmed via booking event: {}", seatIds);
        } else if ("BOOKING_FAILED".equals(type) || "BOOKING_CANCELLED".equals(type)) {
            List<String> seatIdStrings = (List<String>) event.get("seatIds");
            List<UUID> seatIds = seatIdStrings.stream().map(UUID::fromString).toList();
            UUID userId = UUID.fromString((String) event.get("userId"));
            seatService.releaseSeats(seatIds, userId);
            log.info("Seats released via booking failure: {}", seatIds);
        }
    }
}
