package com.marketradar.seed;

import com.marketradar.prompt.PromptKey;
import com.marketradar.prompt.PromptOverride;
import com.marketradar.prompt.PromptOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Removes only overrides known to encode the retired narrow pipeline contract.
 * Newer human-authored overrides are preserved. Without this migration an old DB
 * silently wins over the scalable defaults compiled into the application.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 45)
public class LegacyPromptOverrideMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyPromptOverrideMigration.class);
    private static final Set<String> ANALYST_KEYS = Set.of(
            PromptKey.INTERPRET_DOC.name(), PromptKey.INTERPRET_EXEC.name(),
            PromptKey.INTERPRET_NARRATIVE.name(), PromptKey.INTERPRET_DEEP_DIVE.name());

    private final PromptOverrideRepository prompts;

    public LegacyPromptOverrideMigration(PromptOverrideRepository prompts) {
        this.prompts = prompts;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int removed = 0;
        for (PromptOverride override : prompts.findAll()) {
            String body = override.getBody() == null ? "" : override.getBody();
            boolean retiredAnalystContract = ANALYST_KEYS.contains(override.getPromptKey())
                    && (body.contains("CHỈ báo cáo SỰ VIỆC, KHÔNG đưa khuyến nghị/hàm ý")
                    || body.contains("CHỈ tường thuật SỰ VIỆC xuyên tài liệu")
                    || body.contains("CHỈ phân tích SỰ VIỆC/DỮ KIỆN, KHÔNG đưa khuyến"));
            boolean retiredClassifierScope = PromptKey.CLASSIFY.name().equals(override.getPromptKey())
                    && body.contains("thị trường Việt Nam và Trung Quốc");
            if (retiredAnalystContract || retiredClassifierScope) {
                prompts.delete(override);
                removed++;
            }
        }
        if (removed > 0) {
            log.warn("Removed {} legacy prompt override(s) that would bypass the scalable curation/analysis contract", removed);
        }
    }
}
