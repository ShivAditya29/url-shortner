package urlshortener.app.service;

import ai.grakn.redismock.RedisServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import urlshortener.app.model.URLAnalytics;
import urlshortener.app.repository.URLAnalyticsRepository;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AnalyticsServiceTest {
    private static RedisServer server;
    
    @BeforeClass
    public static void setupServer() throws IOException {
        server = RedisServer.newRedisServer(6790);
        server.start();
    }
    
    @AfterClass
    public static void shutdownServer() throws IOException {
        server.stop();
    }
    
    @Test
    public void test_recordClick_incrementsCounterInRedis() {
        Jedis jedis = new Jedis(server.getHost(), server.getBindPort());
        URLAnalyticsRepository mockRepo = mock(URLAnalyticsRepository.class);
        AnalyticsService service = new AnalyticsService(jedis, mockRepo);
        
        String shortKey = "testABC";
        
        // Record 5 clicks
        for (int i = 0; i < 5; i++) {
            service.recordClick(shortKey);
        }
        
        // Verify Redis counter
        String clicksKey = "analytics:" + shortKey + ":clicks";
        String count = jedis.get(clicksKey);
        assertEquals("5", count);
    }
    
    @Test
    public void test_getStats_syncesRedisToDatabase() {
        Jedis jedis = new Jedis(server.getHost(), server.getBindPort());
        URLAnalyticsRepository mockRepo = mock(URLAnalyticsRepository.class);
        
        String shortKey = "testXYZ";
        URLAnalytics analytics = new URLAnalytics(shortKey);
        analytics.setTotalClicks(10L);
        
        when(mockRepo.findById(shortKey)).thenReturn(Optional.of(analytics));
        when(mockRepo.save(any(URLAnalytics.class))).thenReturn(analytics);
        
        AnalyticsService service = new AnalyticsService(jedis, mockRepo);
        
        // Simulate 15 clicks in Redis
        String clicksKey = "analytics:" + shortKey + ":clicks";
        jedis.set(clicksKey, "15");
        
        // Get stats should sync
        Map<String, Object> stats = service.getStats(shortKey);
        
        // Verify synced count
        assertEquals(15L, stats.get("totalClicks"));
        verify(mockRepo, atLeastOnce()).save(any(URLAnalytics.class));
    }
    
    @Test
    public void test_recordClick_fallsBackToDB_whenRedisDown() {
        // Simulate Redis down by using wrong port
        Jedis jedis = new Jedis("localhost", 9999);
        URLAnalyticsRepository mockRepo = mock(URLAnalyticsRepository.class);
        
        String shortKey = "testFallback";
        URLAnalytics analytics = new URLAnalytics(shortKey);
        
        when(mockRepo.findById(shortKey)).thenReturn(Optional.of(analytics));
        when(mockRepo.save(any(URLAnalytics.class))).thenReturn(analytics);
        
        AnalyticsService service = new AnalyticsService(jedis, mockRepo);
        
        // Should fallback to DB
        service.recordClick(shortKey);
        
        // Verify DB was called
        verify(mockRepo, atLeastOnce()).findById(shortKey);
        verify(mockRepo, atLeastOnce()).save(any(URLAnalytics.class));
    }
}
