package com.eventhive.booking.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreateBookingRequest {
    private UUID userId;
    private UUID eventId;
    private List<UUID> seatIds;
    private BigDecimal totalAmount;
}
