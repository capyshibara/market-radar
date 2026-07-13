package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketradar.domain.Classification;
import com.marketradar.domain.RawDoc;
import java.util.List;

public interface ClassificationRepository extends JpaRepository<Classification, Long> {
    boolean existsByRawDoc(RawDoc rawDoc);

    @Query("select c from Classification c join fetch c.rawDoc d join fetch d.source " +
           "order by c.createdAt desc")
    List<Classification> findAllForDisplay();

    /**
     * Force Retry mirror (see ClaimController#forceRetry): xoá Classification của MỘT
     * doc để ClassificationJob coi doc đó là CHƯA classify — existsByRawDoc trả false,
     * doc được xử lý lại ở lần chạy /classify/run tiếp theo.
     */
    @Modifying
    @Query("delete from Classification c where c.rawDoc.id = :rawDocId")
    void deleteByRawDocId(@Param("rawDocId") Long rawDocId);
}
