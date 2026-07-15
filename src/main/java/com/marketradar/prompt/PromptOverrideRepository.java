package com.marketradar.prompt;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PromptOverrideRepository extends JpaRepository<PromptOverride, String> {
    Optional<PromptOverride> findByPromptKey(String promptKey);
}
