package com.marketradar.interpret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.InterpretedClaim.GateStatus;
import com.marketradar.domain.InterpretedClaim.Origin;
import com.marketradar.domain.InterpretedClaim.Slot;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.pipeline.PipelineRunStatusService;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.PipelineItemLogRepository;
import com.marketradar.review.RiskTierRouter;
import com.marketradar.llm.ProviderSafetyRules;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bước 5 pipeline: với mỗi RawDoc có evidence fact → build pack → AI#3 điền slot
 * → Gate L1 kiểm deterministic → LƯU mọi câu kèm gate status (kể cả fail — fail loud,
 * audit được ở /claims). Sau đó 1 pack toàn cục cho exec summary.
 *
 * GIẢ ĐỊNH: chỉ yêu cầu doc CÓ fact — không yêu cầu classification CONFIRMED
 * (fact extraction từ doc thật vẫn là bước mở; hiện facts là sample data từ seed).
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

    public InterpretationJob(EvidenceFactRepository facts, InterpretedClaimRepository claims,
                             Interpreter interpreter, GroundingGateL1 gate,
                             RiskTierRouter tierRouter, PipelineRunStatusService progress,
                             PipelineItemLogRepository itemLogs) {
        this.facts = facts;
        this.claims = claims;
        this.interpreter = interpreter;
        this.gate = gate;
        this.tierRouter = tierRouter;
        this.progress = progress;
        this.itemLogs = itemLogs;
    }

    @Transactional
    public String runOnce() {
        if (ProviderSafetyRules.isStub(interpreter.providerName())) {
            return "Interpretation refused: writer provider is STUB/missing. "
                    + "No claim edition was created; configure a real writer model.\n";
        }
        StringBuilder summary = new StringBuilder();
        List<EvidenceFact> allFacts = facts.findAllForReport();
        if (allFacts.isEmpty()) return "No evidence facts yet — run Extract first.\n";

        // ---- Gom fact theo doc ----
        Map<RawDoc, List<EvidenceFact>> byDoc = new LinkedHashMap<>();
        for (EvidenceFact f : allFacts) byDoc.computeIfAbsent(f.getRawDoc(), d -> new ArrayList<>()).add(f);

        EvidencePack globalPack = new EvidencePack(null, allFacts);
        Interpreter.InterpretationPlan execPlan = interpreter.planExec(globalPack);
        boolean execPending = !hasCurrentExecEdition(execPlan);
        long eligibleDocs = byDoc.entrySet().stream()
                .filter(e -> e.getKey().getDuplicateOfId() == null)
                .filter(e -> {
                    EvidencePack pack = new EvidencePack(e.getKey().getId(), e.getValue());
                    return !hasCurrentDocEdition(e.getKey(), interpreter.planDoc(pack));
                }).count();
        // Narrative input is known only after verified claim selection below. Reserve one
        // progress item/chapter; an unchanged current edition is reported as skipped.
        long chaptersPending = Chapter.values().length;
        progress.startProgress("interpret", (int) eligibleDocs + (execPending ? 1 : 0) + (int) chaptersPending);
        Long runLogId = progress.currentRunLogId("interpret");

        int docsDone = 0, docsSkipped = 0;
        for (var entry : byDoc.entrySet()) {
            RawDoc doc = entry.getKey();
            if (doc.getDuplicateOfId() != null) { docsSkipped++; continue; } // dedup đã lọc — khỏi tốn LLM viết claim
            EvidencePack pack = new EvidencePack(doc.getId(), entry.getValue());
            Interpreter.InterpretationPlan plan = interpreter.planDoc(pack);
            if (hasCurrentDocEdition(doc, plan)) { docsSkipped++; continue; }
            Interpreter.InterpretOutput out = interpreter.interpretDoc(pack, plan);
            PersistResult stored = persist(out, pack.byCode(), pack.codes(), doc, null, plan, runLogId);
            if (stored.activatable()) claims.supersedePriorByRawDocIdAndOrigin(
                    doc.getId(), Origin.PIPELINE, stored.editionId());
            summary.append(stored.summary());
            docsDone++;
            progress.stepProgress("interpret");
        }

        // ---- Exec summary (pack toàn cục, 1 lần) ----
        if (execPending) {
            Interpreter.InterpretOutput out = interpreter.interpretExecSummary(globalPack, execPlan);
            PersistResult stored = persist(out, globalPack.byCode(), globalPack.codes(), null, null, execPlan, runLogId);
            if (stored.activatable()) claims.supersedePriorBySlotAndOrigin(
                    Slot.EXEC_SUMMARY, Origin.PIPELINE, stored.editionId());
            summary.append(stored.summary());
            progress.stepProgress("interpret");
        } else {
            summary.append("Exec summary already exists — skipped.\n");
        }

        // ---- Chapter narrative (batch 10): tổng hợp xuyên tài liệu, sau khi mọi
        // claim doc-level của run này đã có mặt trong DB ----
        runChapterNarrative(byDoc, summary, runLogId);

        summary.insert(0, "Interpreted " + docsDone + " doc(s), skipped " + docsSkipped
                + " (already interpreted). Provider: " + interpreter.providerName() + "\n");
        return summary.toString();
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
            List<InterpretedClaim> chapterCandidates = publishableInputs.stream()
                    .filter(c -> factsByDocId.getOrDefault(c.getRawDoc().getId(), List.of())
                            .stream().anyMatch(chapter::matches))
                    .toList();
            List<InterpretedClaim> eligible = NarrativeInputSelection.freshOnly(chapterCandidates,
                    c -> com.marketradar.report.ReportWindow.docInWindow(c.getRawDoc(), winStart, today));
            if (eligible.isEmpty()) {
                int staleEditions = claims.supersedeStaleChapter(
                        Slot.NARRATIVE, chapter.name(), Origin.PIPELINE);
                summary.append("Chapter ").append(chapter.name())
                        .append(": no fresh verified + approved claims in window; superseded ")
                        .append(staleEditions).append(" stale claim(s) — skipped.\n");
                logItem(runLogId, null, chapter.name(), "SKIPPED",
                        "no fresh verified + approved claims in narrative window");
                progress.stepProgress("interpret");
                continue;
            }
            // Chọn pack CÓ TRỌNG TÂM thay vì nhồi toàn bộ (feedback reader 2026-07-15: pack
            // 100+ claim → model né chi tiết, viết chung chung). Ưu tiên tài liệu có fact
            // nêu ĐÍCH DANH công ty (cụ thể hơn tin số liệu ngành), tối đa 2 claim/doc,
            // trần MAX_NARRATIVE_CLAIMS — vẫn deterministic, auditable.
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
                progress.stepProgress("interpret");
                continue;
            }
            Interpreter.InterpretOutput out = interpreter.interpretChapterNarrative(pack, plan);
            PersistResult stored = persist(out, pack.byCode(), pack.codes(), null, chapter.name(), plan, runLogId);
            if (stored.activatable()) claims.supersedePriorBySlotAndChapterCodeAndOrigin(
                    Slot.NARRATIVE, chapter.name(), Origin.PIPELINE, stored.editionId());
            summary.append(stored.summary());
            progress.stepProgress("interpret");
        }
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
        StringBuilder sb = new StringBuilder();
        String docLabel = chapterCode != null ? "CHAPTER:" + chapterCode : (doc == null ? "EXEC" : "doc#" + doc.getId());
        String editionId = UUID.randomUUID().toString();

        if (out.schemaRejected()) {
            // Output không parse được → 1 record SCHEMA_REJECTED giữ raw để audit (fail loud).
            // Chỉ có raw response (chưa tách được vi/en) — lưu cùng raw vào cả hai cột.
            String raw = truncate(out.rawResponse(), 2000);
            Slot rejectSlot = chapterCode != null ? Slot.NARRATIVE : (doc == null ? Slot.EXEC_SUMMARY : Slot.WHY_MATTERS);
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
}
