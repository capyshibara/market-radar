package com.marketradar.seed;

import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.domain.SourceUsePolicy;
import com.marketradar.intelligence.SourceIntelligencePolicy;
import com.marketradar.repo.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Repairs only automatically generated MANUAL_* sources when deterministic
 * publisher identity is stronger than the old UNKNOWN defaults. Explicit
 * non-unknown operator metadata is preserved dimension by dimension.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 41)
public class ManualSourceIntelligenceMetadataMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            ManualSourceIntelligenceMetadataMigration.class);
    private final SourceRepository sources;

    public ManualSourceIntelligenceMetadataMigration(SourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public void run(ApplicationArguments args) {
        int repaired = 0;
        for (Source source : sources.findAll()) {
            if (source.getCode() == null || !source.getCode().startsWith("MANUAL_")) continue;
            SourceIntelligencePolicy.Metadata inferred = SourceIntelligencePolicy.infer(source);
            SourceAuthority authority = source.getAuthority();
            GeographyScope marketScope = source.getDefaultMarketScope();
            String marketCode = source.getDefaultMarketCode();
            boolean changed = false;
            if (authority == SourceAuthority.UNKNOWN
                    && inferred.authority() != SourceAuthority.UNKNOWN) {
                authority = inferred.authority();
                changed = true;
            }
            if (marketScope == GeographyScope.UNKNOWN
                    && inferred.marketScope() != GeographyScope.UNKNOWN) {
                marketScope = inferred.marketScope();
                marketCode = inferred.marketCode();
                changed = true;
            }
            if (!changed) continue;
            boolean autoWatch = source.getUsePolicy() == SourceUsePolicy.WATCH_ONLY
                    && source.getAuthority() == SourceAuthority.UNKNOWN;
            source.setIntelligenceMetadata(authority, marketScope, marketCode);
            if (autoWatch && authority != SourceAuthority.UNKNOWN
                    && authority != SourceAuthority.SOCIAL_OR_BLOG) {
                source.setUsePolicy(SourceUsePolicy.DECISION_ELIGIBLE);
            }
            sources.save(source);
            repaired++;
        }
        if (repaired > 0) {
            log.info("Manual source intelligence metadata: repaired {} generated source(s)", repaired);
        }
    }
}
