package urlshortener.app.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;

@Repository
public class URLRepository {
    private final Jedis jedis;
    private final String idKey;
    private final String urlKey;
    private static final Logger LOGGER = LoggerFactory.getLogger(URLRepository.class);
    private static final int CACHE_TTL_SECONDS = 86400; // 24 hours
    private boolean redisAvailable = true;

    public URLRepository() {
        this.jedis = new Jedis();
        this.idKey = "id";
        this.urlKey = "url:";
        checkRedisHealth();
    }

    public URLRepository(Jedis jedis, String idKey, String urlKey) {
        this.jedis = jedis;
        this.idKey = idKey;
        this.urlKey = urlKey;
        checkRedisHealth();
    }

    private void checkRedisHealth() {
        try {
            jedis.ping();
            redisAvailable = true;
            LOGGER.info("Redis connection healthy");
        } catch (Exception e) {
            redisAvailable = false;
            LOGGER.warn("Redis unavailable, falling back to DB only: {}", e.getMessage());
        }
    }

    public Long incrementID() {
        try {
            Long id = jedis.incr(idKey);
            LOGGER.info("Incrementing ID: {}", id-1);
            return id - 1;
        } catch (Exception e) {
            LOGGER.error("Redis incrementID failed: {}", e.getMessage());
            redisAvailable = false;
            // Return null to signal fallback to DB sequence
            return null;
        }
    }

    public void saveUrl(String key, String longUrl) {
        if (!redisAvailable) {
            LOGGER.warn("Redis unavailable, skipping cache write");
            return;
        }
        try {
            LOGGER.info("Saving to cache: {} at {} with TTL {}s", longUrl, key, CACHE_TTL_SECONDS);
            jedis.hset(urlKey, key, longUrl);
            // Set TTL on the hash key for cache expiration
            jedis.expire(urlKey, CACHE_TTL_SECONDS);
        } catch (Exception e) {
            LOGGER.error("Failed to save URL to cache: {}", e.getMessage());
            redisAvailable = false;
        }
    }

    public String getUrl(Long id) {
        if (!redisAvailable) {
            LOGGER.warn("Redis unavailable, returning null for cache miss");
            return null;
        }
        try {
            LOGGER.info("Retrieving from cache at {}", id);
            String url = jedis.hget(urlKey, "url:"+id);
            if (url != null) {
                LOGGER.info("Cache HIT: Retrieved {} at {}", url, id);
            } else {
                LOGGER.info("Cache MISS at {}", id);
            }
            return url;
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve from cache: {}", e.getMessage());
            redisAvailable = false;
            return null;
        }
    }

    public void saveUrlHash(String hash, String shortKey) {
        if (!redisAvailable) {
            LOGGER.warn("Redis unavailable, skipping hash cache write");
            return;
        }
        try {
            LOGGER.info("Saving hash mapping to cache: {} -> {}", hash, shortKey);
            jedis.hset("hash:", hash, shortKey);
            jedis.expire("hash:", CACHE_TTL_SECONDS);
        } catch (Exception e) {
            LOGGER.error("Failed to save hash mapping: {}", e.getMessage());
            redisAvailable = false;
        }
    }

    public String getShortKeyFromHash(String hash) {
        if (!redisAvailable) {
            LOGGER.warn("Redis unavailable, returning null for hash lookup");
            return null;
        }
        try {
            LOGGER.info("Checking cache for existing hash: {}", hash);
            String shortKey = jedis.hget("hash:", hash);
            if (shortKey != null) {
                LOGGER.info("Cache HIT: Found existing short key for hash: {}", shortKey);
            } else {
                LOGGER.info("Cache MISS: No short key found for hash");
            }
            return shortKey;
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve hash from cache: {}", e.getMessage());
            redisAvailable = false;
            return null;
        }
    }

    public boolean isRedisAvailable() {
        return redisAvailable;
    }
}
