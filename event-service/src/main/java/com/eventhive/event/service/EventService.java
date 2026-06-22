package com.eventhive.event.service;

import com.eventhive.event.dto.CreateEventRequest;
import com.eventhive.event.entity.Event;
import com.eventhive.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    public Event createEvent(CreateEventRequest request) {
        Event event = new Event();
        event.setName(request.getName());
        event.setDescription(request.getDescription());
        event.setVenue(request.getVenue());
        event.setCity(request.getCity());
        event.setEventDate(request.getEventDate());
        event.setTotalSeats(request.getTotalSeats());
        event.setAvailableSeats(request.getTotalSeats());
        event.setImageUrl(request.getImageUrl());
        return eventRepository.save(event);
    }

    public List<Event> getEvents(String city, String status) {
        if (city != null && status != null) return eventRepository.findByCityIgnoreCaseAndStatus(city, status);
        if (city != null) return eventRepository.findByCityIgnoreCase(city);
        if (status != null) return eventRepository.findByStatus(status);
        return eventRepository.findAll();
    }

    public Event getEvent(UUID id) {
        return eventRepository.findById(id).orElseThrow(() -> new RuntimeException("Event not found"));
    }
}
