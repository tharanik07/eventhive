package com.eventhive.seat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeatEvent {
    private String type; // SEATS_LOCKED, SEATS_RELEASED, SEATS_BOOKED
    private UUID eventId;
    private List<UUID> seatIds;
    private UUID userId;
}
