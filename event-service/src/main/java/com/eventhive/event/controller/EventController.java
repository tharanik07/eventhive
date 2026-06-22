package com.eventhive.event.controller;

import com.eventhive.event.dto.CreateEventRequest;
import com.eventhive.event.entity.Event;
import com.eventhive.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<Event> create(@Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.ok(eventService.createEvent(request));
    }

    @GetMapping
    public ResponseEntity<List<Event>> list(@RequestParam(required = false) String city,
                                            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(eventService.getEvents(city, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> get(@PathVariable UUID id) {
        return ResponseEntity.ok(eventService.getEvent(id));
    }
}
