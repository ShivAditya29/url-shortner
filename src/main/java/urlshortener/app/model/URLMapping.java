package urlshortener.app.model;

import javax.persistence.*;

@Entity
@Table(name = "url_mappings")
public class URLMapping {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String shortKey;
    
    @Column(nullable = false, length = 2048)
    private String longUrl;
    
    @Column(nullable = false)
    private String urlHash;
    
    @Column(nullable = false)
    private Long createdAt;

    public URLMapping() {
    }

    public URLMapping(String shortKey, String longUrl, String urlHash) {
        this.shortKey = shortKey;
        this.longUrl = longUrl;
        this.urlHash = urlHash;
        this.createdAt = System.currentTimeMillis();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShortKey() {
        return shortKey;
    }

    public void setShortKey(String shortKey) {
        this.shortKey = shortKey;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public void setUrlHash(String urlHash) {
        this.urlHash = urlHash;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
