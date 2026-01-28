# URL Shortener

A Spring Boot service that shortens URLs with Redis caching, persistent storage, rate limiting, and analytics.

---

## How It Works

**Create a short URL** → Service checks if we've seen this URL before. If not, generates a Base62 ID (like "aB3"), stores it in the database, and caches it in Redis for speed. Same URL always gets the same short code.

**Visit a short URL** → Checks Redis cache first (usually instant), falls back to database if needed, and counts the click in analytics.

**Rate limiting** → Only limits URL creation (10 per minute per IP) to prevent spam. Redirects are unlimited so users get the speed they expect.

**If Redis goes down** → Everything still works, just slower. Service is designed to degrade gracefully instead of failing.

---

## Key Design Decisions

### Base62 IDs + Sequential Counters
Auto-incrementing counter stored in Redis, converted to Base62 (`a-z`, `A-Z`, `0-9`). Short, collision-free URLs without needing hashes.

### Idempotent Creation
Hash the input URL. If we've seen it before, return the same short code instead of creating a new one. Prevents wasting IDs on duplicate submissions.

### Cache-Aside Pattern (Redis + Database)
Redis is the fast layer (24-hour TTL), database is the source of truth. On cache miss, we load from DB and repopulate Redis. On creation, we write to both.

### Analytics with Eventual Consistency
Atomic Redis counters for every redirect (super fast, no locks). When you ask for stats, we sync from Redis to the database. We don't need real-time exact counts—eventual consistency is fine for analytics and keeps the hot path blazingly fast.

### Rate Limiting Only on Creation
Creating URLs can be abused to fill the database. Redirects are the core experience and should be unrestricted. This matches real-world services like bit.ly.

---

## API

**Shorten a URL**
```bash
POST http://localhost:8080/shortener
Content-Type: application/json

{"url": "https://example.com/very/long/url"}
```
Returns: `http://localhost:8080/aB3`

**Visit a short URL**
```bash
GET http://localhost:8080/aB3
```
Redirects to original URL and increments click count.

**Get stats**
```bash
GET http://localhost:8080/stats/aB3
```
```json
{
  "shortKey": "aB3",
  "totalClicks": 142,
  "clicksToday": 23,
  "lastAccessedAt": 1737134567890,
  "createdAt": 1737120000000,
  "dataSource": "redis+db"
}
```

**Rate limit error (HTTP 429)**
```json
{
  "error": "Rate Limit Exceeded",
  "message": "Max 10 requests per 60 seconds. Try again in 45 seconds.",
  "retryAfterSeconds": 45
}
```

---

## Architecture

**URLController** → Routes requests to services  
**URLConverterService** → Manages cache-aside logic for URL storage/retrieval  
**RateLimiterService** → Tracks requests per IP using Redis  
**AnalyticsService** → Counts clicks in Redis, syncs to DB on demand  
**URLRepository** → Redis cache layer with failover  
**URLMappingRepository, URLAnalyticsRepository** → JPA database layers  
**IDConverter** → Base62 encoding/decoding  
**URLValidator** → Input validation

---

## Running It

```bash
# Start Redis (optional, service works without it)
redis-server

# Build and run
gradle build
gradle run
```

Server runs on `http://localhost:8080`

---

## Testing

```bash
gradle test
```

Tests cover ID increments, atomic analytics, Redis failures, and database fallback behavior.

---

## Performance

- **Cache hit**: <1ms
- **Cache miss**: 10-30ms (database query)
- **Redis down**: 10-50ms (direct database)
- **Cold start**: 50-100ms

---

## Interview Talking Points

**Why only rate limit creation?** Creating URLs can be abused to fill the database. Redirects are the core experience. Real-world services do the same thing.

**Why eventual consistency for analytics?** Atomic Redis operations are O(1) and under 1ms. Writing to the database on every click would cause contention and slow down redirects. We don't need real-time exact counts.

**What if Redis crashes?** Data loss on recent clicks is acceptable for analytics. We sync periodically, so we only lose clicks since the last sync. URL mappings are always in the database, so nothing critical is lost.

**How does this scale?** Redis `INCR` is atomic and distributed across a cluster. For millions of requests per second, we could add Redis Cluster for sharding, batch writes to the database, or use a time-series database for historical analytics.

**Failure handling?** Service stays up even if Redis is down—we just use the database. We log degraded mode alerts so ops teams know something is wrong.

---

## Stack

- **Framework**: Spring Boot 2.0.1
- **Cache**: Redis (Jedis 2.9.0)
- **Database**: H2
- **ORM**: Spring Data JPA
- **Build**: Gradle
