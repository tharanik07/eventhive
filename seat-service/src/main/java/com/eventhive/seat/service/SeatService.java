package com.eventhive.seat.service;

import com.eventhive.seat.dto.LockSeatsRequest;
import com.eventhive.seat.dto.SeatEvent;
import com.eventhive.seat.entity.Seat;
import com.eventhive.seat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${seat.lock-ttl-seconds:300}")
    private int lockTtlSeconds;

    public List<Seat> getSeats(UUID eventId) {
        return seatRepository.findByEventId(eventId);
    }

    public List<Seat> getAvailableSeats(UUID eventId) {
        return seatRepository.findByEventIdAndStatus(eventId, "AVAILABLE");
    }

    /**
     * Lock seats using Redis SET NX EX (distributed lock with TTL).
     * Only succeeds if ALL requested seats can be locked atomically.
     */
    public boolean lockSeats(LockSeatsRequest request) {
        List<UUID> seatIds = request.getSeatIds();
        UUID userId = request.getUserId();
        List<String> lockedKeys = new ArrayList<>();

        // Try to acquire Redis lock for each seat
        for (UUID seatId : seatIds) {
            String key = "seat:lock:" + seatId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, userId.toString(), Duration.ofSeconds(lockTtlSeconds));

            if (Boolean.TRUE.equals(acquired)) {
                lockedKeys.add(key);
            } else {
                // Rollback: release any locks we already acquired
                lockedKeys.forEach(redisTemplate::delete);
                return false;
            }
        }

        // Update DB status
        updateSeatsLocked(seatIds, userId);

        // Publish event
        kafkaTemplate.send("seat-events", new SeatEvent("SEATS_LOCKED", request.getEventId(), seatIds, userId));
        log.info("Locked seats {} for user {}", seatIds, userId);
        return true;
    }

    @Transactional
    public void releaseSeats(List<UUID> seatIds, UUID userId) {
        for (UUID seatId : seatIds) {
            String key = "seat:lock:" + seatId;
            String lockedBy = redisTemplate.opsForValue().get(key);
            if (userId.toString().equals(lockedBy)) {
                redisTemplate.delete(key);
                seatRepository.releaseSeat(seatId);
            }
        }
        kafkaTemplate.send("seat-events", new SeatEvent("SEATS_RELEASED", null, seatIds, userId));
        log.info("Released seats {} for user {}", seatIds, userId);
    }

    @Transactional
    public void confirmSeats(List<UUID> seatIds) {
        for (UUID seatId : seatIds) {
            redisTemplate.delete("seat:lock:" + seatId);
            seatRepository.markAsBooked(seatId);
        }
        kafkaTemplate.send("seat-events", new SeatEvent("SEATS_BOOKED", null, seatIds, null));
        log.info("Confirmed booking for seats {}", seatIds);
    }

    @Transactional
    private void updateSeatsLocked(List<UUID> seatIds, UUID userId) {
        LocalDateTime lockedUntil = LocalDateTime.now().plusSeconds(lockTtlSeconds);
        for (UUID seatId : seatIds) {
            Seat seat = seatRepository.findById(seatId).orElseThrow();
            seat.setStatus("LOCKED");
            seat.setLockedBy(userId);
            seat.setLockedUntil(lockedUntil);
            seatRepository.save(seat);
        }
    }
}
