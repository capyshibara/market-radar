package com.marketradar.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.marketradar.domain.LlmCallLog;
import java.util.Optional;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {
    Optional<LlmCallLog> findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(
            String promptSha256, int sampleIndex);
}
