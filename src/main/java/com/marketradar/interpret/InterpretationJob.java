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
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.review.RiskTierRouter;

import java.util.*;

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

    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;
    private final Interpreter interpreter;
    private final GroundingGateL1 gate;
    private final RiskTierRouter tierRouter;

    public InterpretationJob(EvidenceFactRepository facts, InterpretedClaimRepository claims,
                             Interpreter interpreter, GroundingGateL1 gate,
                             RiskTierRouter tierRouter) {
        this.facts = facts;
        this.claims = claims;
        this.interpreter = interpreter;
        this.gate = gate;
        this.tierRouter = tierRouter;
    }

    @Transactional
    public String runOnce() {
        StringBuilder summary = new StringBuilder();
        List<EvidenceFact> allFacts = facts.findAllForReport();
        if (allFacts.isEmpty()) return "No evidence facts yet — run Extract first.\n";

        // ---- Gom fact theo doc ----
        Map<RawDoc, List<EvidenceFact>> byDoc = new LinkedHashMap<>();
        for (EvidenceFact f : allFacts) byDoc.computeIfAbsent(f.getRawDoc(), d -> new ArrayList<>()).add(f);

        int docsDone = 0, docsSkipped = 0;
        for (var entry : byDoc.entrySet()) {
            RawDoc doc = entry.getKey();
            if (doc.getDuplicateOfId() != null) { docsSkipped++; continue; } // dedup đã lọc — khỏi tốn LLM viết claim
            if (claims.existsByRawDocAndOrigin(doc, Origin.PIPELINE)) { docsSkipped++; continue; }
            EvidencePack pack = new EvidencePack(doc.getId(), entry.getValue());
            Interpreter.InterpretOutput out = interpreter.interpretDoc(pack);
            summary.append(persist(out, pack, doc));
            docsDone++;
        }

        // ---- Exec summary (pack toàn cục, 1 lần) ----
        if (!claims.existsBySlotAndOrigin(Slot.EXEC_SUMMARY, Origin.PIPELINE)) {
            EvidencePack globalPack = new EvidencePack(null, allFacts);
            Interpreter.InterpretOutput out = interpreter.interpretExecSummary(globalPack);
            summary.append(persist(out, globalPack, null));
        } else {
            summary.append("Exec summary already exists — skipped.\n");
        }

        summary.insert(0, "Interpreted " + docsDone + " doc(s), skipped " + docsSkipped
                + " (already interpreted). Provider: " + interpreter.providerName() + "\n");
        return summary.toString();
    }

    /** Chấm gate từng câu và lưu — mọi câu đều được lưu, PASS hay FAIL. */
    private String persist(Interpreter.InterpretOutput out, EvidencePack pack, RawDoc doc) {
        StringBuilder sb = new StringBuilder();
        String docLabel = doc == null ? "EXEC" : "doc#" + doc.getId();

        if (out.schemaRejected()) {
            // Output không parse được → 1 record SCHEMA_REJECTED giữ raw để audit (fail loud).
            // Chỉ có raw response (chưa tách được vi/en) — lưu cùng raw vào cả hai cột.
            String raw = truncate(out.rawResponse(), 2000);
            InterpretedClaim c = new InterpretedClaim(nextCode(),
                    doc, doc == null ? Slot.EXEC_SUMMARY : Slot.WHY_MATTERS, Origin.PIPELINE,
                    raw, raw, null,
                    GateStatus.SCHEMA_REJECTED, "{\"reason\":\"output was not valid JSON schema\"}",
                    interpreter.providerName());
            // Batch 4: schema-reject luôn cần người nhìn (fail loud)
            c.setRiskTier(tierRouter.assignTier(doc, Origin.PIPELINE));
            c.setReviewStatus(ReviewStatus.PENDING_REVIEW);
            claims.save(c);
            sb.append(docLabel).append(": SCHEMA_REJECTED (raw output kept in claim ").append(c.getClaimCode()).append(")\n");
            return sb.toString();
        }

        Map<String, EvidenceFact> byCode = pack.byCode();
        for (Interpreter.Sentence s : out.sentences()) {
            List<EvidenceFact> cited = s.factCodes().stream()
                    .map(byCode::get).filter(Objects::nonNull).toList();
            GroundingGateL1.GateResult r = gate.checkBilingual(
                    s.textVi(), s.textEn(), s.factCodes(), cited, pack.codes());
            InterpretedClaim c = new InterpretedClaim(nextCode(), doc, s.slot(), Origin.PIPELINE,
                    s.textVi(), s.textEn(), String.join(",", s.factCodes()),
                    r.status(), r.detailJson(), interpreter.providerName());
            // Batch 4: gán tier (placeholder RiskTierRouter) + route:
            //   L1 PASS → chờ Gate L2 (PENDING_VERIFICATION)
            //   L1 FAIL → thẳng vào Reviewer Console (không verify text đã fail exact-match)
            c.setRiskTier(tierRouter.assignTier(doc, Origin.PIPELINE));
            c.setReviewStatus(r.status() == GateStatus.PASS
                    ? ReviewStatus.PENDING_VERIFICATION : ReviewStatus.PENDING_REVIEW);
            claims.save(c);
            sb.append(docLabel).append(' ').append(s.slot()).append(" → ")
              .append(r.status()).append(" (").append(c.getClaimCode()).append(")\n");
            log.info("Gate L1 {} {} → {}", docLabel, c.getClaimCode(), r.status());
        }
        return sb.toString();
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
