package com.marketradar.extract;

import org.springframework.stereotype.Service;
import com.marketradar.domain.Classification;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.RawDoc;
import com.marketradar.llm.LlmClient;
import com.marketradar.prompt.PromptKey;
import com.marketradar.prompt.PromptService;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.FactExtractionRunRepository;
import com.marketradar.repo.RawDocRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only diagnostics and candidate selection for a controlled extraction backfill. */
@Service
public class ExtractionBackfillService {

    public static final int MAX_TARGETED_DOCS = 25;

    private final RawDocRepository rawDocs;
    private final ClassificationRepository classifications;
    private final FactExtractionRunRepository runs;
    private final PromptService prompts;
    private final LlmClient llm;

    public ExtractionBackfillService(RawDocRepository rawDocs,
                                     ClassificationRepository classifications,
                                     FactExtractionRunRepository runs,
                                     PromptService prompts, LlmClient llm) {
        this.rawDocs = rawDocs;
        this.classifications = classifications;
        this.runs = runs;
        this.prompts = prompts;
        this.llm = llm;
    }

    public ExtractionVersioning.CurrentVersion currentVersion() {
        return ExtractionVersioning.current(llm.providerName(), prompts.body(PromptKey.EXTRACT));
    }

    /** No writes and no LLM calls. */
    public BackfillPlan plan(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_TARGETED_DOCS));
        var version = currentVersion();

        Map<Long, Classification.Status> classByDoc = new HashMap<>();
        for (Classification c : classifications.findAllForDisplay()) {
            classByDoc.put(c.getRawDoc().getId(), c.getStatus());
        }

        EnumMap<ExtractionContentDiagnostics.State, Long> counts =
                new EnumMap<>(ExtractionContentDiagnostics.State.class);
        EnumMap<ExtractionContentDiagnostics.State, List<Candidate>> samples =
                new EnumMap<>(ExtractionContentDiagnostics.State.class);
        for (var state : ExtractionContentDiagnostics.State.values()) {
            counts.put(state, 0L);
            samples.put(state, new ArrayList<>());
        }
        List<Candidate> candidates = new ArrayList<>();
        List<Integer> fullTextLengths = new ArrayList<>();
        int singleChunkDocuments = 0, multiChunkDocuments = 0, maxChunks = 0;
        long sourceCharacters = 0, coveredCharacters = 0;
        for (RawDoc doc : rawDocs.findAllWithSource()) {
            if (doc.getParseStatus() == RawDoc.ParseStatus.OK && doc.isFullTextFetched()
                    && doc.getRawText() != null && !doc.getRawText().isBlank()) {
                fullTextLengths.add(doc.getRawText().length());
                var chunkPlan = LongDocumentChunker.plan(doc.getRawText());
                if (chunkPlan.chunkCount() <= 1) singleChunkDocuments++;
                else multiChunkDocuments++;
                maxChunks = Math.max(maxChunks, chunkPlan.chunkCount());
                sourceCharacters += chunkPlan.inputChars();
                coveredCharacters += chunkPlan.coveredCharacters();
            }
            boolean confirmed = classByDoc.get(doc.getId()) == Classification.Status.CONFIRMED;
            String signature = ExtractionVersioning.signature(version, doc);
            boolean current = runs.existsByRawDocAndExtractionSignatureAndStatusAndCurrentEditionTrue(
                    doc, signature, FactExtractionRun.Status.SUCCESS);
            ExtractionContentDiagnostics.Assessment assessment =
                    ExtractionContentDiagnostics.assessDetailed(doc, confirmed, current);
            counts.merge(assessment.state(), 1L, Long::sum);
            Candidate diagnostic = candidate(doc, assessment);
            if (samples.get(assessment.state()).size() < 5) {
                samples.get(assessment.state()).add(diagnostic);
            }
            if (assessment.state() == ExtractionContentDiagnostics.State.READY_STALE
                    && candidates.size() < limit) {
                candidates.add(diagnostic);
            }
        }
        List<FunnelStage> funnel = new ArrayList<>();
        for (var state : ExtractionContentDiagnostics.State.values()) {
            funnel.add(new FunnelStage(state, counts.get(state), List.copyOf(samples.get(state))));
        }
        EnumMap<FactExtractionRun.Status, Long> attemptOutcomes =
                new EnumMap<>(FactExtractionRun.Status.class);
        for (var status : FactExtractionRun.Status.values()) {
            attemptOutcomes.put(status, runs.countByStatus(status));
        }
        return new BackfillPlan(version.pipelineVersion(), version.modelVersion(),
                version.promptSha256(), Map.copyOf(counts),
                ExtractionContentDiagnostics.summarizeLengths(fullTextLengths),
                new ChunkCoverage(singleChunkDocuments, multiChunkDocuments, maxChunks,
                        sourceCharacters, coveredCharacters,
                        Math.max(0, sourceCharacters - coveredCharacters)),
                List.copyOf(funnel), Map.copyOf(attemptOutcomes),
                List.copyOf(candidates));
    }

    /** Validates explicit IDs against the same completeness/version rules as the dry run. */
    public TargetSelection selectTargets(List<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return new TargetSelection(List.of(), List.of("No rawDocId supplied"));
        }
        Set<Long> unique = new HashSet<>(requestedIds);
        if (unique.size() > MAX_TARGETED_DOCS) {
            return new TargetSelection(List.of(), List.of(
                    "At most " + MAX_TARGETED_DOCS + " documents are allowed per controlled run"));
        }

        var version = currentVersion();
        Map<Long, Classification> confirmed = new HashMap<>();
        for (Classification c : classifications.findAllForDisplay()) {
            if (c.getStatus() == Classification.Status.CONFIRMED
                    && unique.contains(c.getRawDoc().getId())) {
                confirmed.put(c.getRawDoc().getId(), c);
            }
        }

        List<Long> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Map<Long, RawDoc> docs = new HashMap<>();
        rawDocs.findAllWithSource().stream()
                .filter(d -> unique.contains(d.getId()))
                .forEach(d -> docs.put(d.getId(), d));

        for (Long id : unique.stream().sorted().toList()) {
            RawDoc doc = docs.get(id);
            if (doc == null) {
                rejected.add("doc#" + id + ": NOT_FOUND");
                continue;
            }
            boolean current = runs.existsByRawDocAndExtractionSignatureAndStatusAndCurrentEditionTrue(
                    doc, ExtractionVersioning.signature(version, doc),
                    FactExtractionRun.Status.SUCCESS);
            var assessment = ExtractionContentDiagnostics.assessDetailed(doc,
                    confirmed.containsKey(id), current);
            if (assessment.eligible()) accepted.add(id);
            else rejected.add("doc#" + id + ": " + assessment.reasonCode()
                    + " — " + assessment.reason());
        }
        return new TargetSelection(List.copyOf(accepted), List.copyOf(rejected));
    }

    private static Candidate candidate(RawDoc doc, ExtractionContentDiagnostics.Assessment assessment) {
        return new Candidate(doc.getId(), doc.getSource().getCode(), doc.getTitle(),
                doc.getRawText() == null ? 0 : doc.getRawText().length(), assessment.state(),
                assessment.reasonCode(), assessment.reason(), assessment.chunksPlanned(),
                assessment.completeChunkCoverage());
    }

    public record Candidate(Long rawDocId, String sourceCode, String title, int textChars,
                            ExtractionContentDiagnostics.State state, String reasonCode,
                            String reason, int chunksPlanned, boolean completeChunkCoverage) {}

    public record FunnelStage(ExtractionContentDiagnostics.State state, long count,
                              List<Candidate> samples) {}

    public record ChunkCoverage(int singleChunkDocuments, int multiChunkDocuments, int maxChunks,
                                long sourceCharacters, long coveredCharacters,
                                long silentlyDroppedCharacters) {}

    public record BackfillPlan(String pipelineVersion, String modelVersion, String promptSha256,
                               Map<ExtractionContentDiagnostics.State, Long> counts,
                               ExtractionContentDiagnostics.LengthStats contentLengths,
                               ChunkCoverage chunkCoverage, List<FunnelStage> rejectionFunnel,
                               Map<FactExtractionRun.Status, Long> attemptOutcomes,
                               List<Candidate> candidates) {}

    public record TargetSelection(List<Long> acceptedIds, List<String> rejected) {}
}
