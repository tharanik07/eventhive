package com.eventhive.booking.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.Data;

@Data
public class BookingSeatId implements Serializable {
    private UUID bookingId;
    private UUID seatId;
}
