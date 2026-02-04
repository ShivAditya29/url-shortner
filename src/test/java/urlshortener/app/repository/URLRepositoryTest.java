package urlshortener.app.repository;

import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class URLRepositoryTest {
    private Jedis jedis;
    private URLRepository urlRepository;

    @Before
    public void setUp() {
        AtomicLong counter = new AtomicLong(0L);
        jedis = mock(Jedis.class);
        when(jedis.ping()).thenReturn("PONG");
        when(jedis.incr(anyString())).thenAnswer(invocation -> counter.incrementAndGet());
        urlRepository = new URLRepository(jedis, "id", "url:");
    }

    @Test
    public void test_incrementID_StartsAt0AndIncrements() {
        for (long expectedId = 0L; expectedId < 50L; ++expectedId) {
            long actualId = urlRepository.incrementID();
            assertEquals(expectedId, actualId);
        }
    }
}
