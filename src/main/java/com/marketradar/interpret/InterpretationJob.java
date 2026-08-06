package com.marketradar.interpret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.InterpretedClaim.GateStatus;
import com.marketradar.domain.InterpretedClaim.Origin;
import com.marketradar.domain.InterpretedClaim.Slot;
import com.marketradar.domain.RawDoc;
import com.marketradar.llm.TerminalLlmRuntimeException;
import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.pipeline.PipelineRunStatusService;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.review.RiskTierRouter;
import com.marketradar.llm.ProviderSafetyRules;
import com.marketradar.report.bi.BiFinding;
import com.marketradar.report.bi.Connector;
import com.marketradar.report.bi.PeriodicalBiAdapter;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bước 5 pipeline: với mỗi RawDoc có evidence fact → build pack → AI#3 điền slot
 * → Gate L1 kiểm deterministic → LƯU mọi câu kèm gate status (kể cả fail — fail loud,
 * audit được ở /claims). Sau đó 1 pack toàn cục cho exec summary.
 *
 * GIẢ ĐỊNH: chỉ yêu cầu doc CÓ fact — không yêu cầu classification CONFIRMED (dữ liệu mẫu tự
 * seed đặt fact tay, không qua Classify, nên không thể đòi CONFIRMED). NHƯNG rawDoc.sampleData
 * PHẢI bị loại (2026-08-02 fix): trước đây thiếu điều kiện này, 2 tài liệu mẫu hư cấu của
 * SeedData bị xử lý y hệt tài liệu thật, sinh claim thật nằm lẫn trong Reviewer Queue — xem
 * SampleDataCleanupMigration.
 */
@Service
public class InterpretationJob {

    private static final Logger log = LoggerFactory.getLogger(InterpretationJob.class);

    /** Trần số claim đưa vào 1 pack narrative — đủ đa dạng để tổng hợp, đủ hẹp để model
     * bám chi tiết cụ thể (feedback reader 2026-07-15). */
    private static final int MAX_NARRATIVE_CLAIMS = 24;
    private static final int MAX_NARRATIVE_CLAIMS_PER_DOC = 2;
    private static final List<ReviewStatus> PUBLISHABLE_REVIEW_STATUSES = List.of(
            ReviewStatus.AUTO_APPROVED, ReviewStatus.APPROVED,
            ReviewStatus.EDITED_APPROVED, ReviewStatus.FORCE_APPROVED);

    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;
    private final Interpreter interpreter;
    private final GroundingGateL1 gate;
    private final RiskTierRouter tierRouter;
    private final PipelineRunStatusService progress;
    private final PipelineItemLogRepository itemLogs;
    private final PeriodicalBiAdapter biAdapter;
    private final TransactionTemplate transactions;
    private final AnalystInputSelection.Config inputSelectionConfig;
    private final double minimumSynthesisDocumentCoverage;

    public InterpretationJob(EvidenceFactRepository facts, InterpretedClaimRepository claims,
                             Interpreter interpreter, GroundingGateL1 gate,
                             RiskTierRouter tierRouter, PipelineRunStatusService progress,
                             PipelineItemLogRepository itemLogs,
                             PeriodicalBiAdapter biAdapter,
                             PlatformTransactionManager transactionManager,
                             @Value("${marketradar.analyst.batch-documents:60}") int maxDocuments,
                             @Value("${marketradar.analyst.max-facts-per-document:12}") int maxFactsPerDocument,
                             @Value("${marketradar.analyst.max-executive-facts:60}") int maxExecutiveFacts,
                             @Value("${marketradar.analyst.max-documents-per-source:18}") int maxDocumentsPerSource,
                             @Value("${marketradar.analyst.max-age-days:365}") int maxAgeDays,
                             @Value("${marketradar.analyst.target-market:VN}") String targetMarket,
                             @Value("${marketradar.analyst.synthesis-min-document-coverage:0.90}")
                             double minimumSynthesisDocumentCoverage) {
        this.facts = facts;
        this.claims = claims;
        this.interpreter = interpreter;
        this.gate = gate;
        this.tierRouter = tierRouter;
        this.progress = progress;
        this.itemLogs = itemLogs;
        this.biAdapter = biAdapter;
        this.transactions = new TransactionTemplate(transactionManager);
        this.inputSelectionConfig = new AnalystInputSelection.Config(
                maxDocuments, maxFactsPerDocument, maxExecutiveFacts,
                maxDocumentsPerSource, maxAgeDays, targetMarket);
        if (minimumSynthesisDocumentCoverage <= 0.0 || minimumSynthesisDocumentCoverage > 1.0) {
            throw new IllegalArgumentException(
                    "marketradar.analyst.synthesis-min-document-coverage must be in (0, 1]");
        }
        this.minimumSynthesisDocumentCoverage = minimumSynthesisDocumentCoverage;
    }

    public String runOnce() {
        if (ProviderSafetyRules.isStub(interpreter.providerName())) {
            return "Interpretation refused: writer provider is STUB/missing. "
                    + "No claim edition was created; configure a real writer model.\n";
        }
        StringBuilder summary = new StringBuilder();
        // sampleData=true → tài liệu mẫu hư cấu (SeedData), không phải evidence thật — loại
        // trước khi gom theo doc, khác đúng cách findCurrentProductNewsCandidates() đã làm.
        List<EvidenceFact> allFacts = facts.findAllForReport().stream()
                .filter(f -> f.getRawDoc() != null && !f.getRawDoc().isSampleData())
                .toList();
        if (allFacts.isEmpty()) return "No evidence facts yet — run Extract first.\n";

        // CFO step 2 happens before paid analysis. maxDocuments is only this action's
        // batch size: current interpretation editions are excluded so later actions
        // continue into the tail instead of selecting the same "top N" forever.
        AnalystInputSelection.Selection inputSelection = nextAnalystSelection(allFacts);
        Map<RawDoc, List<EvidenceFact>> byDoc = inputSelection.selectedByDocument();
        Map<RawDoc, List<EvidenceFact>> allEligibleByDoc = inputSelection.eligibleByDocument();
        if (allEligibleByDoc.isEmpty() || inputSelection.executiveFacts().isEmpty()) {
            return inputSelection.diagnostics().summary()
                    + " No current entity-safe evidence is available for paid analysis.\n";
        }

        EvidencePack globalPack = new EvidencePack(null, inputSelection.executiveFacts());
        Interpreter.InterpretationPlan execPlan = interpreter.planExec(globalPack);
        boolean finalBatch = inputSelection.diagnostics().deferredDocuments() == 0;
        AnalystSynthesisReadiness.Decision readinessBefore = synthesisReadiness(
                allEligibleByDoc, inputSelection.diagnostics().deferredDocuments());
        // A residual document is skipped only after its current signature/input has a
        // durable SCHEMA_REJECTED attempt and the represented corpus meets the configured
        // coverage floor. New/unattempted documents are still processed even above 90%.
        boolean quarantineAuditedResidualTail = readinessBefore.ready() && !byDoc.isEmpty();
        Map<RawDoc, List<EvidenceFact>> documentsToInterpret = quarantineAuditedResidualTail
                ? Map.of() : byDoc;
        boolean execPending = finalBatch && !hasCurrentExecEdition(execPlan);
        long eligibleDocs = documentsToInterpret.size();
        // Narrative input is known only after verified claim selection below. Reserve one
        // progress item/chapter only on the final document batch. Earlier batches
        // deliberately cannot publish a corpus-wide story from partial coverage.
        long chaptersPending = finalBatch ? Chapter.values().length : 0;
        progress.startProgress("interpret", (int) eligibleDocs + (execPending ? 1 : 0) + (int) chaptersPending);
        Long runLogId = progress.currentRunLogId("interpret");

        int docsDone = 0, docsSkipped = 0;
        if (quarantineAuditedResidualTail) {
            docsSkipped = byDoc.size();
            summary.append(readinessBefore.message()).append('\n');
        }
        for (var entry : documentsToInterpret.entrySet()) {
            RawDoc doc = entry.getKey();
            if (doc.getDuplicateOfId() != null) { docsSkipped++; continue; } // dedup đã lọc — khỏi tốn LLM viết claim
            EvidencePack pack = new EvidencePack(doc.getId(), entry.getValue());
            Interpreter.InterpretationPlan plan = interpreter.planDoc(pack);
            if (hasCurrentDocEdition(doc, plan)) { docsSkipped++; continue; }
            try {
                Interpreter.InterpretOutput out = interpreter.interpretDoc(pack, plan);
                PersistResult stored = transactions.execute(status -> {
                    PersistResult persisted = persist(out, pack.byCode(), pack.codes(), doc, null, plan, runLogId);
                    if (persisted.activatable()) claims.supersedePriorByRawDocIdAndOrigin(
                            doc.getId(), Origin.PIPELINE, persisted.editionId());
                    return persisted;
                });
                summary.append(stored.summary());
                docsDone++;
            } catch (RuntimeException e) {
                rethrowTerminal(e);
                log.error("Interpretation failed for doc#{}; prior edition preserved", doc.getId(), e);
                summary.append("doc#").append(doc.getId()).append(": ERROR — prior edition preserved — ")
                        .append(safeMessage(e)).append('\n');
                logItem(runLogId, doc, null, "ERROR", safeMessage(e));
            } finally {
                progress.stepProgress("interpret");
            }
        }

        AnalystSynthesisReadiness.Decision readinessAfter = synthesisReadiness(
                allEligibleByDoc, inputSelection.diagnostics().deferredDocuments());
        if (!finalBatch || !readinessAfter.ready()) {
            summary.append(readinessAfter.message()).append('\n');
            summary.insert(0, inputSelection.diagnostics().summary() + "\n"
                    + "Interpreted " + docsDone + " selected doc(s), skipped " + docsSkipped
                    + ". Provider: " + interpreter.providerName() + "\n");
            return summary.toString();
        }
        if (!quarantineAuditedResidualTail) summary.append(readinessAfter.message()).append('\n');

        // ---- Exec summary (pack toàn cục, 1 lần) ----
        if (execPending) {
            try {
                Interpreter.InterpretOutput out = interpreter.interpretExecSummary(globalPack, execPlan);
                PersistResult stored = transactions.execute(status -> {
                    PersistResult persisted = persist(out, globalPack.byCode(), globalPack.codes(), null, null, execPlan, runLogId);
                    if (persisted.activatable()) claims.supersedePriorBySlotAndOrigin(
                            Slot.EXEC_SUMMARY, Origin.PIPELINE, persisted.editionId());
                    return persisted;
                });
                summary.append(stored.summary());
            } catch (RuntimeException e) {
                rethrowTerminal(e);
                log.error("Executive synthesis failed; prior edition preserved", e);
                summary.append("EXEC: ERROR — prior edition preserved — ")
                        .append(safeMessage(e)).append('\n');
                logItem(runLogId, null, null, "ERROR", safeMessage(e));
            } finally {
                progress.stepProgress("interpret");
            }
        } else {
            summary.append("Exec summary already exists — skipped.\n");
        }

        // ---- Chapter narrative (batch 10): tổng hợp xuyên tài liệu, sau khi mọi
        // claim doc-level của run này đã có mặt trong DB ----
        try {
            runChapterNarrative(allEligibleByDoc, summary, runLogId);
        } catch (RuntimeException e) {
            rethrowTerminal(e);
            log.error("Chapter narrative stage failed; document editions remain committed", e);
            summary.append("Chapter narrative: ERROR — ").append(safeMessage(e)).append('\n');
        }

        // ---- DEEP_DIVE (2026-08-03): Connector đề xuất chủ thể từ claim ĐÃ DUYỆT, Analyst
        // viết narrative — chạy SAU narrative vì cần đọc claim mới nhất đã lưu ở trên ----
        try {
            runDeepDiveSynthesis(summary, runLogId);
        } catch (RuntimeException e) {
            rethrowTerminal(e);
            log.error("Deep-dive synthesis failed; earlier editions remain committed", e);
            summary.append("Deep dive: ERROR — ").append(safeMessage(e)).append('\n');
        }

        summary.insert(0, inputSelection.diagnostics().summary() + "\n"
                + "Interpreted " + docsDone + " selected doc(s), skipped " + docsSkipped
                + " (already interpreted). Executive pack=" + globalPack.facts().size()
                + " fact(s). Provider: " + interpreter.providerName() + "\n");
        return summary.toString();
    }

    /** Read-only paid-input preflight. It performs the exact production selection with zero LLM calls. */
    public String dryRunInputPlan() {
        List<EvidenceFact> allFacts = facts.findAllForReport().stream()
                .filter(f -> f.getRawDoc() != null && !f.getRawDoc().isSampleData())
                .toList();
        if (allFacts.isEmpty()) return "No evidence facts yet — run Researcher + Connector first.\n";
        AnalystInputSelection.Selection selection = nextAnalystSelection(allFacts);
        StringBuilder out = new StringBuilder("READ-ONLY ANALYST INPUT PLAN — zero LLM calls\n")
                .append(selection.diagnostics().summary()).append('\n')
                .append("Executive synthesis pack: ").append(selection.executiveFacts().size())
                .append(" bounded fact(s).\n\n");
        int rank = 0;
        for (var entry : selection.selectedByDocument().entrySet()) {
            RawDoc doc = entry.getKey();
            Set<String> markets = entry.getValue().stream().map(EvidenceFact::getMarketCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            out.append(String.format(Locale.ROOT, "%03d", ++rank)).append(" · doc#")
                    .append(doc.getId()).append(" · ").append(doc.getSource().getCode())
                    .append(" · ").append(doc.getSource().getAuthority())
                    .append(" · ").append(doc.getIntakeMethod())
                    .append(" · market=").append(markets.isEmpty() ? "UNKNOWN" : markets)
                    .append(" · ").append(entry.getValue().size()).append(" fact(s) · ")
                    .append(truncate(doc.getTitle(), 120)).append('\n');
        }
        return out.toString();
    }

    private AnalystInputSelection.Selection nextAnalystSelection(List<EvidenceFact> allFacts) {
        LocalDate today = LocalDate.now();
        AnalystInputSelection.Selection corpus = AnalystInputSelection.select(
                allFacts, today, inputSelectionConfig);
        Set<Long> representedDocumentIds = new LinkedHashSet<>();
        for (var entry : corpus.eligibleByDocument().entrySet()) {
            RawDoc doc = entry.getKey();
            if (doc.getId() == null) continue;
            EvidencePack pack = new EvidencePack(doc.getId(), entry.getValue());
            if (hasCurrentDocEdition(doc, interpreter.planDoc(pack))) {
                representedDocumentIds.add(doc.getId());
            }
        }
        return AnalystInputSelection.select(allFacts, today, inputSelectionConfig,
                representedDocumentIds);
    }

    /**
     * Tổng hợp xuyên tài liệu chỉ từ claim đã đủ điều kiện xuất bản: L1 PASS,
     * review *_APPROVED và verdict Gate L2 MỚI NHẤT là ENTAILED. Narrative có thể
     * trễ một vòng verify/review, nhưng không được dùng claim chưa kiểm hoặc claim
     * mà verifier mới nhất đã hạ xuống NEUTRAL/CONTRADICTED.
     */
    private void runChapterNarrative(Map<RawDoc, List<EvidenceFact>> byDoc, StringBuilder summary, Long runLogId) {
        Map<String, EvidenceFact> factByCode = new HashMap<>();
        Map<Long, List<EvidenceFact>> factsByDocId = new HashMap<>();
        byDoc.forEach((doc, fl) -> {
            factsByDocId.put(doc.getId(), fl);
            for (EvidenceFact f : fl) factByCode.put(f.getFactCode(), f);
        });

        List<InterpretedClaim> publishableInputs = claims.findPublishable(PUBLISHABLE_REVIEW_STATUSES).stream()
                .filter(c -> c.getRawDoc() != null && c.getRawDoc().getDuplicateOfId() == null)
                .filter(c -> c.getSlot() == Slot.WHY_MATTERS || c.getSlot() == Slot.IMPLICATION)
                .filter(c -> c.getOrigin() == Origin.PIPELINE)
                .toList();

        // Cửa sổ độ mới cho narrative: tổng hợp ở cửa sổ quarterly (90 ngày). Không
        // fallback sang corpus cũ: report mới phải rỗng/cảnh báo thay vì kể lại tin stale.
        LocalDate today = LocalDate.now();
        LocalDate winStart = com.marketradar.report.ReportWindow.narrativeStart(today);

        for (Chapter chapter : Chapter.values()) {
            try {
                List<InterpretedClaim> chapterCandidates = publishableInputs.stream()
                    .filter(c -> factsByDocId.getOrDefault(c.getRawDoc().getId(), List.of())
                            .stream().anyMatch(chapter::matches))
                    .toList();
                List<InterpretedClaim> eligible = NarrativeInputSelection.freshOnly(chapterCandidates,
                    c -> com.marketradar.report.ReportWindow.docInWindow(c.getRawDoc(), winStart, today));
                if (eligible.isEmpty()) {
                    Integer staleEditions = transactions.execute(status -> claims.supersedeStaleChapter(
                            Slot.NARRATIVE, chapter.name(), Origin.PIPELINE));
                    summary.append("Chapter ").append(chapter.name())
                            .append(": no fresh verified + approved claims in window; superseded ")
                            .append(staleEditions).append(" stale claim(s) — skipped.\n");
                    logItem(runLogId, null, chapter.name(), "SKIPPED",
                            "no fresh verified + approved claims in narrative window");
                    continue;
                }
                // A focused pack prevents a broad corpus from producing generic prose.
                List<InterpretedClaim> chapterClaims = selectNarrativeClaims(eligible, factsByDocId);
                Set<String> codes = chapterClaims.stream()
                        .flatMap(c -> Arrays.stream(c.getFactCodesCsv().split(",")))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                List<EvidenceFact> chapterFacts = codes.stream().map(factByCode::get).filter(Objects::nonNull).toList();
                NarrativePack pack = new NarrativePack(chapter, chapterClaims, chapterFacts);
                Interpreter.InterpretationPlan plan = interpreter.planNarrative(pack);
                if (hasCurrentNarrativeEdition(chapter, plan)) {
                    summary.append("Chapter ").append(chapter.name())
                            .append(": current interpretation edition already exists — skipped.\n");
                    continue;
                }
                Interpreter.InterpretOutput out = interpreter.interpretChapterNarrative(pack, plan);
                PersistResult stored = transactions.execute(status -> {
                    PersistResult persisted = persist(out, pack.byCode(), pack.codes(), null, chapter.name(), plan, runLogId);
                    if (persisted.activatable()) claims.supersedePriorBySlotAndChapterCodeAndOrigin(
                            Slot.NARRATIVE, chapter.name(), Origin.PIPELINE, persisted.editionId());
                    return persisted;
                });
                summary.append(stored.summary());
            } catch (RuntimeException e) {
                rethrowTerminal(e);
                log.error("Narrative failed for chapter {}; prior edition preserved", chapter, e);
                summary.append("Chapter ").append(chapter.name())
                        .append(": ERROR — prior edition preserved — ")
                        .append(safeMessage(e)).append('\n');
                logItem(runLogId, null, chapter.name(), "ERROR", safeMessage(e));
            } finally {
                progress.stepProgress("interpret");
            }
        }
    }

    /**
     * DEEP_DIVE (2026-08-03, feedback: "Sau đó sẽ đến Analyst và Fact Checker... Fact Checker
     * sẽ đi kiểm tra lại và đẩy vào hàng đợi người duyệt nếu không tự tin"): Connector đề xuất
     * chủ thể TỪ CLAIM ĐÃ DUYỆT (không phải fact thô — input đã qua ít nhất 1 vòng người duyệt),
     * Analyst tổng hợp thành 1 bài phân tích xuyên bucket/tài liệu, rồi câu MỚI này lại đi qua
     * ĐÚNG Gate L1 ngay dưới đây + Gate L2/Reviewer Queue ở stage verify/review riêng — không có
     * đường tắt nào bỏ qua xác thực dù input đầu vào đã "đáng tin" từ trước.
     */
    private void runDeepDiveSynthesis(StringBuilder summary, Long runLogId) {
        LocalDate today = LocalDate.now();
        LocalDate winStart = com.marketradar.report.ReportWindow.narrativeStart(today);
        List<PeriodicalBiAdapter.RoutedFinding> routedFindings = biAdapter.approvedFindings(winStart, today);
        if (routedFindings.isEmpty()) {
            summary.append("Deep dive: no approved findings in window — skipped.\n");
            return;
        }
        List<PeriodicalBiAdapter.RoutedFinding> decisionGrade = routedFindings.stream()
                .filter(rf -> "DECISION_GRADE".equals(rf.finding().evidenceGrade())).toList();
        if (decisionGrade.isEmpty()) {
            summary.append("Deep dive: only Editorial Watch findings are available; synthesis is withheld.\n");
            return;
        }
        Map<BiFinding, List<String>> factCodesByFinding = new HashMap<>();
        for (var rf : decisionGrade) factCodesByFinding.put(rf.finding(), rf.factCodes());
        List<BiFinding> findingsOnly = decisionGrade.stream().map(PeriodicalBiAdapter.RoutedFinding::finding).toList();
        List<Connector.DeepDiveCandidate> candidates = Connector.proposeDeepDiveCandidates(findingsOnly);
        if (candidates.isEmpty()) {
            summary.append("Deep dive: no candidate subject met the threshold this run — skipped.\n");
            return;
        }
        for (Connector.DeepDiveCandidate candidate : candidates) {
            try {
                String chapterCode = sanitizeDeepDiveKey(candidate.subjectKey());
                Set<String> codes = new LinkedHashSet<>();
                for (BiFinding f : candidate.members()) codes.addAll(factCodesByFinding.getOrDefault(f, List.of()));
                if (codes.isEmpty()) continue; // never synthesize without citations
                List<EvidenceFact> resolvedFacts = facts.findAllByFactCodeInForAudit(List.copyOf(codes));
                if (resolvedFacts.isEmpty()) continue;
                EvidencePack pack = new EvidencePack(null, resolvedFacts);
                Interpreter.InterpretationPlan plan = interpreter.planDeepDive(pack);
                if (hasCurrentDeepDiveEdition(chapterCode, plan)) {
                    summary.append("Deep dive \"").append(candidate.subjectKey())
                            .append("\": current interpretation edition already exists — skipped.\n");
                    continue;
                }
                Interpreter.InterpretOutput out = interpreter.interpretDeepDive(pack, plan);
                PersistResult stored = transactions.execute(status -> {
                    PersistResult persisted = persist(out, pack.byCode(), pack.codes(), null, chapterCode, plan, runLogId, Slot.DEEP_DIVE);
                    if (persisted.activatable()) claims.supersedePriorBySlotAndChapterCodeAndOrigin(
                            Slot.DEEP_DIVE, chapterCode, Origin.PIPELINE, persisted.editionId());
                    return persisted;
                });
                summary.append("Deep dive \"").append(candidate.subjectKey()).append("\" (")
                        .append(candidate.reason()).append("): ").append(stored.summary());
            } catch (RuntimeException e) {
                rethrowTerminal(e);
                log.error("Deep-dive candidate {} failed; continuing", candidate.subjectKey(), e);
                summary.append("Deep dive \"").append(candidate.subjectKey())
                        .append("\": ERROR — prior edition preserved — ")
                        .append(safeMessage(e)).append('\n');
            }
        }
    }

    private boolean hasCurrentDeepDiveEdition(String chapterCode, Interpreter.InterpretationPlan plan) {
        var key = plan.editionKey();
        return claims.existsBySlotAndChapterCodeAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
                Slot.DEEP_DIVE, chapterCode, Origin.PIPELINE, key.signature(), key.inputHash());
    }

    /** chapterCode column length=32 (@Column(length=32) trên InterpretedClaim#chapterCode) —
     *  chuẩn hoá subjectKey (có thể có dấu/khoảng trắng) về ASCII ngắn gọn, không đoán trùng. */
    private static String sanitizeDeepDiveKey(String subjectKey) {
        String ascii = java.text.Normalizer.normalize(subjectKey, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (ascii.isBlank()) ascii = "SUBJECT";
        String prefixed = "DD_" + ascii;
        return prefixed.length() > 32 ? prefixed.substring(0, 32) : prefixed;
    }

    /**
     * Chọn tập claim CÓ TRỌNG TÂM cho pack narrative (feedback reader 2026-07-15):
     *  - tối đa {@link #MAX_NARRATIVE_CLAIMS_PER_DOC} claim/doc (chống 1 bài chiếm cả chương);
     *  - ưu tiên doc có fact NÊU TÊN CÔNG TY (cụ thể hơn tin số liệu ngành/không tên);
     *  - trong cùng nhóm, doc mới hơn (eventDate lớn nhất) trước;
     *  - trần tổng {@link #MAX_NARRATIVE_CLAIMS}.
     * Deterministic (sort ổn định theo id khi hoà) để chạy lại cho kết quả nhất quán.
     */
    private List<InterpretedClaim> selectNarrativeClaims(List<InterpretedClaim> eligible,
                                                         Map<Long, List<EvidenceFact>> factsByDocId) {
        Map<Long, List<InterpretedClaim>> byDoc = eligible.stream()
                .collect(Collectors.groupingBy(c -> c.getRawDoc().getId(), LinkedHashMap::new, Collectors.toList()));

        record DocGroup(Long docId, boolean named, LocalDate recency, List<InterpretedClaim> claims) {}
        List<DocGroup> groups = new ArrayList<>();
        byDoc.forEach((docId, claims) -> {
            List<EvidenceFact> docFacts = factsByDocId.getOrDefault(docId, List.of());
            boolean named = docFacts.stream().anyMatch(f -> f.getCompany() != null && !f.getCompany().isBlank());
            LocalDate recency = docFacts.stream()
                    .map(EvidenceFact::getEventDate).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(LocalDate.MIN);
            List<InterpretedClaim> capped = claims.stream()
                    .sorted(Comparator.comparing(InterpretedClaim::getId))
                    .limit(MAX_NARRATIVE_CLAIMS_PER_DOC).toList();
            groups.add(new DocGroup(docId, named, recency, capped));
        });
        // named-company trước, rồi recency giảm dần, rồi docId để ổn định
        groups.sort(Comparator.comparing((DocGroup g) -> g.named() ? 0 : 1)
                .thenComparing(g -> g.recency(), Comparator.reverseOrder())
                .thenComparing(DocGroup::docId));

        List<InterpretedClaim> out = new ArrayList<>();
        for (DocGroup g : groups) {
            for (InterpretedClaim c : g.claims()) {
                if (out.size() >= MAX_NARRATIVE_CLAIMS) return out;
                out.add(c);
            }
        }
        return out;
    }

    /** Chấm gate từng câu và lưu — mọi câu đều được lưu, PASS hay FAIL.
     * chapterCode != null ⇒ câu Slot.NARRATIVE cho 1 chương (doc luôn null, giống EXEC_SUMMARY). */
    private record PersistResult(String summary, String editionId, boolean activatable) {}

    private PersistResult persist(Interpreter.InterpretOutput out, Map<String, EvidenceFact> byCode,
                                  Set<String> allCodes, RawDoc doc, String chapterCode,
                                  Interpreter.InterpretationPlan plan, Long runLogId) {
        Slot rejectSlot = chapterCode != null ? Slot.NARRATIVE : (doc == null ? Slot.EXEC_SUMMARY : Slot.WHY_MATTERS);
        return persist(out, byCode, allCodes, doc, chapterCode, plan, runLogId, rejectSlot);
    }

    /** 2026-08-03: rejectSlot tường minh (thay vì tự suy từ chapterCode!=null) — DEEP_DIVE cũng
     *  dùng chapterCode (khoá subjectKey) như NARRATIVE (chapterCode=chapter.name()), nên suy
     *  ngầm sẽ gán NHẦM SCHEMA_REJECTED của DEEP_DIVE thành Slot.NARRATIVE. */
    private PersistResult persist(Interpreter.InterpretOutput out, Map<String, EvidenceFact> byCode,
                                  Set<String> allCodes, RawDoc doc, String chapterCode,
                                  Interpreter.InterpretationPlan plan, Long runLogId, Slot rejectSlot) {
        StringBuilder sb = new StringBuilder();
        String docLabel = chapterCode != null ? "CHAPTER:" + chapterCode : (doc == null ? "EXEC" : "doc#" + doc.getId());
        String editionId = UUID.randomUUID().toString();

        if (out.schemaRejected()) {
            // Output không parse được → 1 record SCHEMA_REJECTED giữ raw để audit (fail loud).
            // Chỉ có raw response (chưa tách được vi/en) — lưu cùng raw vào cả hai cột.
            String raw = truncate(out.rawResponse(), 2000);
            InterpretedClaim c = new InterpretedClaim(nextCode(),
                    doc, rejectSlot, Origin.PIPELINE,
                    raw, raw, null,
                    GateStatus.SCHEMA_REJECTED, "{\"reason\":\"output was not valid JSON schema\"}",
                    interpreter.providerName());
            // Batch 4: schema-reject luôn cần người nhìn (fail loud)
            c.setRiskTier(tierRouter.assignTier(doc, Origin.PIPELINE));
            c.setReviewStatus(ReviewStatus.PENDING_REVIEW);
            c.setChapterCode(chapterCode);
            applyEdition(c, plan, editionId);
            c.markSuperseded(); // failed attempt: audit it, but never replace a good active edition
            claims.save(c);
            sb.append(docLabel).append(": SCHEMA_REJECTED (raw output kept in claim ").append(c.getClaimCode()).append(")\n");
            logItem(runLogId, doc, chapterCode, "SCHEMA_REJECTED", "raw output kept in claim " + c.getClaimCode());
            return new PersistResult(sb.toString(), editionId, false);
        }

        List<GateStatus> statuses = new ArrayList<>();
        for (Interpreter.Sentence s : out.sentences()) {
            List<EvidenceFact> cited = s.factCodes().stream()
                    .map(byCode::get).filter(Objects::nonNull).toList();
            GroundingGateL1.GateResult r = gate.checkBilingual(
                    s.textVi(), s.textEn(), s.factCodes(), cited, allCodes);
            InterpretedClaim c = new InterpretedClaim(nextCode(), doc, s.slot(), Origin.PIPELINE,
                    s.textVi(), s.textEn(), String.join(",", s.factCodes()),
                    r.status(), r.detailJson(), interpreter.providerName());
            // Batch 4: gán tier (placeholder RiskTierRouter) + route:
            //   L1 PASS → chờ Gate L2 (PENDING_VERIFICATION)
            //   L1 FAIL → thẳng vào Reviewer Console (không verify text đã fail exact-match)
            c.setRiskTier(tierRouter.assignTier(doc, Origin.PIPELINE));
            c.setReviewStatus(r.status() == GateStatus.PASS
                    ? ReviewStatus.PENDING_VERIFICATION : ReviewStatus.PENDING_REVIEW);
            c.setChapterCode(chapterCode);
            c.setBiBucket(s.biBucket());
            applyEdition(c, plan, editionId);
            claims.save(c);
            statuses.add(r.status());
            sb.append(docLabel).append(' ').append(s.slot()).append(" → ")
              .append(r.status()).append(" (").append(c.getClaimCode()).append(")\n");
            log.info("Gate L1 {} {} → {}", docLabel, c.getClaimCode(), r.status());
        }
        // 1 item log tổng hợp cho cả doc (nhiều câu → nhiều gate status) — status hiển thị
        // là PASS nếu MỌI câu pass, ngược lại liệt kê các FAIL gặp phải (worst-case, dễ quét).
        boolean allPass = !statuses.isEmpty() && statuses.stream().allMatch(s -> s == GateStatus.PASS);
        String itemStatus = statuses.isEmpty() ? "NO_SENTENCES"
                : allPass ? "PASS" : statuses.stream().filter(s -> s != GateStatus.PASS)
                        .map(Enum::name).distinct().reduce((a, b) -> a + "," + b).orElse("FAIL");
        logItem(runLogId, doc, chapterCode, itemStatus, statuses.size() + " sentence(s)");
        return new PersistResult(sb.toString(), editionId, shouldActivate(out));
    }

    private boolean hasCurrentDocEdition(RawDoc doc, Interpreter.InterpretationPlan plan) {
        var key = plan.editionKey();
        return claims.existsByRawDocAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
                doc, Origin.PIPELINE, key.signature(), key.inputHash());
    }

    private boolean hasCurrentSchemaFailure(RawDoc doc, Interpreter.InterpretationPlan plan) {
        var key = plan.editionKey();
        return claims.existsByRawDocAndOriginAndInterpretationSignatureAndInterpretationInputHashAndGateStatus(
                doc, Origin.PIPELINE, key.signature(), key.inputHash(), GateStatus.SCHEMA_REJECTED);
    }

    private AnalystSynthesisReadiness.Decision synthesisReadiness(
            Map<RawDoc, List<EvidenceFact>> allEligibleByDoc, int deferredDocuments) {
        int represented = 0;
        int auditedFailures = 0;
        for (var entry : allEligibleByDoc.entrySet()) {
            RawDoc doc = entry.getKey();
            if (doc.getId() == null) continue;
            Interpreter.InterpretationPlan plan = interpreter.planDoc(
                    new EvidencePack(doc.getId(), entry.getValue()));
            if (hasCurrentDocEdition(doc, plan)) represented++;
            else if (hasCurrentSchemaFailure(doc, plan)) auditedFailures++;
        }
        return AnalystSynthesisReadiness.evaluate(allEligibleByDoc.size(), represented,
                auditedFailures, deferredDocuments, minimumSynthesisDocumentCoverage);
    }

    private boolean hasCurrentExecEdition(Interpreter.InterpretationPlan plan) {
        var key = plan.editionKey();
        return claims.existsBySlotAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
                Slot.EXEC_SUMMARY, Origin.PIPELINE, key.signature(), key.inputHash());
    }

    private boolean hasCurrentNarrativeEdition(Chapter chapter, Interpreter.InterpretationPlan plan) {
        var key = plan.editionKey();
        return claims.existsBySlotAndChapterCodeAndOriginAndInterpretationSignatureAndInterpretationInputHashAndSupersededFalse(
                Slot.NARRATIVE, chapter.name(), Origin.PIPELINE, key.signature(), key.inputHash());
    }

    private static void applyEdition(InterpretedClaim claim, Interpreter.InterpretationPlan plan,
                                     String editionId) {
        var key = plan.editionKey();
        claim.setInterpretationEdition(key.signature(), key.inputHash(), editionId);
    }

    private static boolean shouldActivate(Interpreter.InterpretOutput out) {
        return InterpretationVersioning.shouldActivate(out.schemaRejected(), out.sentences().size());
    }

    private void logItem(Long runLogId, RawDoc doc, String chapterCode, String status, String message) {
        if (runLogId == null) return;
        String itemId = chapterCode != null ? "CHAPTER:" + chapterCode : (doc == null ? "EXEC" : String.valueOf(doc.getId()));
        String itemTitle = chapterCode != null ? "Chapter narrative: " + chapterCode
                : (doc == null ? "Executive summary" : doc.getTitle());
        itemLogs.save(new PipelineItemLog(runLogId, PipelineItemLog.ItemType.RAW_DOC,
                itemId, itemTitle, doc == null ? null : doc.getId(), status, message));
    }

    /**
     * C-001, C-002... — dựa trên MÃ LỚN NHẤT hiện có, không dùng count() (fix
     * 2026-07-13: count() vỡ khi có row bị xoá — xem InterpretedClaimRepository).
     */
    private String nextCode() {
        int max = claims.findAllClaimCodes().stream()
                .mapToInt(InterpretationJob::codeSuffix)
                .max().orElse(0);
        return String.format("C-%03d", max + 1);
    }

    private static int codeSuffix(String code) {
        try { return Integer.parseInt(code.substring(2)); } catch (Exception e) { return 0; }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return truncate(error.getMessage().strip(), 400);
    }

    private static void rethrowTerminal(RuntimeException error) {
        if (error instanceof TerminalLlmRuntimeException) throw error;
    }
}
