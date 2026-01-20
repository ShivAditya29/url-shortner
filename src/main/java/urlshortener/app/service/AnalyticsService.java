package urlshortener.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import urlshortener.app.model.URLAnalytics;
import urlshortener.app.repository.URLAnalyticsRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Analytics Service implementing hybrid Redis + DB pattern
 * 
 * DESIGN DECISIONS:
 * 
 * 1. Redis for Hot Path (Atomic Increments)
 *    - Uses INCR for atomic counter updates (O(1), thread-safe)
 *    - Stores in-memory for ultra-fast increments on redirects
 *    - Key format: "analytics:{shortKey}:clicks"
 * 
 * 2. Database for Durability (Source of Truth)
 *    - Periodic sync from Redis to DB (async background job in production)
 *    - Current implementation: sync on stats read (lazy persistence)
 *    - Provides historical data, aggregations
 * 
 * 3. Eventual Consistency Model
 *    - Redis counters may be ahead of DB
 *    - Acceptable tradeoff: fast writes > immediate consistency
 *    - Stats endpoint returns latest from Redis + DB baseline
 * 
 * INTERVIEW DISCUSSION POINTS:
 * - Why not just DB? → Too slow for high-traffic redirects
 * - Why not just Redis? → No durability, data loss on restart
 * - Eventual consistency: Business acceptable for analytics
 * - Alternative: Kafka/message queue for async DB writes
 */
@Service
public class AnalyticsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsService.class);
    
    private final Jedis jedis;
    private final URLAnalyticsRepository analyticsRepository;
    private boolean redisAvailable = true;
    
    private static final String ANALYTICS_CLICKS_PREFIX = "analytics:";
    private static final String CLICKS_SUFFIX = ":clicks";
    private static final String LAST_ACCESS_SUFFIX = ":lastAccess";
    
    @Autowired
    public AnalyticsService(URLAnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
        this.jedis = new Jedis();
        checkRedisHealth();
    }
    
    public AnalyticsService(Jedis jedis, URLAnalyticsRepository analyticsRepository) {
        this.jedis = jedis;
        this.analyticsRepository = analyticsRepository;
        checkRedisHealth();
    }
    
    private void checkRedisHealth() {
        try {
            jedis.ping();
            redisAvailable = true;
            LOGGER.info("Analytics: Redis connection healthy");
        } catch (Exception e) {
            redisAvailable = false;
            LOGGER.warn("Analytics: Redis unavailable, will use DB only: {}", e.getMessage());
        }
    }
    
    /**
     * Record a click/redirect event
     * Fast path: Atomic increment in Redis
     * 
     * @param shortKey The short URL identifier
     */
    public void recordClick(String shortKey) {
        long timestamp = System.currentTimeMillis();
        
        // Fast path: Redis atomic increment
        if (redisAvailable) {
            try {
                String clicksKey = ANALYTICS_CLICKS_PREFIX + shortKey + CLICKS_SUFFIX;
                String lastAccessKey = ANALYTICS_CLICKS_PREFIX + shortKey + LAST_ACCESS_SUFFIX;
                
                Long newCount = jedis.incr(clicksKey);
                jedis.set(lastAccessKey, String.valueOf(timestamp));
                
                LOGGER.info("[ANALYTICS] Recorded click for {} in Redis: count={}", shortKey, newCount);
                return;
            } catch (Exception e) {
                LOGGER.error("Failed to record click in Redis: {}", e.getMessage());
                redisAvailable = false;
                // Fall through to DB recording
            }
        }
        
        // Fallback: Direct DB write (slower but reliable)
        LOGGER.info("[ANALYTICS] Redis unavailable, recording click in DB for {}", shortKey);
        recordClickInDB(shortKey, timestamp);
    }
    
    /**
     * Direct DB recording (fallback when Redis is down)
     */
    private void recordClickInDB(String shortKey, long timestamp) {
        try {
            Optional<URLAnalytics> existing = analyticsRepository.findById(shortKey);
            URLAnalytics analytics;
            
            if (existing.isPresent()) {
                analytics = existing.get();
                analytics.setTotalClicks(analytics.getTotalClicks() + 1);
                analytics.setLastAccessedAt(timestamp);
                updateDailyClicks(analytics);
            } else {
                analytics = new URLAnalytics(shortKey);
                analytics.setTotalClicks(1L);
                analytics.setLastAccessedAt(timestamp);
                analytics.setClicksToday(1L);
            }
            
            analyticsRepository.save(analytics);
            LOGGER.info("[ANALYTICS] Recorded click in DB for {}: total={}", shortKey, analytics.getTotalClicks());
        } catch (Exception e) {
            LOGGER.error("Failed to record click in DB: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Get analytics stats for a short URL
     * Syncs Redis counters to DB (lazy persistence)
     * 
     * @param shortKey The short URL identifier
     * @return Analytics statistics
     */
    public Map<String, Object> getStats(String shortKey) {
        LOGGER.info("[ANALYTICS] Fetching stats for {}", shortKey);
        
        // Step 1: Get baseline from DB
        URLAnalytics dbAnalytics = analyticsRepository.findById(shortKey)
            .orElse(new URLAnalytics(shortKey));
        
        long totalClicks = dbAnalytics.getTotalClicks();
        long lastAccessedAt = dbAnalytics.getLastAccessedAt();
        
        // Step 2: Check Redis for latest counts (eventual consistency)
        if (redisAvailable) {
            try {
                String clicksKey = ANALYTICS_CLICKS_PREFIX + shortKey + CLICKS_SUFFIX;
                String lastAccessKey = ANALYTICS_CLICKS_PREFIX + shortKey + LAST_ACCESS_SUFFIX;
                
                String redisClicks = jedis.get(clicksKey);
                String redisLastAccess = jedis.get(lastAccessKey);
                
                if (redisClicks != null) {
                    long redisCount = Long.parseLong(redisClicks);
                    
                    // Sync to DB if Redis has newer data
                    if (redisCount > totalClicks) {
                        LOGGER.info("[ANALYTICS] Syncing Redis to DB: {} clicks", redisCount);
                        dbAnalytics.setTotalClicks(redisCount);
                        totalClicks = redisCount;
                    }
                }
                
                if (redisLastAccess != null) {
                    long redisTimestamp = Long.parseLong(redisLastAccess);
                    if (redisTimestamp > lastAccessedAt) {
                        dbAnalytics.setLastAccessedAt(redisTimestamp);
                        lastAccessedAt = redisTimestamp;
                    }
                }
                
                // Persist synced data
                analyticsRepository.save(dbAnalytics);
                
            } catch (Exception e) {
                LOGGER.error("Failed to sync Redis analytics: {}", e.getMessage());
                redisAvailable = false;
            }
        }
        
        // Step 3: Build response
        Map<String, Object> stats = new HashMap<>();
        stats.put("shortKey", shortKey);
        stats.put("totalClicks", totalClicks);
        stats.put("lastAccessedAt", lastAccessedAt);
        stats.put("lastAccessedDate", formatTimestamp(lastAccessedAt));
        stats.put("createdAt", dbAnalytics.getCreatedAt());
        stats.put("createdDate", formatTimestamp(dbAnalytics.getCreatedAt()));
        stats.put("clicksToday", dbAnalytics.getClicksToday());
        stats.put("dataSource", redisAvailable ? "redis+db" : "db-only");
        
        LOGGER.info("[ANALYTICS] Stats for {}: totalClicks={}, source={}", 
            shortKey, totalClicks, stats.get("dataSource"));
        
        return stats;
    }
    
    /**
     * Initialize analytics for a new short URL
     */
    public void initializeAnalytics(String shortKey) {
        URLAnalytics analytics = new URLAnalytics(shortKey);
        analyticsRepository.save(analytics);
        LOGGER.info("[ANALYTICS] Initialized analytics for {}", shortKey);
    }
    
    /**
     * Update daily click aggregation
     */
    private void updateDailyClicks(URLAnalytics analytics) {
        String today = LocalDate.now().toString();
        String lastDate = analytics.getLastAggregationDate();
        
        if (!today.equals(lastDate)) {
            // New day - reset daily counter
            analytics.setClicksToday(1L);
            analytics.setLastAggregationDate(today);
            LOGGER.info("[ANALYTICS] Reset daily counter for {}", analytics.getShortKey());
        } else {
            // Same day - increment
            analytics.setClicksToday(analytics.getClicksToday() + 1);
        }
    }
    
    private String formatTimestamp(long timestamp) {
        if (timestamp == 0) {
            return "never";
        }
        return new java.util.Date(timestamp).toString();
    }
}
