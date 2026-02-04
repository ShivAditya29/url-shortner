package urlshortener.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import urlshortener.app.model.URLMapping;

import java.util.List;
import java.util.Optional;

@Repository
public interface URLMappingRepository extends JpaRepository<URLMapping, Long> {
    Optional<URLMapping> findByShortKey(String shortKey);
    Optional<URLMapping> findByUrlHash(String urlHash);
    
    @Query("SELECT u FROM URLMapping u WHERE u.expiresAt IS NOT NULL AND u.expiresAt < ?1")
    List<URLMapping> findExpiredUrls(Long currentTimeMillis);
}
