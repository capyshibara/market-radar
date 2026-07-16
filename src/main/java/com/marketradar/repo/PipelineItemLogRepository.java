package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.PipelineItemLog;

import java.util.List;

public interface PipelineItemLogRepository extends JpaRepository<PipelineItemLog, Long> {

    List<PipelineItemLog> findByRunLogIdOrderByIdAsc(Long runLogId);

    List<PipelineItemLog> findByRawDocIdOrderByCreatedAtAsc(Long rawDocId);

    /** Item-level events for the selected pipeline cycle(s). */
    List<PipelineItemLog> findByRunLogIdInOrderByCreatedAtAsc(List<Long> runLogIds);

    /** Cho trang history: mọi item log liên quan tới doc — dùng để dựng trail theo doc,
     * chỉ lấy lần chạy GẦN NHẤT của mỗi stage cho mỗi doc ở tầng service (đơn giản hơn
     * viết window-function trong JPQL, dữ liệu demo không đủ lớn để cần tối ưu). */
    List<PipelineItemLog> findByItemTypeOrderByCreatedAtDesc(PipelineItemLog.ItemType itemType);
}
