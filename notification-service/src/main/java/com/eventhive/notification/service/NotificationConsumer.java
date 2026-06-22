package com.eventhive.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Notification Service: pure Kafka consumer.
 * Listens to booking-events and sends notifications (simulated via logging).
 * In production, integrate SendGrid (email), Twilio (SMS), FCM (push).
 */
@Slf4j
@Service
public class NotificationConsumer {

    @KafkaListener(topics = "booking-events", groupId = "notification-service")
    public void handleBookingEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        String bookingId = (String) event.get("bookingId");
        String userId = (String) event.get("userId");

        switch (type) {
            case "BOOKING_CONFIRMED" -> sendConfirmation(bookingId, userId, event);
            case "BOOKING_FAILED" -> sendFailureNotification(bookingId, userId);
            case "BOOKING_CANCELLED" -> sendCancellationNotification(bookingId, userId);
            default -> log.debug("Ignoring event type: {}", type);
        }
    }

    @KafkaListener(topics = "queue-events", groupId = "notification-service")
    public void handleQueueEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        if ("QUEUE_TURN_REACHED".equals(type)) {
            String userId = (String) event.get("userId");
            String eventId = (String) event.get("eventId");
            log.info("📱 PUSH NOTIFICATION → User {}: It's your turn! Proceed to booking for event {}",
                    userId, eventId);
        }
    }

    private void sendConfirmation(String bookingId, String userId, Map<String, Object> event) {
        log.info("📧 EMAIL → User {}: Booking {} CONFIRMED!", userId, bookingId);
        log.info("📱 SMS → User {}: Your ticket is booked! Booking ID: {}", userId, bookingId);
        log.info("🔔 PUSH → User {}: Booking confirmed. Check your email for the e-ticket.", userId);
        // In production: generate PDF ticket, upload to S3, attach to email
    }

    private void sendFailureNotification(String bookingId, String userId) {
        log.info("📧 EMAIL → User {}: Booking {} FAILED. Payment was declined. Please try again.",
                userId, bookingId);
        log.info("📱 SMS → User {}: Payment failed for booking {}. No charge was made.",
                userId, bookingId);
    }

    private void sendCancellationNotification(String bookingId, String userId) {
        log.info("📧 EMAIL → User {}: Booking {} has been CANCELLED. Refund will be processed.",
                userId, bookingId);
    }
}
