package urlshortener.app.service;

import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import urlshortener.app.model.URLAnalytics;
import urlshortener.app.repository.URLAnalyticsRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AnalyticsServiceTest {
    private Jedis jedis;
    private URLAnalyticsRepository repository;

    @Before
    public void setUp() {
        jedis = createInMemoryJedis();
        repository = createInMemoryRepository();
    }
    
    @Test
    public void test_recordClick_incrementsCounterInRedis() {
        AnalyticsService service = new AnalyticsService(jedis, repository);
        String shortKey = "testABC";
        
        for (int i = 0; i < 5; i++) {
            service.recordClick(shortKey);
        }

        Map<String, Object> stats = service.getStats(shortKey);
        assertEquals(5L, stats.get("totalClicks"));
    }
    
    @Test
    public void test_getStats_syncesRedisToDatabase() {
        String shortKey = "testXYZ";
        URLAnalytics analytics = new URLAnalytics(shortKey);
        analytics.setTotalClicks(10L);
        repository.save(analytics);

        AnalyticsService service = new AnalyticsService(jedis, repository);
        
        String clicksKey = "analytics:" + shortKey + ":clicks";
        jedis.set(clicksKey, "15");

        Map<String, Object> stats = service.getStats(shortKey);
        assertEquals(15L, stats.get("totalClicks"));
    }
    
    @Test
    public void test_recordClick_fallsBackToDB_whenRedisDown() {
        Jedis jedis = createRedisDownJedis();
        URLAnalyticsRepository repository = createInMemoryRepository();
        String shortKey = "testFallback";

        AnalyticsService service = new AnalyticsService(jedis, repository);
        service.recordClick(shortKey);

        Map<String, Object> stats = service.getStats(shortKey);
        assertEquals(1L, stats.get("totalClicks"));
        assertEquals("db-only", stats.get("dataSource"));
    }

    private URLAnalyticsRepository createInMemoryRepository() {
        Map<String, URLAnalytics> store = new HashMap<>();
        URLAnalyticsRepository repo = mock(URLAnalyticsRepository.class);

        when(repo.findById(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(store.get(invocation.getArgument(0))));

        when(repo.save(any(URLAnalytics.class))).thenAnswer(invocation -> {
            URLAnalytics analytics = invocation.getArgument(0);
            assertNotNull(analytics.getShortKey());
            store.put(analytics.getShortKey(), analytics);
            return analytics;
        });

        return repo;
    }

    private Jedis createInMemoryJedis() {
        Map<String, String> store = new HashMap<>();
        Jedis mockJedis = mock(Jedis.class);

        when(mockJedis.ping()).thenReturn("PONG");

        when(mockJedis.incr(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String current = store.get(key);
            long next = current == null ? 1L : Long.parseLong(current) + 1L;
            store.put(key, String.valueOf(next));
            return next;
        });

        when(mockJedis.set(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            store.put(key, value);
            return "OK";
        });

        when(mockJedis.get(anyString())).thenAnswer(invocation ->
            store.get(invocation.getArgument(0)));

        return mockJedis;
    }

    private Jedis createRedisDownJedis() {
        Jedis mockJedis = mock(Jedis.class);
        when(mockJedis.ping()).thenThrow(new RuntimeException("Redis down"));
        return mockJedis;
    }
}
