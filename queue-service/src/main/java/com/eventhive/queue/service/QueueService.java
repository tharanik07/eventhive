package com.eventhive.queue.service;

import com.eventhive.queue.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${queue.batch-size:50}")
    private int batchSize;

    @Value("${queue.token-ttl-seconds:300}")
    private int tokenTtlSeconds;

    private static final String QUEUE_PREFIX = "queue:event:";
    private static final String TOKEN_PREFIX = "queue:token:";

    /**
     * Join the virtual queue for an event.
     * Uses Redis Sorted Set with timestamp as score for FIFO ordering.
     */
    public QueueStatusResponse joinQueue(String eventId, String userId) {
        String queueKey = QUEUE_PREFIX + eventId;
        double score = System.currentTimeMillis();

        // Add user to sorted set (only if not already present)
        redisTemplate.opsForZSet().addIfAbsent(queueKey, userId, score);

        Long position = redisTemplate.opsForZSet().rank(queueKey, userId);
        Long totalInQueue = redisTemplate.opsForZSet().size(queueKey);

        String token = UUID.randomUUID().toString();
        // Store token → userId mapping
        redisTemplate.opsForValue().set(TOKEN_PREFIX + token, userId + ":" + eventId,
                Duration.ofSeconds(tokenTtlSeconds * 2L));

        long estimatedWait = (position != null ? position : 0) * 3; // ~3 sec per batch

        return new QueueStatusResponse(token, position != null ? position + 1 : 1,
                totalInQueue != null ? totalInQueue : 1, "WAITING", estimatedWait);
    }

    /**
     * Check queue position by token.
     */
    public QueueStatusResponse getStatus(String token) {
        String data = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
        if (data == null) {
            return new QueueStatusResponse(token, -1, 0, "EXPIRED", 0);
        }

        String[] parts = data.split(":");
        String userId = parts[0];
        String eventId = parts[1];
        String queueKey = QUEUE_PREFIX + eventId;

        // Check if user has been granted access (token exists in granted set)
        String grantedKey = "queue:granted:" + eventId;
        Boolean isGranted = redisTemplate.opsForSet().isMember(grantedKey, userId);
        if (Boolean.TRUE.equals(isGranted)) {
            return new QueueStatusResponse(token, 0, 0, "YOUR_TURN", 0);
        }

        Long position = redisTemplate.opsForZSet().rank(queueKey, userId);
        if (position == null) {
            // Already processed out of queue
            return new QueueStatusResponse(token, 0, 0, "YOUR_TURN", 0);
        }

        Long totalInQueue = redisTemplate.opsForZSet().size(queueKey);
        long estimatedWait = position * 3;

        return new QueueStatusResponse(token, position + 1,
                totalInQueue != null ? totalInQueue : 0, "WAITING", estimatedWait);
    }

    /**
     * Scheduled task: process batch of users from each queue.
     * Pops N users from the front and grants them booking access.
     */
    @Scheduled(fixedDelayString = "${queue.process-interval-ms:3000}")
    public void processQueues() {
        // Get all active queue keys
        Set<String> keys = redisTemplate.keys(QUEUE_PREFIX + "*");
        if (keys == null) return;

        for (String queueKey : keys) {
            String eventId = queueKey.replace(QUEUE_PREFIX, "");
            Set<ZSetOperations.TypedTuple<String>> batch = redisTemplate.opsForZSet()
                    .popMin(queueKey, batchSize);

            if (batch == null || batch.isEmpty()) continue;

            String grantedKey = "queue:granted:" + eventId;
            for (ZSetOperations.TypedTuple<String> entry : batch) {
                String userId = entry.getValue();
                if (userId != null) {
                    // Grant access (stored in a Set with TTL)
                    redisTemplate.opsForSet().add(grantedKey, userId);
                    redisTemplate.expire(grantedKey, Duration.ofSeconds(tokenTtlSeconds));

                    // Publish queue event
                    kafkaTemplate.send("queue-events", Map.of(
                            "type", "QUEUE_TURN_REACHED",
                            "eventId", eventId,
                            "userId", userId
                    ));
                }
            }
            log.info("Processed {} users from queue for event {}", batch.size(), eventId);
        }
    }
}
