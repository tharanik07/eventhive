package com.eventhive.booking.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Data
@Entity
@Table(name = "booking_seats")
@IdClass(BookingSeatId.class)
public class BookingSeat {
    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Id
    @Column(name = "seat_id")
    private UUID seatId;
}
