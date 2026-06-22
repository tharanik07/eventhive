# EventHive — End-to-End System Design Write-Up

## 1. Problem Statement

When a high-demand event (concert, sports match, product launch) opens for booking, millions of users hit the system simultaneously competing for limited inventory. Traditional monolithic booking systems fail catastrophically under this load:

- **Ticketmaster (Taylor Swift, 2022):** 14 million concurrent users crashed the platform
- **BookMyShow (IPL):** Crashes every season during ticket drops
- **PS5 Launch:** Bots purchased 80% of inventory in seconds

The core engineering challenges:
1. **Thundering herd** — millions of requests at T=0 overwhelm backends
2. **Double-booking** — two users get the same seat
3. **Overselling** — more tickets sold than physically exist
4. **Payment-inventory mismatch** — payment fails but seats remain locked forever
5. **Bot attacks** — automated scalpers outpace legitimate users
6. **Unfair access** — users with faster connections always win

EventHive solves each of these with specific distributed systems patterns.

---

## 2. Architecture Overview

```
┌──────────────┐     ┌─────────────────┐     ┌──────────────────────────┐
│   React UI   │────▶│   API Gateway   │────▶│      Microservices        │
│  (Port 3000) │     │   (Port 8080)   │     │                          │
└──────────────┘     │  • Rate Limiting │     │  User     (8081) — JWT   │
                     │  • CORS          │     │  Event    (8082) — CRUD  │
                     │  • Routing       │     │  Seat     (8083) — Locks │
                     └─────────────────┘     │  Queue    (8084) — FIFO  │
                                              │  Booking  (8085) — Saga  │
                                              │  Payment  (8086) — Idem. │
                                              │  Notify   (8087) — Async │
                                              └────────────┬─────────────┘
                                                           │
                                              ┌────────────▼─────────────┐
                                              │       Apache Kafka        │
                                              │    (Event Backbone)       │
                                              └────────────┬─────────────┘
                                                           │
                                    ┌──────────────────────┼──────────────┐
                                    │                      │              │
                              ┌─────▼─────┐    ┌──────────▼──┐   ┌──────▼──────┐
                              │ PostgreSQL │    │    Redis     │   │    Kafka    │
                              │ (Bookings, │    │ (Locks,      │   │  (Events,   │
                              │  Payments, │    │  Queue,      │   │   DLQ)      │
                              │  Users)    │    │  Rate Limit) │   │             │
                              └───────────┘    └─────────────┘   └─────────────┘
```

**Design Principle:** Each service owns its data and communicates only via Kafka events (choreography-based saga). No service directly calls another service's database.

---

## 3. End-to-End Booking Flow

### Happy Path (User books 2 seats for a concert)

```
User                Gateway          Queue         Seat          Booking       Payment       Notification
 │                    │               │             │              │             │              │
 │──GET /events──────▶│──────────────────────────▶│              │             │              │
 │◀─Event list────────│◀──────────────────────────│              │             │              │
 │                    │               │             │              │             │              │
 │──GET /events/1/seats──────────────────────────▶│              │             │              │
 │◀─Seat map (from Redis cache)──────────────────│              │             │              │
 │                    │               │             │              │             │              │
 │──POST /queue/join─▶│──────────────▶│             │              │             │              │
 │◀─Token + position──│◀──────────────│             │              │             │              │
 │                    │               │             │              │             │              │
 │──GET /queue/status (poll every 2s)▶│             │              │             │              │
 │◀─position=0 (YOUR_TURN)───────────│             │              │             │              │
 │                    │               │             │              │             │              │
 │──POST /seats/lock─▶│─────────────────────────▶│              │             │              │
 │                    │               │     SET NX EX 300 (Redis) │             │              │
 │◀─LOCKED (5min TTL)─│◀──────────────────────────│──Kafka: SEATS_LOCKED──────▶│              │
 │                    │               │             │              │             │              │
 │──POST /bookings───▶│────────────────────────────────────────▶│             │              │
 │◀─PENDING───────────│◀────────────────────────────────────────│             │              │
 │                    │               │             │              │──Kafka:─────▶│              │
 │                    │               │             │              │ BOOKING_     │              │
 │                    │               │             │              │ CREATED      │              │
 │                    │               │             │              │             │              │
 │                    │               │             │              │◀─Kafka:──────│              │
 │                    │               │             │              │ PAYMENT_     │              │
 │                    │               │             │              │ COMPLETED    │              │
 │                    │               │             │              │             │              │
 │                    │               │             │◀─Kafka:───────│             │              │
 │                    │               │             │ BOOKING_      │             │              │
 │                    │               │             │ CONFIRMED     │             │              │
 │                    │               │             │              │             │              │
 │                    │               │             │ Mark BOOKED  │             │──Kafka:──────▶│
 │                    │               │             │ Delete lock  │             │BOOKING_       │
 │                    │               │             │              │             │CONFIRMED      │
 │                    │               │             │              │             │              │
 │──GET /bookings/id/status──────────────────────────────────────▶│             │   📧 Email   │
 │◀─CONFIRMED─────────│◀────────────────────────────────────────│             │   📱 SMS     │
 │                    │               │             │              │             │   🔔 Push    │
```

### Failure Path (Payment Declined — Saga Compensation)

```
Payment Service                    Booking Service                 Seat Service
      │                                  │                              │
      │──Kafka: PAYMENT_FAILED──────────▶│                              │
      │                                  │                              │
      │                                  │ booking.status = FAILED      │
      │                                  │                              │
      │                                  │──Kafka: BOOKING_FAILED──────▶│
      │                                  │                              │
      │                                  │              seat.status = AVAILABLE  │
      │                                  │              DELETE Redis lock        │
      │                                  │                              │
      │                                  │──Kafka: BOOKING_FAILED──────▶ Notification
      │                                  │                              │
      │                                  │                    📧 "Payment failed, seats released"
```

**Key guarantee:** If payment fails, seats are ALWAYS released. The saga ensures no state is permanently inconsistent.

---

## 4. Solving Each Problem

### Problem 1: Thundering Herd → Virtual Queue

**Solution:** Redis Sorted Set as a FIFO queue

```
User clicks "Book Now"
    ↓
ZADD queue:event:{id} {timestamp} {userId}    ← O(log N) insert
    ↓
Return position via ZRANK                      ← O(log N) lookup
    ↓
Background scheduler: ZPOPMIN every 3 seconds  ← Pops 50 users per batch
    ↓
Grant booking access (Redis SET with TTL)
    ↓
User polls status → "YOUR_TURN" → redirect to seat selection
```

**Why this works:** Instead of 1M users hitting the booking endpoint simultaneously, the queue meters them to 50 users every 3 seconds. Downstream services see controlled, predictable load.

**Throughput math:** 50 users/batch × (1000ms / 3000ms) = ~16 users/sec admitted. With 20 Kafka partitions, each partition handles <1 booking/sec — trivial.

---

### Problem 2: Double-Booking → Redis Distributed Lock

**Solution:** `SET seat:lock:{seatId} {userId} NX EX 300`

```java
// Atomic lock acquisition — only succeeds if key doesn't exist
Boolean acquired = redis.setIfAbsent(key, userId, Duration.ofSeconds(300));
```

**NX flag:** Only set if Not eXists — guarantees exactly one user gets the lock.
**EX 300:** Auto-expires after 5 minutes — handles abandoned carts without cleanup jobs.

**Multi-seat atomicity:** If locking 3 seats and seat #2 fails, we rollback by deleting seats #1's lock. All-or-nothing.

---

### Problem 3: Overselling → Atomic State Transitions

**Solution:** Three-state machine with transitions that can never skip:

```
AVAILABLE → LOCKED → BOOKED
     ↑                  │
     └──────────────────┘  (only via compensation/cancellation)
```

- A seat can only be BOOKED if it was LOCKED by the same user
- LOCKED → AVAILABLE happens automatically (TTL) or via saga compensation
- Database status is updated AFTER Redis lock is acquired (Redis is source of truth for availability)

---

### Problem 4: Payment-Inventory Mismatch → Saga Pattern

**Solution:** Choreography-based saga via Kafka events

```
BookingCreated → PaymentInitiated → PaymentCompleted → BookingConfirmed → SeatsBooked
                                  ↓ (failure)
                            PaymentFailed → BookingFailed → SeatsReleased
```

**Why choreography over orchestration:**
- No single point of failure (no saga coordinator to crash)
- Each service reacts independently to events
- Kafka guarantees event delivery (at-least-once with idempotency)

**Idempotency key:** `payment-{bookingId}` — even if Kafka delivers the same BookingCreated twice, the payment is processed exactly once.

---

### Problem 5: Bot Attacks → Rate Limiting

**Solution:** Token bucket via Redis (API Gateway level)

```
Configuration:
  replenish_rate: 10 tokens/second
  burst_capacity: 20 tokens
  cost_per_request: 1 token

Result:
  Sustained: 10 requests/sec per IP
  Burst: up to 20 in a spike
  Exhausted: HTTP 429 Too Many Requests
```

Bots that make 100 requests/sec get blocked after the first 20. Legitimate users making 1-2 requests rarely hit the limit.

---

### Problem 6: Unfair Queue Jumping → FIFO with Tokens

**Solution:** Redis ZADD with timestamp as score guarantees first-come-first-served ordering. The token issued to each user is:
- One-time use (stored in Redis with TTL)
- Tied to a specific user+event combination
- Cannot be transferred or reused

---

### Problem 7: Failed Payments Blocking Seats → TTL-Based Holds

**Solution:** Every seat lock has a 300-second TTL in Redis.

Three release triggers:
1. **Payment success** → explicit DELETE + mark BOOKED
2. **Payment failure** → saga compensation → explicit DELETE + mark AVAILABLE
3. **Timeout** → Redis auto-expires → seat becomes available (next reader sees key doesn't exist)

No manual cleanup, no cron jobs, no stale locks.

---

## 5. Data Design

### PostgreSQL (Source of truth for committed data)

```sql
users        → id, email, password_hash, role
events       → id, name, venue, city, event_date, total_seats, available_seats
seats        → id, event_id, seat_number, row_name, category, price, status
bookings     → id, user_id, event_id, total_amount, status, created_at
booking_seats → booking_id, seat_id (many-to-many)
payments     → id, booking_id, amount, status, idempotency_key, gateway_txn_id
```

### Redis (Ephemeral state + locks)

```
seat:lock:{seatId}          → userId (TTL: 300s)    — distributed lock
queue:event:{eventId}       → SortedSet(userId, ts) — virtual queue
queue:granted:{eventId}     → Set(userId)           — who has booking access
queue:token:{token}         → "userId:eventId"      — token lookup
rate:request:{ip}           → token bucket state    — rate limiting
```

### Kafka Topics

```
booking-events    (20 partitions) → BOOKING_CREATED, CONFIRMED, FAILED, CANCELLED
payment-events    (10 partitions) → PAYMENT_COMPLETED, FAILED, REFUNDED
seat-events       (20 partitions) → SEATS_LOCKED, RELEASED, BOOKED
queue-events      (10 partitions) → QUEUE_TURN_REACHED
notification-events (5 partitions) → EMAIL, SMS, PUSH payloads
```

**Partition key strategy:**
- `booking-events` → partition by bookingId (saga events stay ordered)
- `seat-events` → partition by seatId (all ops on one seat are ordered)
- `queue-events` → partition by eventId (per-event ordering)

---

## 6. Technology Choices & Justification

| Decision | Choice | Why (not alternatives) |
|----------|--------|----------------------|
| Language | Java 17 | Type safety for financial operations; ecosystem maturity |
| Framework | Spring Boot 3.2 | Industry standard, production-proven, rich integration |
| Message Broker | Kafka | Durable, ordered, replayable; RabbitMQ can't replay |
| Cache/Lock | Redis | Sub-ms latency, atomic SET NX EX, native sorted sets |
| Primary DB | PostgreSQL | ACID for bookings/payments, JSONB flexibility |
| API Gateway | Spring Cloud Gateway | Native Spring integration, reactive, built-in rate limiter |
| Frontend | React 18 + Vite | Component model for seat map, fast HMR dev loop |
| Auth | JWT + BCrypt | Stateless, no session store needed at gateway level |

---

## 7. Failure Scenarios & Handling

| Scenario | What happens | Recovery |
|----------|-------------|----------|
| Redis dies during seat lock | Lock fails → user sees "seat unavailable" | DB still has AVAILABLE status; try again |
| Kafka broker down | Messages buffered in producer | Producer retries with backoff; in-flight saga pauses |
| Payment gateway timeout | Payment status unknown | Idempotency key allows safe retry; timeout → FAILED after 30s |
| Service crash mid-saga | Booking stuck in PENDING | TTL expires → seat auto-released; user retries |
| Duplicate Kafka message | PaymentService receives same BookingCreated twice | Idempotency key → second attempt is no-op |
| Network partition (Redis ↔ DB) | Redis says locked, DB says available | Redis is authoritative for locks; DB updated after |

---

## 8. Scalability Characteristics

| Component | Scaling Strategy | Bottleneck |
|-----------|-----------------|------------|
| API Gateway | Horizontal (stateless) | None — round-robin behind LB |
| User Service | Horizontal (stateless) | DB connections (pool) |
| Seat Service | Limited by Redis single-thread | Shard by eventId across Redis clusters |
| Queue Service | Single scheduler (leader election) | Redis ZPOPMIN is O(log N) × batch_size |
| Booking Service | Horizontal (Kafka consumer groups) | Kafka partition count (20) |
| Payment Service | Horizontal (idempotent) | External gateway rate limit |
| Kafka | Partition count × brokers | 20 partitions = 20 parallel consumers max |

**Capacity estimate:**
- 20 booking-event partitions × 50 msgs/sec/partition = 1,000 bookings/sec
- Queue admits 50 users every 3 seconds = ~16/sec sustained
- Redis: 100K+ ops/sec for lock operations — never the bottleneck

---

## 9. What's Implemented vs. Production-Grade

| Feature | Current Implementation | Production Would Need |
|---------|----------------------|----------------------|
| Seat Lock | Redis SET NX EX | Redisson Redlock (multi-node) + fencing tokens |
| Payment | Simulated (random success/fail) | Stripe/Razorpay SDK with webhooks |
| Notifications | Console logging | SendGrid email, Twilio SMS, FCM push |
| Observability | None | Prometheus + Grafana + ELK + Zipkin |
| Auth | Basic JWT | OAuth2 + refresh tokens + account lockout |
| Bot Detection | Rate limiting only | Fingerprinting + CAPTCHA + velocity scoring |
| Queue | Fixed batch size | Adaptive based on downstream capacity |
| Deployment | Docker Compose | Kubernetes with HPA + PodDisruptionBudgets |

---

## 10. Frontend Architecture

```
src/
├── components/
│   ├── ErrorBoundary.jsx   — catches render crashes, shows recovery UI
│   ├── Navbar.jsx          — auth-aware navigation
│   ├── SeatMap.jsx         — interactive grid, color-coded, accessible (aria-labels)
│   ├── BookingTimer.jsx    — 5-min countdown with urgency state
│   ├── QueueStatus.jsx     — real-time position polling with progress bar
│   └── Toast.jsx           — notification toasts (success/error/info)
├── pages/
│   ├── Login.jsx           — form with loading state + validation
│   ├── Register.jsx        — client-side validation + password hints
│   ├── EventList.jsx       — grid with city filter + pagination
│   ├── EventDetail.jsx     — seat map + booking summary + auto-refresh
│   ├── Queue.jsx           — waiting room with auto-redirect
│   ├── Checkout.jsx        — timer + payment + saga status polling + back button
│   └── MyBookings.jsx      — booking history with event details + cancellation
├── context/
│   └── AuthContext.jsx     — JWT parsing, expiry check, UUID extraction
└── services/
    └── api.js              — Axios with auth interceptor, 401/429 handling, env-based URL
```

**Key UX decisions:**
- Seat map auto-refreshes every 10s so users see real-time availability
- Confirmation dialog before locking (prevents accidental clicks)
- Toast notifications for all async outcomes
- Responsive design — seat map scrolls horizontally on mobile
- 5-minute countdown creates urgency without pressuring unfairly

---

## 11. How to Run & Verify

### Quick Start (No infrastructure needed)
```bash
cd frontend
npm install
node mock-server.js          # Terminal 1 — mock API on :8080
npm run dev                  # Terminal 2 — React on :3000
```

### Test the full flow
1. Login → `test@test.com` / `password`
2. Browse events → click one → interactive seat map
3. Select seats → confirm lock → checkout with timer
4. Pay → watch saga resolve (CONFIRMED or FAILED)
5. My Bookings → see history with event details

### Test distributed locking
```bash
# Terminal 1: Lock seat
curl -X POST http://localhost:8080/seats/lock \
  -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-A1"],"userId":"user1"}'
# → 200 OK

# Terminal 2: Same seat, different user
curl -X POST http://localhost:8080/seats/lock \
  -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-A1"],"userId":"user2"}'
# → 409 Conflict
```

### Test saga compensation
```bash
# Create booking (15% chance of payment failure in mock)
curl -X POST http://localhost:8080/bookings \
  -H "Content-Type: application/json" \
  -d '{"userId":"user1","eventId":"1","seatIds":["1-B3"],"totalAmount":3000}'

# After 2s, check status
curl http://localhost:8080/bookings/{id}/status
# → CONFIRMED (seats booked) or FAILED (seats released)
```

---

## 12. Key Takeaways

1. **Queue absorbs the thundering herd** — downstream services never see spike load
2. **Redis SET NX EX is a distributed lock primitive** — atomic, self-expiring, no coordination needed
3. **Choreography saga via Kafka** — no single coordinator to crash; each service is autonomous
4. **Idempotency keys** — safe retries in an at-least-once delivery world
5. **TTL-based holds** — self-healing system; no abandoned locks, no cleanup jobs
6. **Rate limiting at the gateway** — bots get 429'd before they reach business logic

The system degrades gracefully: if Kafka is slow, bookings queue up but don't fail. If Redis dies, locks fail-safe to "unavailable" (user retries). If payment gateway is down, the circuit breaker stops accepting new bookings rather than locking seats that will time out.
