package com.marketradar.pipeline;

import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.extract.ExtractionContentDiagnostics;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.SwitchableLlmClient;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.RawDocRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read-only live adapter for the one-run reprocessing preflight. */
@Service
public class ReprocessPreflightService {

    private final SwitchableLlmClient classifier;
    private final SwitchableLlmClient writer;
    private final SwitchableLlmClient verifier;
    private final PipelineRunStatusService runs;
    private final RawDocRepository docs;
    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;

    public ReprocessPreflightService(
            @Qualifier("classifierLlmClient") LlmClient classifier,
            LlmClient writer,
            @Qualifier("verifierLlmClient") LlmClient verifier,
            PipelineRunStatusService runs,
            RawDocRepository docs,
            EvidenceFactRepository facts,
            InterpretedClaimRepository claims) {
        this.classifier = (SwitchableLlmClient) classifier;
        this.writer = (SwitchableLlmClient) writer;
        this.verifier = (SwitchableLlmClient) verifier;
        this.runs = runs;
        this.docs = docs;
        this.facts = facts;
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public ReprocessPreflightRules.Report inspect(boolean backupConfirmed) {
        List<RawDoc> all = docs.findAllWithSource();
        long titleOnly = all.stream().filter(d -> !d.isFullTextFetched()).count();
        long shortFullText = all.stream().filter(RawDoc::isFullTextFetched)
                .filter(d -> d.getRawText() == null || d.getRawText().strip().length()
                        < ExtractionContentDiagnostics.MIN_ARTICLE_CHARS)
                .count();
        long parseFailures = all.stream().filter(d -> d.getParseStatus() != RawDoc.ParseStatus.OK).count();
        long incompleteDocuments = all.stream().filter(d -> !d.isFullTextFetched()
                        || d.getRawText() == null
                        || d.getRawText().strip().length() < ExtractionContentDiagnostics.MIN_ARTICLE_CHARS
                        || d.getParseStatus() != RawDoc.ParseStatus.OK)
                .count();
        var contentLengths = ExtractionContentDiagnostics.summarizeLengths(all.stream()
                .filter(d -> d.isFullTextFetched() && d.getParseStatus() == RawDoc.ParseStatus.OK)
                .map(RawDoc::getRawText).filter(java.util.Objects::nonNull)
                .map(String::length).toList());
        long pendingReview = claims.countByReviewStatus(InterpretedClaim.ReviewStatus.PENDING_REVIEW);

        return ReprocessPreflightRules.evaluate(new ReprocessPreflightRules.Input(
                classifier.config().kind(), writer.config().kind(), verifier.config().kind(),
                runs.anyRunning(), backupConfirmed, all.size(), incompleteDocuments,
                titleOnly, shortFullText,
                parseFailures, facts.count(), pendingReview, contentLengths));
    }
}
