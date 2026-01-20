package urlshortener.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import urlshortener.app.common.IDConverter;
import urlshortener.app.model.URLMapping;
import urlshortener.app.repository.URLMappingRepository;
import urlshortener.app.repository.URLRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
public class URLConverterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(URLConverterService.class);
    private final URLRepository urlRepository;
    private final URLMappingRepository dbRepository;

    @Autowired
    public URLConverterService(URLRepository urlRepository, URLMappingRepository dbRepository) {
        this.urlRepository = urlRepository;
        this.dbRepository = dbRepository;
    }

    public String shortenURL(String localURL, String longUrl) {
        LOGGER.info("Shortening {}", longUrl);
        
        // Generate hash of the long URL for idempotency
        String urlHash = generateHash(longUrl);
        
        // CACHE-ASIDE PATTERN - Step 1: Check cache first
        String existingShortKey = urlRepository.getShortKeyFromHash(urlHash);
        if (existingShortKey != null) {
            LOGGER.info("[CACHE HIT] URL already exists with short key: {}", existingShortKey);
            String baseString = formatLocalURLFromShortener(localURL);
            return baseString + existingShortKey;
        }
        
        // Step 2: Cache miss - check database
        LOGGER.info("[CACHE MISS] Checking database for hash: {}", urlHash);
        Optional<URLMapping> existingMapping = dbRepository.findByUrlHash(urlHash);
        if (existingMapping.isPresent()) {
            String shortKey = existingMapping.get().getShortKey();
            LOGGER.info("[DB HIT] Found in database, repopulating cache: {}", shortKey);
            
            // Step 3: Repopulate cache with TTL (cache-aside write-through)
            urlRepository.saveUrlHash(urlHash, shortKey);
            Long id = IDConverter.INSTANCE.getDictionaryKeyFromUniqueID(shortKey);
            urlRepository.saveUrl("url:" + id, longUrl);
            
            String baseString = formatLocalURLFromShortener(localURL);
            return baseString + shortKey;
        }
        
        // Step 4: Neither cache nor DB has it - create new entry
        LOGGER.info("[DB MISS] Creating new shortened URL");
        
        // Try Redis ID, fallback to DB sequence if Redis is down
        Long id = urlRepository.incrementID();
        if (id == null) {
            LOGGER.warn("[DEGRADED MODE] Redis down, using DB-generated sequence");
            // Let DB generate the ID
            URLMapping mapping = new URLMapping(null, longUrl, urlHash);
            URLMapping saved = dbRepository.save(mapping);
            id = saved.getId();
        }
        
        String uniqueID = IDConverter.INSTANCE.createUniqueID(id);
        
        // Save to database (source of truth)
        URLMapping newMapping = new URLMapping(uniqueID, longUrl, urlHash);
        dbRepository.save(newMapping);
        LOGGER.info("[DB WRITE] Saved to database: {}", uniqueID);
        
        // Write to cache if available
        urlRepository.saveUrl("url:" + id, longUrl);
        urlRepository.saveUrlHash(urlHash, uniqueID);
        LOGGER.info("[CACHE WRITE] Populated cache with TTL");
        
        String baseString = formatLocalURLFromShortener(localURL);
        String shortenedURL = baseString + uniqueID;
        return shortenedURL;
    }

    public String getLongURLFromID(String uniqueID) throws Exception {
        Long dictionaryKey = IDConverter.INSTANCE.getDictionaryKeyFromUniqueID(uniqueID);
        
        // CACHE-ASIDE PATTERN - Step 1: Try cache first
        String longUrl = urlRepository.getUrl(dictionaryKey);
        
        if (longUrl != null) {
            LOGGER.info("[CACHE HIT] Retrieved from cache: {}", longUrl);
            return longUrl;
        }
        
        // Step 2: Cache miss - query database
        LOGGER.info("[CACHE MISS] Querying database for shortKey: {}", uniqueID);
        Optional<URLMapping> mapping = dbRepository.findByShortKey(uniqueID);
        
        if (!mapping.isPresent()) {
            LOGGER.error("[DB MISS] URL not found for shortKey: {}", uniqueID);
            throw new Exception("URL for short key " + uniqueID + " does not exist");
        }
        
        longUrl = mapping.get().getLongUrl();
        LOGGER.info("[DB HIT] Retrieved from database: {}", longUrl);
        
        // Step 3: Repopulate cache with TTL
        LOGGER.info("[CACHE WRITE] Repopulating cache with TTL");
        urlRepository.saveUrl("url:" + dictionaryKey, longUrl);
        
        return longUrl;
    }

    private String formatLocalURLFromShortener(String localURL) {
        String[] addressComponents = localURL.split("/");
        // remove the endpoint (last index)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addressComponents.length - 1; ++i) {
            sb.append(addressComponents[i]);
        }
        sb.append('/');
        return sb.toString();
    }

    private String generateHash(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            
            // Convert byte array to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("SHA-256 algorithm not available", e);
            // Fallback to using the URL itself as hash (not recommended for production)
            return url;
        }
    }

}
