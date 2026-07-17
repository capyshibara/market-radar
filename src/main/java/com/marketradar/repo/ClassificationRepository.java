package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.Classification;
import com.marketradar.domain.RawDoc;
import java.util.List;
import java.util.Optional;

public interface ClassificationRepository extends JpaRepository<Classification, Long> {
    boolean existsByRawDoc(RawDoc rawDoc);

    Optional<Classification> findByRawDoc(RawDoc rawDoc);

    @Query("select c from Classification c join fetch c.rawDoc d join fetch d.source " +
           "order by c.createdAt desc")
    List<Classification> findAllForDisplay();

    /** Targeted read for a report candidate set; does not mutate classifications. */
    @Query("select c from Classification c join fetch c.rawDoc d join fetch d.source " +
           "where d.id in :rawDocIds")
    List<Classification> findByRawDocIdIn(@org.springframework.data.repository.query.Param("rawDocIds")
                                           List<Long> rawDocIds);

    /** Desk feed: routed items for one department, newest first. Read-only. */
    @Query("select c from Classification c join c.departments dept " +
           "join fetch c.rawDoc d join fetch d.source " +
           "where dept = :department and c.routingStatus = :routingStatus " +
           "order by c.createdAt desc")
    List<Classification> findByDepartmentAndRoutingStatus(
            @org.springframework.data.repository.query.Param("department")
            com.marketradar.domain.Department department,
            @org.springframework.data.repository.query.Param("routingStatus")
            Classification.RoutingStatus routingStatus);

    default List<Classification> findRoutedByDepartment(
            com.marketradar.domain.Department department) {
        return findByDepartmentAndRoutingStatus(department, Classification.RoutingStatus.ROUTED);
    }

    /** Re-route candidates when a routing rule is added for a category. */
    @Query("select c from Classification c join c.labels label " +
           "where label = :category and c.status = :status")
    List<Classification> findByLabelAndStatus(
            @org.springframework.data.repository.query.Param("category")
            com.marketradar.domain.Category category,
            @org.springframework.data.repository.query.Param("status")
            Classification.Status status);

    default List<Classification> findConfirmedByLabel(com.marketradar.domain.Category category) {
        return findByLabelAndStatus(category, Classification.Status.CONFIRMED);
    }
}
