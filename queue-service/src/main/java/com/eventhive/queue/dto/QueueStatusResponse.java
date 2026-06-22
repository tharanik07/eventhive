package com.eventhive.queue.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class QueueStatusResponse {
    private String token;
    private long position;
    private long totalInQueue;
    private String status; // WAITING, YOUR_TURN, EXPIRED
    private long estimatedWaitSeconds;
}
