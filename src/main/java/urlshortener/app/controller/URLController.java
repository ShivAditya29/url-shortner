package urlshortener.app.controller;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import urlshortener.app.common.URLValidator;
import urlshortener.app.exception.RateLimitExceededException;
import urlshortener.app.service.AnalyticsService;
import urlshortener.app.service.RateLimiterService;
import urlshortener.app.service.URLConverterService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;


@RestController
public class URLController {
    private static final Logger LOGGER = LoggerFactory.getLogger(URLController.class);
    private final URLConverterService urlConverterService;
    private final RateLimiterService rateLimiterService;
    private final AnalyticsService analyticsService;

    public URLController(URLConverterService urlConverterService, 
                        RateLimiterService rateLimiterService,
                        AnalyticsService analyticsService) {
        this.urlConverterService = urlConverterService;
        this.rateLimiterService = rateLimiterService;
        this.analyticsService = analyticsService;
    }

    @RequestMapping(value = "/shortener", method=RequestMethod.POST, consumes = {"application/json"})
    public String shortenUrl(@RequestBody @Valid final ShortenRequest shortenRequest, HttpServletRequest request) throws Exception {
        // Extract client identifier (IP address)
        String clientIp = getClientIp(request);
        
        // Rate limiting check (ONLY on URL creation, NOT on redirects)
        if (!rateLimiterService.isAllowed(clientIp)) {
            long resetTime = rateLimiterService.getResetTime(clientIp);
            LOGGER.warn("Rate limit exceeded for IP: {}", clientIp);
            throw new RateLimitExceededException(
                rateLimiterService.getMaxRequests(),
                rateLimiterService.getWindowSize(),
                resetTime
            );
        }
        
        LOGGER.info("Received url to shorten: {} from IP: {}", shortenRequest.getUrl(), clientIp);
        String longUrl = shortenRequest.getUrl();
        if (URLValidator.INSTANCE.validateURL(longUrl)) {
            String localURL = request.getRequestURL().toString();
            String shortenedUrl = urlConverterService.shortenURL(localURL, shortenRequest.getUrl());
            
            // Initialize analytics for new URL
            String shortKey = shortenedUrl.substring(shortenedUrl.lastIndexOf('/') + 1);
            analyticsService.initializeAnalytics(shortKey);
            
            LOGGER.info("Shortened url to: {}", shortenedUrl);
            
            // Add rate limit headers to response
            int remaining = rateLimiterService.getRemainingRequests(clientIp);
            LOGGER.info("Remaining requests for {}: {}", clientIp, remaining);
            
            return shortenedUrl;
        }
        throw new Exception("Please enter a valid URL");
    }

    @RequestMapping(value = "/{id}", method=RequestMethod.GET)
    public RedirectView redirectUrl(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws IOException, URISyntaxException, Exception {
        // NOTE: Redirects are NOT rate limited - only URL creation is rate limited
        // Reason: Redirects should be fast and unrestricted for end users
        LOGGER.info("Received shortened url to redirect: " + id);
        
        // Record click analytics (atomic increment in Redis)
        analyticsService.recordClick(id);
        
        String redirectUrlString = urlConverterService.getLongURLFromID(id);
        LOGGER.info("Original URL: " + redirectUrlString);
        RedirectView redirectView = new RedirectView();
        redirectView.setUrl("http://" + redirectUrlString);
        return redirectView;
    }
    
    @RequestMapping(value = "/stats/{id}", method=RequestMethod.GET)
    public Map<String, Object> getStats(@PathVariable String id) {
        LOGGER.info("Fetching analytics stats for: {}", id);
        return analyticsService.getStats(id);
    }
    
    /**
     * Extract client IP address from request
     * Checks X-Forwarded-For header for proxy/load balancer scenarios
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

class ShortenRequest{
    private String url;

    @JsonCreator
    public ShortenRequest() {

    }

    @JsonCreator
    public ShortenRequest(@JsonProperty("url") String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}


