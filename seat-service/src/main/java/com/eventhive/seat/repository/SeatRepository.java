package com.eventhive.seat.repository;

import com.eventhive.seat.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {
    List<Seat> findByEventId(UUID eventId);
    List<Seat> findByEventIdAndStatus(UUID eventId, String status);

    @Modifying
    @Query("UPDATE Seat s SET s.status = 'BOOKED', s.lockedBy = null, s.lockedUntil = null WHERE s.id = :seatId")
    void markAsBooked(UUID seatId);

    @Modifying
    @Query("UPDATE Seat s SET s.status = 'AVAILABLE', s.lockedBy = null, s.lockedUntil = null WHERE s.id = :seatId")
    void releaseSeat(UUID seatId);
}
