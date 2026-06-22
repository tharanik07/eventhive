package com.eventhive.booking.repository;

import com.eventhive.booking.entity.BookingSeat;
import com.eventhive.booking.entity.BookingSeatId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {
    List<BookingSeat> findByBookingId(UUID bookingId);
}
