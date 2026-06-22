package com.eventhive.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CreateEventRequest {
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String venue;
    @NotBlank
    private String city;
    @NotNull
    private LocalDateTime eventDate;
    private int totalSeats;
    private String imageUrl;
}
