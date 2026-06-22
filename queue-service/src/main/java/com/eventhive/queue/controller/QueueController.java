package com.eventhive.queue.controller;

import com.eventhive.queue.dto.QueueJoinRequest;
import com.eventhive.queue.dto.QueueStatusResponse;
import com.eventhive.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/join")
    public ResponseEntity<QueueStatusResponse> join(@RequestBody QueueJoinRequest request) {
        return ResponseEntity.ok(queueService.joinQueue(request.getEventId(), request.getUserId()));
    }

    @GetMapping("/status/{token}")
    public ResponseEntity<QueueStatusResponse> status(@PathVariable String token) {
        return ResponseEntity.ok(queueService.getStatus(token));
    }
}
