package vn.techcomlife.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.techcomlife.marketradar.domain.Classification;
import vn.techcomlife.marketradar.domain.RawDoc;
import java.util.List;

public interface ClassificationRepository extends JpaRepository<Classification, Long> {
    boolean existsByRawDoc(RawDoc rawDoc);

    @Query("select c from Classification c join fetch c.rawDoc d join fetch d.source " +
           "order by c.createdAt desc")
    List<Classification> findAllForDisplay();
}
