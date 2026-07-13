package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.marketradar.domain.LlmCallLog;
import java.util.Optional;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {
    Optional<LlmCallLog> findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(
            String promptSha256, int sampleIndex);

    /**
     * Batch 9 ("Force Retry"): xoá cache của MỘT doc cho MỘT purpose cụ thể — nếu không
     * xoá, replay-cache sẽ trả lại đúng response HỎNG cũ thay vì gọi LLM lại thật sự
     * (đây chính là lỗi gặp phải đêm nay — xoá claim mà quên xoá cache thì vẫn kẹt).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from LlmCallLog l where l.purpose = :purpose and l.rawDocId = :rawDocId")
    void deleteByPurposeAndRawDocId(@org.springframework.data.repository.query.Param("purpose") String purpose,
                                     @org.springframework.data.repository.query.Param("rawDocId") Long rawDocId);
}
