package com.eventhive.seat.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "seats")
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "seat_number")
    private String seatNumber;

    @Column(name = "row_name")
    private String rowName;

    private String category; // VIP, PREMIUM, REGULAR

    private BigDecimal price;

    private String status = "AVAILABLE"; // AVAILABLE, LOCKED, BOOKED

    @Column(name = "locked_by")
    private UUID lockedBy;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;
}
