package urlshortener.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import urlshortener.app.model.URLMapping;

import java.util.Optional;

@Repository
public interface URLMappingRepository extends JpaRepository<URLMapping, Long> {
    Optional<URLMapping> findByShortKey(String shortKey);
    Optional<URLMapping> findByUrlHash(String urlHash);
}
