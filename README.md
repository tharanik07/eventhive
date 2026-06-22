# EventHive - High-Concurrency Event Ticketing Platform

A distributed event ticketing system with concurrency-safe seat locking, saga-based payment flow, virtual queues, and a React frontend. Demonstrates solutions to double-booking, thundering herd, and distributed transaction problems.

## Architecture

```
React Frontend (:3000) → Mock API Server (:8080)
                              │
                              ├── Auth (register/login/JWT)
                              ├── Events (CRUD + filtering)
                              ├── Seats (mutex locks + TTL)
                              ├── Queue (FIFO + auto-grant)
                              ├── Bookings (saga: PENDING → CONFIRMED/FAILED)
                              └── Payments (simulated + compensation)
```

## Quick Start

```bash
cd frontend
npm install

# Terminal 1: API server
node mock-server.js

# Terminal 2: React frontend
npm run dev
```

- **Frontend:** http://localhost:3000
- **API:** http://localhost:8080
- **Test user:** `test@test.com` / `password`

## End-to-End Testing Guide

### Flow 1: Happy Path Booking

1. Open http://localhost:3000 → Login with `test@test.com` / `password`
2. Click any event card (e.g., "Coldplay")
3. See seat map — prices shown in legend (VIP ₹5000, Premium ₹3000, Regular ₹1500)
4. Click 1-6 seats (they turn purple when selected)
5. Click **Book Now** → seats get locked (5-min TTL)
6. Checkout shows timer counting down + order summary
7. Click **Pay** → saga processes payment (2s)
8. 85% chance: ✅ CONFIRMED (seats marked BOOKED)
9. 15% chance: ❌ FAILED → click **Retry Payment** or **Choose Different Seats**
10. Go to **My Bookings** → see booking with event details, copy ID button

### Flow 2: Double-Booking Prevention

Open two browser tabs on the same event:

```bash
# Or test via curl:
# User A locks seat:
curl -X POST http://localhost:8080/seats/lock \
  -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-A1"],"userId":"user-A"}'
# → 200 OK

# User B tries same seat:
curl -X POST http://localhost:8080/seats/lock \
  -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-A1"],"userId":"user-B"}'
# → 409 "Seat A1 is locked by another user"
```

In the UI: second user sees the seat disappear from available on the next auto-refresh (every 10s) or gets a toast "Seats unavailable" if they try to lock.

### Flow 3: Atomic Rollback (Multi-seat)

```bash
# Lock A1 first
curl -X POST http://localhost:8080/seats/lock \
  -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-A1"],"userId":"user-A"}'

# Try to lock A2 + A1 (A1 already taken) — A2 should NOT get locked
curl -X POST http://localhost:8080/seats/lock \
  -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-A2","1-A1"],"userId":"user-B"}'
# → 409, and A2 remains AVAILABLE (rolled back)
```

### Flow 4: Saga Compensation

```bash
# Lock + Book
curl -X POST http://localhost:8080/seats/lock -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-F1"],"userId":"user-X"}'
curl -X POST http://localhost:8080/bookings -H "Content-Type: application/json" \
  -d '{"userId":"user-X","eventId":"1","seatIds":["1-F1"],"totalAmount":1500}'

# Wait 3s, then check status
sleep 3
curl http://localhost:8080/bookings/{id}/status
# If FAILED → seat 1-F1 automatically released to AVAILABLE
```

### Flow 5: Virtual Queue

1. Navigate to http://localhost:3000/queue/1
2. Click **Join Queue**
3. Watch position countdown + progress bar
4. After ~5s → "YOUR_TURN" → auto-redirect to event page
5. Or click **Leave Queue** to exit

### Flow 6: Lock Expiry

```bash
# Lock a seat
curl -X POST http://localhost:8080/seats/lock -H "Content-Type: application/json" \
  -d '{"eventId":"1","seatIds":["1-G1"],"userId":"lazy-user"}'

# Don't book. Wait 5 minutes (or check after server cleanup runs every 30s)
# Seat auto-releases to AVAILABLE
```

In the UI: if the checkout timer hits 0:00, seats are explicitly released via API call and user is redirected home.

### Flow 7: Booking Cancellation

1. Go to **My Bookings**
2. Click **Cancel** on a CONFIRMED booking
3. Booking status → CANCELLED, seats released back to AVAILABLE, refund initiated

## Concurrency Guarantees

| Scenario | Protection | Result |
|----------|-----------|--------|
| Two users lock same seat | Per-seat mutex + atomic check | Exactly one wins |
| Multi-seat lock partial failure | All-or-nothing with rollback | No orphan locks |
| Payment fails mid-saga | Compensation releases all seats | No stuck inventory |
| User abandons checkout | 5-min TTL auto-expiry | Seats return to pool |
| Same user re-locks | Idempotent — extends TTL | No error |
| Booking without lock ownership | Validated before creation | 409 rejected |
| Duplicate booking attempt | Checked by userId+seatIds | 409 rejected |
| Cancel non-confirmed booking | State machine enforced | 400 rejected |

## Edge Cases Handled

- Empty seat array → 400
- More than 6 seats → 400
- Invalid event ID → 404
- Seat doesn't exist → 404
- Expired lock treated as AVAILABLE on read
- Race condition test endpoint: `POST /test/race-condition`

## Frontend Features

| Feature | Implementation |
|---------|---------------|
| Error boundary | Catches React crashes, shows recovery UI |
| Toast notifications | Success/error/info with slide-in animation |
| Loading skeletons | Pulse-animated placeholders |
| Responsive navbar | Hamburger menu on mobile |
| Seat map | Color-coded by category, price legend, responsive scroll |
| Booking timer | 5-min countdown, releases seats on expiry |
| Queue status | Real-time position polling, progress bar, leave button |
| Auto-refresh | Seat map refreshes every 10s |
| Retry payment | Re-locks seats and retries on failure |
| Copy booking ID | Clipboard copy in My Bookings |
| Sold out overlay | Disabled event cards with "SOLD OUT" badge |
| Page animations | Fade-in on route changes |
| JWT management | Parse UUID from token, expiry check on load |

## Project Structure

```
EventHive/
├── pom.xml                    # Parent Maven POM (8 microservices)
├── docker-compose.yml         # Full stack (PostgreSQL, Redis, Kafka)
├── init.sql                   # Database schema
├── DESIGN.md                  # System design write-up
├── user-service/              # JWT auth
├── event-service/             # Event CRUD
├── seat-service/              # Redis distributed locks
├── queue-service/             # Virtual queue (Redis sorted sets)
├── booking-service/           # Saga orchestration via Kafka
├── payment-service/           # Idempotent payments
├── notification-service/      # Event-driven notifications
├── api-gateway/               # Spring Cloud Gateway + rate limiting
└── frontend/
    ├── mock-server.js         # Concurrency-safe API (no infra needed)
    ├── .env                   # VITE_API_URL config
    ├── src/components/        # ErrorBoundary, Navbar, SeatMap, BookingTimer, QueueStatus, Toast
    ├── src/pages/             # Login, Register, EventList, EventDetail, Queue, Checkout, MyBookings
    ├── src/context/           # AuthContext (JWT parsing + expiry)
    └── src/services/          # Axios with interceptors
```

## Full Stack Mode (Docker)

For running all 8 Spring Boot services with real PostgreSQL, Redis, and Kafka:

```bash
mvn clean package -DskipTests
docker compose up --build
```

## Tech Stack

**Backend:** Java 17, Spring Boot 3.2, Spring Cloud Gateway, Spring Kafka, Spring Data Redis/JPA  
**Frontend:** React 18, Vite, Tailwind CSS, React Router, Axios  
**Data:** PostgreSQL 15, Redis 7, Apache Kafka  
**Patterns:** Saga (choreography), Distributed Locks, Virtual Queue, Rate Limiting, Idempotency, TTL-based holds

## Future Improvements

### Must Do (before demo/interview)

- [ ] **Remove Lombok** from all backend services (Java 25 incompatibility) — replace with plain getters/setters
- [ ] **Add `docker-compose.dev.yml`** for local infra only (PostgreSQL + Redis + Kafka) to run services via `mvn spring-boot:run`
- [ ] **Add integration test** — Testcontainers test proving two users can't lock the same seat

### Should Do (makes it stand out)

- [ ] **Prometheus + Grafana** — add `micrometer-registry-prometheus` + custom metrics (`bookings.created`, `seats.lock.duration`)
- [ ] **Resilience4j circuit breaker** — wrap payment gateway call, fallback to FAILED after 3 consecutive failures
- [ ] **Kafka consumer integration test** — verify saga completes end-to-end through Kafka topics
- [ ] **Swagger/OpenAPI docs** — `springdoc-openapi` auto-generated API docs at `/swagger-ui.html`

### Nice to Have

- [ ] **GitHub Actions CI** — `.github/workflows/build.yml` that compiles all services on push
- [ ] **Elasticsearch** for event search with full-text + geo filtering
- [ ] **WebSocket** for real-time queue position (replace polling)
- [ ] **Kubernetes manifests** with HPA for auto-scaling under load
- [ ] **Gatling load test** script simulating 1000 concurrent booking attempts
- [ ] **Dead Letter Queue** consumer that retries/alerts on failed saga events
