package urlshortener.app.exception;

public class RateLimitExceededException extends RuntimeException {
    private final int maxRequests;
    private final int windowSeconds;
    private final long resetTime;
    
    public RateLimitExceededException(int maxRequests, int windowSeconds, long resetTime) {
        super(String.format("Rate limit exceeded. Max %d requests per %d seconds. Try again in %d seconds.", 
            maxRequests, windowSeconds, resetTime));
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.resetTime = resetTime;
    }
    
    public int getMaxRequests() {
        return maxRequests;
    }
    
    public int getWindowSeconds() {
        return windowSeconds;
    }
    
    public long getResetTime() {
        return resetTime;
    }
}
