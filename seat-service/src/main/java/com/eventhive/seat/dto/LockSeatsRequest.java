package com.eventhive.seat.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class LockSeatsRequest {
    private UUID eventId;
    private List<UUID> seatIds;
    private UUID userId;
}
