# URL Shortener

## A Production-Ready Java Service with Explicit Cache-Aside Pattern

This URL shortener implements industry-standard caching patterns with **Redis as cache** and **H2 database as the source of truth**, featuring graceful degradation and failure handling.

---

## 🏗️ System Design Highlights

### **Cache-Aside Pattern (Lazy Loading)**
The system implements an **explicit cache-aside strategy** for optimal performance and reliability:

#### **On URL Creation:**
1. **Check Cache** (Redis) for existing URL hash
2. **Cache Miss** → Query Database
3. **DB Miss** → Generate new ID, save to DB, populate cache with TTL
4. **DB Hit** → Repopulate cache from DB

#### **On URL Redirect:**
1. **Try Cache** (Redis) first
2. **Cache Hit** → Return immediately (fast path)
3. **Cache Miss** → Query Database
4. **Repopulate Cache** with 24-hour TTL

### **Failure Handling & Graceful Degradation**

#### **Redis Down Scenario:**
- ✅ Service remains operational using database
- ✅ Auto-detects Redis unavailability via health checks
- ✅ Falls back to DB-generated IDs when Redis counter fails
- ✅ Logs degraded mode warnings
- ⚠️ Performance impact: ~10-50ms additional latency per request

#### **Database Slow Scenario:**
- ✅ Cache layer shields from DB slowness
- ✅ Cache hits (hot URLs) served at <1ms
- ✅ Only cache misses experience DB latency
- ✅ TTL prevents infinite cache growth

#### **Both Down Scenario:**
- ❌ Service returns errors (expected behavior)
- 💡 Recommendation: Implement circuit breaker pattern for production

---

## 📊 Key Features

### **1. Base62 Encoding + Sequential IDs**
- Auto-incrementing IDs from Redis (`INCR` command)
- Converted to Base62: `a-z`, `A-Z`, `0-9` (62 characters)
- Short, collision-free URLs: `/a`, `/b`, ..., `/aB3`

### **2. Idempotent URL Creation**
- Same long URL always returns the same short URL
- Uses SHA-256 hash for deduplication
- Prevents counter waste on duplicate submissions

### **3. Two-Tier Storage Architecture**
- **Redis (L1 Cache)**: Fast lookups, 24-hour TTL
- **H2 Database (L2 Source of Truth)**: Persistent storage
- Write-through on creation, lazy-load on reads

### **4. Rate Limiting on URL Creation**
- **Fixed Window Counter** algorithm using Redis
- Limits: **10 requests per 60 seconds** per IP address
- Applied ONLY to `/shortener` POST endpoint
- Redirects are NOT rate limited (fast, unrestricted for end users)
- **Fail-open strategy**: If Redis is down, allows requests (prevents denial of service)
- Standard HTTP 429 responses with retry-after headers

**Why Rate Limit Only Creation?**
- Prevents spam/abuse of URL generation
- Redirects should be fast and unrestricted (core user experience)
- Real-world requirement for public APIs
- Interview-friendly design decision

### **5. First-Class Analytics**
- **Hybrid Redis + Database** architecture for click tracking
- **Atomic increments** using Redis `INCR` (thread-safe, O(1))
- **Eventual consistency** model: Redis fast writes → periodic DB sync
- Tracks: Total clicks, last accessed timestamp, daily aggregation
- **Lazy persistence**: Stats reads trigger Redis-to-DB sync
- Exposed via `GET /stats/{shortKey}` endpoint

**Metrics Tracked**:
- `totalClicks`: Lifetime click count
- `lastAccessedAt`: Unix timestamp of last redirect
- `clicksToday`: Rolling daily counter
- `createdAt`: URL creation timestamp

**Design Rationale**:
- **Not just `count++`**: Atomic Redis counters prevent race conditions
- **Redis for hot path**: Ultra-fast increments on every redirect
- **DB for durability**: Survives Redis restarts, enables historical queries
- **Eventual consistency acceptable**: Analytics don't need immediate consistency

### **6. Observability**
- Explicit logging for cache HIT/MISS events
- DB HIT/MISS tracking
- Degraded mode alerts
- Redis health monitoring
- Rate limit tracking per IP
- Analytics data source tracking (redis+db vs db-only)

---

## 🔧 Architecture Components

### **controller**
**URLController.java**  
- POST `/shortener`: Accepts JSON with URL, returns shortened version (rate limited)
- GET `/{id}`: Redirects to original URL (NOT rate limited, records analytics)
- GET `/stats/{id}`: Retrieve analytics for a shortened URL
- Extracts client IP from X-Forwarded-For header (proxy-aware)

**GlobalExceptionHandler.java**  
- Handles rate limit exceptions with HTTP 429 responses
- Returns standard rate limit headers (X-RateLimit-*, Retry-After)

### **exception**
**RateLimitExceededException.java**  
Custom exception for rate limit violations with metadata

### **service**
**URLConverterService.java**  
Implements cache-aside pattern:
- `shortenURL()`: Check cache → Check DB → Create new (with cache population)
- `getLongURLFromID()`: Try cache → Fallback to DB → Repopulate cache

**RateLimiterService.java**  
Implements rate limiting using Redis:
- Fixed window counter algorithm
- Per-IP tracking with TTL-based windows
- Configurable limits (10 requests/minute default)
- Fail-open on Redis failures

**AnalyticsService.java**  
Hybrid Redis + Database analytics:
- Atomic click tracking with Redis `INCR`
- Lazy persistence: sync on stats reads
- Daily aggregation with rolling counters
- Eventual consistency model

### **repository**
**URLRepository.java** (Redis Cache Layer)
- Auto-detects Redis health
- Safe operations with try-catch fallback
- Sets TTL on all cached entries (24 hours)
- Returns `null` on failures (triggers DB fallback)

**URLMappingRepository.java** (JPA Database Layer)
- Source of truth for all URL mappings
- Provides `findByShortKey()` and `findByUrlHash()`

**URLAnalyticsRepository.java** (JPA Analytics Storage)
- Persistent storage for analytics data
- Synced from Redis periodically

### **model**
**URLMapping.java**  
JPA entity for persistent storage with fields:
- `id`: Auto-generated sequence
- `shortKey`: Base62 encoded ID
- `longUrl`: Original URL
- `urlHash`: SHA-256 hash for idempotency

**URLAnalytics.java**  
JPA entity for analytics with fields:
- `shortKey`: Primary key (URL identifier)
- `totalClicks`: Lifetime click count
- `lastAccessedAt`: Last redirect timestamp
- `clicksToday`: Daily aggregated clicks
- `lastAggregationDate`: Date of last daily reset

### **exception**
**RateLimitExceededException.java**  
Custom exception for rate limit violations with metadata

### **common**  
Implements rate limiting using Redis:
- Fixed window counter algorithm
- Per-IP tracking with TTL-based windows
- Configurable limits (10 requests/minute default)
- Fail-open on Redis failures

**AnalyticsService.java**  
Hybrid Redis + Database analytics:
- Atomic click tracking with Redis `INCR`
- Lazy persistence: sync on stats reads
- Daily aggregation with rolling counters
- Eventual consistency model

### **repository**
**URLRepository.java** (Redis Cache Layer)
- Auto-detects Redis health
- Safe operations with try-catch fallback
- Sets TTL on all cached entries (24 hours)
- Returns `null` on failures (triggers DB fallback)

**URLMappingRepository.java** (JPA Database Layer)
- Source of truth for all URL mappings
- Provides `findByShortKey()` and `findByUrlHash()`

### **model**
**URLMapping.java**  
JPA entity for persistent storage with fields:
- `id`: Auto-generated sequence
- `shortKey`: Base62 encoded ID
- `longUrl`: Original URL
- `urlHash`: SHA-256 hash for idempotency

### **common**
**IDConverter.java**  
Base62 encoding/decoding for compact URLs

**URLValidator.java**  
Regex-based URL validation

---

## 🚀 How to Run

### **1. Start Redis Server (Optional)**
```bash
redis-server
```
*Note: Service works without Redis in degraded mode*

### **2. Build the Project**
```bash
gradle build
```

### **3. Run the Application**
```bash
gradle run
```

Server runs on: `http://localhost:8080`

---

## 📡 API Usage

### **Shorten a URL**
```bash
POST http://localhost:8080/shortener
Content-Type: application/json

{
  "url": "https://example.com/very/long/url"
}
```

**Response (Success):**
```
http://localhost:8080/aB3
```

**Response (Rate Limit Exceeded - HTTP 429):**
```json
{
  "error": "Rate Limit Exceeded",
  "message": "Rate limit exceeded. Max 10 requests per 60 seconds. Try again in 45 seconds.",
  "maxRequests": 10,
  "windowSeconds": 60,
  "retryAfterSeconds": 45
}
```

**Rate Limit Headers:**
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1737201234
Retry-After: 45
```

### **Access Shortened URL (NOT Rate Limited)**
```bash
GET http://localhost:8080/aB3
```
→ Redirects to `https://example.com/very/long/url`  
*(Click is automatically tracked in analytics)*

### **Get Analytics Stats**
```bash
GET http://localhost:8080/stats/aB3
```

**Response:**
```json
{
  "shortKey": "aB3",
  "totalClicks": 142,
  "lastAccessedAt": 1737134567890,
  "lastAccessedDate": "Fri Jan 17 15:30:45 PST 2026",
  "createdAt": 1737120000000,
  "createdDate": "Fri Jan 17 11:20:00 PST 2026",
  "clicksToday": 23,
  "dataSource": "redis+db"
}
```

**Field Descriptions**:
- `totalClicks`: Lifetime total redirects
- `lastAccessedAt`: Unix timestamp (milliseconds) of last click
- `lastAccessedDate`: Human-readable last access time
- `createdAt`/`createdDate`: When URL was created
- `clicksToday`: Clicks since midnight (rolling daily counter)
- `dataSource`: Where data came from (`redis+db` or `db-only`)

---

## 📈 Performance Characteristics

| Scenario | Latency | Notes |
|----------|---------|-------|
| Cache Hit (Redis up) | <1ms | 99% of hot URLs |
| Cache Miss (Redis up) | 10-30ms | DB query + cache write |
| Redis Down | 10-50ms | Direct DB queries |
| Cold Start | 50-100ms | First request after TTL expiry |

---

## 🧪 Testing

The project includes comprehensive unit tests:

```bash
gradle test
```

**Test Coverage**:
- **URLRepositoryTest.java**: Tests ID increment with Redis Mock
- **AnalyticsServiceTest.java**: 
  - Atomic click recording in Redis
  - Redis-to-DB sync on stats retrieval
  - Fallback to DB when Redis is unavailable
  - Eventual consistency validation

---

## 🎯 System Design Interview Talking Points

✅ **Cache-Aside Pattern**: Explicit implementation with TTL and repopulation  
✅ **Failure Handling**: Redis failures don't bring down the service  
✅ **Graceful Degradation**: DB fallback when cache unavailable  
✅ **Idempotency**: Same URL → same short code  
✅ **Scalability**: Base62 supports 62^7 = 3.5 trillion URLs with 7 characters  
✅ **Observability**: Structured logging for cache/DB hits and failures  
✅ **Rate Limiting**: Per-IP limits on creation (NOT redirects) - prevents abuse  
✅ **Fail-Open Strategy**: Rate limiter allows requests when Redis is down  
✅ **Standard HTTP Headers**: X-RateLimit-* and Retry-After for client guidance  
✅ **Analytics First-Class**: Atomic Redis counters + eventual DB sync  
✅ **Eventual Consistency**: Analytics acceptable tradeoff for performance  

**🎤 Interview Discussion Points:**

**"Why rate limit only creation, not redirects?"**
- Creation can be abused to fill up database
- Redirects are the core user experience - should be fast & unrestricted
- Real-world services (bit.ly, tinyurl) follow same pattern

**"What algorithm did you use for rate limiting?"**
- Fixed Window Counter (simple, efficient, O(1))
- Alternatives: Token Bucket (smoother), Sliding Window Log (accurate but O(n))
- Chose Fixed Window for simplicity and low memory overhead

**"How do you handle distributed rate limiting?"**
- Redis provides atomic operations (INCR) - naturally distributed
- Multiple app servers can share same Redis instance
- For global scale: Redis Cluster with consistent hashing

**"What happens if someone uses VPN/proxies to bypass IP limits?"**
- Future enhancement: API key-based rate limiting
- Device fingerprinting
- CAPTCHA after repeated violations
- Behavioral analysis

**"Why not store analytics directly in the database?"**
- **Performance**: DB writes are slow (10-50ms), would slow redirects
- **Contention**: Every redirect would lock DB row (serialization bottleneck)
- **Scalability**: Millions of redirects/sec need O(1) atomic operations
- **Solution**: Redis `INCR` is atomic, in-memory, <1ms

**"What about eventual consistency in analytics?"**
- **Acceptable for analytics**: Users don't need real-time exact counts
- **Business tradeoff**: Speed > consistency for click tracking
- **Sync strategy**: Lazy persistence on stats reads
- **Production alternative**: Background job syncs every N minutes

**"What if Redis crashes before syncing to DB?"**
- **Data loss**: Some click counts lost (acceptable for analytics)
- **Mitigation**: Redis persistence (RDB snapshots, AOF logging)
- **Critical data**: URL mappings always in DB (source of truth)
- **Recovery**: Resume from last DB count, continue incrementing

**"How would you handle millions of redirects per second?"**
- **Read-heavy workload**: Perfect for caching
- **Redis Cluster**: Sharded counters across nodes
- **Write batching**: Aggregate in Redis, bulk write to DB
- **Time-series DB**: For historical analytics (InfluxDB, TimescaleDB)

---

## 💡 Production Enhancements (Future Work)

- [ ] Circuit breaker pattern (Hystrix/Resilience4j)
- [ ] Distributed caching (Redis Cluster)
- [ ] API key-based authentication and rate limiting
- [ ] Token bucket or sliding window rate limiter
- [ ] Rate limit per API key (separate from IP limits)
- [ ] Analytics tracking (click counts, geolocation)
- [ ] Custom short codes (vanity URLs)
- [ ] Expiration policies for old URLs
- [ ] Multi-region replication
- [ ] CAPTCHA integration for abuse prevention
- [ ] WebSocket for real-time analytics

---

## 🛠️ Technology Stack

- **Framework**: Spring Boot 2.0.1
- **Cache**: Redis (Jedis client 2.9.0)
- **Database**: H2 (embedded, file-based)
- **ORM**: Spring Data JPA
- **Build**: Gradle
- **Testing**: JUnit with Redis Mock

