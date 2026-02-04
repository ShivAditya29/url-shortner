package urlshortener.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import urlshortener.app.model.URLMapping;
import urlshortener.app.repository.URLMappingRepository;

import java.util.List;

@Service
public class URLExpiryCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(URLExpiryCleanupService.class);
    
    private final URLMappingRepository urlMappingRepository;
    
    @Autowired
    public URLExpiryCleanupService(URLMappingRepository urlMappingRepository) {
        this.urlMappingRepository = urlMappingRepository;
    }
    
    /**
     * Scheduled job that runs once per day at midnight to clean up expired URLs.
     * Uses cron expression: "0 0 0 * * *" (midnight every day)
     * Runs daily to prevent database bloat from expired URLs that were never accessed (lazy deletion only catches accessed ones).
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void cleanupExpiredUrls() {
        LOGGER.info("[CLEANUP JOB] Starting daily expired URL cleanup");
        
        long startTime = System.currentTimeMillis();
        long currentTimeMillis = System.currentTimeMillis();
        
        // Find all expired URLs
        List<URLMapping> expiredUrls = urlMappingRepository.findExpiredUrls(currentTimeMillis);
        
        if (expiredUrls.isEmpty()) {
            LOGGER.info("[CLEANUP JOB] No expired URLs found");
            return;
        }
        
        LOGGER.info("[CLEANUP JOB] Found {} expired URLs to delete", expiredUrls.size());
        
        // Delete all expired URLs
        urlMappingRepository.deleteAll(expiredUrls);
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        LOGGER.info("[CLEANUP JOB] Cleanup completed. Deleted {} URLs in {}ms", 
                    expiredUrls.size(), elapsedTime);
    }
}
