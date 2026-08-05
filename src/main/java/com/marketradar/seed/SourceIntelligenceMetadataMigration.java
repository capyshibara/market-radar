package com.marketradar.seed;

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

/** Backfills the independent authority and geography axes after all source seeds run. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class SourceIntelligenceMetadataMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceIntelligenceMetadataMigration.class);
    private final SourceRepository sources;

    public SourceIntelligenceMetadataMigration(SourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public void run(ApplicationArguments args) {
        int enriched = 0;
        for (Source source : sources.findAll()) {
            // Preserve later operator corrections. This migration only fills legacy rows.
            boolean changed = false;
            if (!source.hasExplicitIntelligenceMetadata()) {
                SourceIntelligencePolicy.Metadata metadata = SourceIntelligencePolicy.infer(source);
                source.setIntelligenceMetadata(metadata.authority(), metadata.marketScope(), metadata.marketCode());
                changed = true;
            }
            if (!source.hasExplicitUsePolicy()) {
                SourceAuthority authority = source.getAuthority();
                source.setUsePolicy(authority == SourceAuthority.UNKNOWN
                                || authority == SourceAuthority.SOCIAL_OR_BLOG
                        ? SourceUsePolicy.WATCH_ONLY
                        : SourceUsePolicy.DECISION_ELIGIBLE);
                changed = true;
            }
            if (changed) {
                sources.save(source);
                enriched++;
            }
        }
        if (enriched > 0) {
            log.info("Source intelligence metadata: separated authority and geography for {} source(s)", enriched);
        }
    }
}
