package urlshortener.app.model;

import javax.persistence.*;

@Entity
@Table(name = "url_analytics")
public class URLAnalytics {
    
    @Id
    private String shortKey;
    
    @Column(nullable = false)
    private Long totalClicks = 0L;
    
    @Column(nullable = false)
    private Long lastAccessedAt;
    
    @Column(nullable = false)
    private Long createdAt;
    
    // Daily aggregation fields (optional enhancement)
    @Column
    private Long clicksToday = 0L;
    
    @Column
    private String lastAggregationDate; // Format: YYYY-MM-DD

    public URLAnalytics() {
    }

    public URLAnalytics(String shortKey) {
        this.shortKey = shortKey;
        this.totalClicks = 0L;
        this.clicksToday = 0L;
        this.createdAt = System.currentTimeMillis();
        this.lastAccessedAt = System.currentTimeMillis();
        this.lastAggregationDate = getCurrentDate();
    }

    public String getShortKey() {
        return shortKey;
    }

    public void setShortKey(String shortKey) {
        this.shortKey = shortKey;
    }

    public Long getTotalClicks() {
        return totalClicks;
    }

    public void setTotalClicks(Long totalClicks) {
        this.totalClicks = totalClicks;
    }

    public Long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Long lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getClicksToday() {
        return clicksToday;
    }

    public void setClicksToday(Long clicksToday) {
        this.clicksToday = clicksToday;
    }

    public String getLastAggregationDate() {
        return lastAggregationDate;
    }

    public void setLastAggregationDate(String lastAggregationDate) {
        this.lastAggregationDate = lastAggregationDate;
    }
    
    private String getCurrentDate() {
        return java.time.LocalDate.now().toString();
    }
}
