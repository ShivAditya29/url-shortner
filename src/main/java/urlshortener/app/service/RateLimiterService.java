package urlshortener.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

/**
 * Rate Limiter Service using Token Bucket Algorithm
 * 
 * Implements rate limiting using Redis with sliding window counters
 * to prevent abuse on URL creation endpoint.
 * 
 * Algorithm: Fixed Window Counter (simple, efficient)
 * - Each IP gets a counter in Redis
 * - Counter resets after window expires (TTL)
 * - If counter >= limit, request is rejected
 * 
 * Alternative algorithms (mentioned for interviews):
 * - Token Bucket: More smooth, allows bursts
 * - Sliding Window Log: More accurate, higher memory
 * - Sliding Window Counter: Hybrid approach
 */
@Service
public class RateLimiterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimiterService.class);
    
    private final Jedis jedis;
    private boolean redisAvailable = true;
    
    // Rate limit configuration
    private static final int MAX_REQUESTS_PER_WINDOW = 10; // 10 requests
    private static final int WINDOW_SIZE_SECONDS = 60; // per minute
    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    
    public RateLimiterService() {
        this.jedis = new Jedis();
        checkRedisHealth();
    }
    
    public RateLimiterService(Jedis jedis) {
        this.jedis = jedis;
        checkRedisHealth();
    }
    
    private void checkRedisHealth() {
        try {
            jedis.ping();
            redisAvailable = true;
            LOGGER.info("RateLimiter: Redis connection healthy");
        } catch (Exception e) {
            redisAvailable = false;
            LOGGER.warn("RateLimiter: Redis unavailable, rate limiting disabled: {}", e.getMessage());
        }
    }
    
    /**
     * Check if request is allowed under rate limit
     * 
     * @param identifier IP address or API key
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean isAllowed(String identifier) {
        if (!redisAvailable) {
            LOGGER.warn("Redis down, allowing request (degraded mode)");
            return true; // Fail open - allow requests when Redis is down
        }
        
        String key = RATE_LIMIT_PREFIX + identifier;
        
        try {
            // Get current count
            String countStr = jedis.get(key);
            Long currentCount = (countStr != null) ? Long.parseLong(countStr) : 0L;
            
            if (currentCount >= MAX_REQUESTS_PER_WINDOW) {
                LOGGER.warn("Rate limit exceeded for {}: {}/{} requests", 
                    identifier, currentCount, MAX_REQUESTS_PER_WINDOW);
                return false;
            }
            
            // Increment counter
            Long newCount = jedis.incr(key);
            
            // Set TTL only on first request (when counter = 1)
            if (newCount == 1) {
                jedis.expire(key, WINDOW_SIZE_SECONDS);
                LOGGER.info("Started new rate limit window for {}: {}/{} requests", 
                    identifier, newCount, MAX_REQUESTS_PER_WINDOW);
            } else {
                LOGGER.info("Rate limit check for {}: {}/{} requests", 
                    identifier, newCount, MAX_REQUESTS_PER_WINDOW);
            }
            
            return true;
            
        } catch (Exception e) {
            LOGGER.error("Rate limiter error: {}, allowing request", e.getMessage());
            redisAvailable = false;
            return true; // Fail open
        }
    }
    
    /**
     * Get remaining requests for an identifier
     */
    public int getRemainingRequests(String identifier) {
        if (!redisAvailable) {
            return MAX_REQUESTS_PER_WINDOW;
        }
        
        try {
            String key = RATE_LIMIT_PREFIX + identifier;
            String countStr = jedis.get(key);
            Long currentCount = (countStr != null) ? Long.parseLong(countStr) : 0L;
            return Math.max(0, (int)(MAX_REQUESTS_PER_WINDOW - currentCount));
        } catch (Exception e) {
            LOGGER.error("Error getting remaining requests: {}", e.getMessage());
            return MAX_REQUESTS_PER_WINDOW;
        }
    }
    
    /**
     * Get time until rate limit window resets (in seconds)
     */
    public long getResetTime(String identifier) {
        if (!redisAvailable) {
            return 0;
        }
        
        try {
            String key = RATE_LIMIT_PREFIX + identifier;
            Long ttl = jedis.ttl(key);
            return (ttl != null && ttl > 0) ? ttl : 0;
        } catch (Exception e) {
            LOGGER.error("Error getting reset time: {}", e.getMessage());
            return 0;
        }
    }
    
    public int getMaxRequests() {
        return MAX_REQUESTS_PER_WINDOW;
    }
    
    public int getWindowSize() {
        return WINDOW_SIZE_SECONDS;
    }
}
