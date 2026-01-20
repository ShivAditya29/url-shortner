package urlshortener.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import urlshortener.app.model.URLAnalytics;

@Repository
public interface URLAnalyticsRepository extends JpaRepository<URLAnalytics, String> {
    // shortKey is the primary key, so findById is sufficient
}
