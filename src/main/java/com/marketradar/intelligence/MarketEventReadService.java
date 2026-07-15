package com.marketradar.intelligence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.repo.MarketEventRepository;

import java.time.LocalDate;
import java.util.List;

/** Supported downstream boundary; consumers should not reconstruct cluster/temporal rules. */
@Service
public class MarketEventReadService {

    private final MarketEventRepository events;

    public MarketEventReadService(MarketEventRepository events) {
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<MarketEventIntelligenceView> readForSynthesis(String pipelineVersion,
                                                              LocalDate asOf) {
        if (pipelineVersion == null || pipelineVersion.isBlank() || asOf == null) {
            throw new IllegalArgumentException("pipelineVersion and asOf are required");
        }
        return events.findAllWithClusterForPipelineVersion(pipelineVersion).stream()
                .map(event -> MarketEventIntelligenceView.from(event, asOf)).toList();
    }

    /** Hard-safe subset for generating future-looking actions. */
    @Transactional(readOnly = true)
    public List<MarketEventIntelligenceView> futureActionCandidates(String pipelineVersion,
                                                                    LocalDate asOf) {
        return readForSynthesis(pipelineVersion, asOf).stream()
                .filter(MarketEventIntelligenceView::futureActionEligible).toList();
    }
}
