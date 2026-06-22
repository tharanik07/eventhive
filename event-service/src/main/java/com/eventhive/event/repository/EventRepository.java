package com.eventhive.event.repository;

import com.eventhive.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    List<Event> findByCityIgnoreCase(String city);
    List<Event> findByStatus(String status);
    List<Event> findByCityIgnoreCaseAndStatus(String city, String status);
}
