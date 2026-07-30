package com.marketradar.synthesize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.InterpretedClaim.GateStatus;
import com.marketradar.domain.InterpretedClaim.Origin;
import com.marketradar.domain.InterpretedClaim.ReviewStatus;
import com.marketradar.domain.InterpretedClaim.Slot;
import com.marketradar.interpret.EvidencePack;
import com.marketradar.interpret.GroundingGateL1;
import com.marketradar.interpret.Interpreter;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.review.RiskTierRouter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 3 — bước "synthesize": gom fact xuyên nhiều RawDoc (BucketGrouper, code thuần)
 * → Interpreter.interpretSynthesis (AI viết + tự đánh giá decision-relevance)
 * → Gate L1 (code thuần, y hệt InterpretationJob) → lưu InterpretedClaim (bucket+subjectKey,
 * rawDoc=null — đúng tiền lệ EXEC_SUMMARY, vì đây luôn là câu xuyên tài liệu).
 *
 * KHÔNG chạy theo lịch (khác IngestionJob) — manual-trigger, vì phụ thuộc vào EvidenceFact
 * đã có (chạy SAU /ingest/run và /interpret/run trong demo).
 */
@Service
public class SynthesisJob {

    private static final Logger log = LoggerFactory.getLogger(SynthesisJob.class);

    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;
    private final Interpreter interpreter;
    private final GroundingGateL1 gate;
    private final RiskTierRouter tierRouter;
    private final BucketGrouper grouper;
    private final String homeCompany;
    private final int recencyDays;

    public SynthesisJob(EvidenceFactRepository facts, InterpretedClaimRepository claims,
                        Interpreter interpreter, GroundingGateL1 gate, RiskTierRouter tierRouter,
                        BucketGrouper grouper,
                        @Value("${marketradar.home-company:}") String homeCompany,
                        @Value("${marketradar.synthesis.recency-days:120}") int recencyDays) {
        this.facts = facts;
        this.claims = claims;
        this.interpreter = interpreter;
        this.gate = gate;
        this.tierRouter = tierRouter;
        this.grouper = grouper;
        this.homeCompany = homeCompany;
        this.recencyDays = recencyDays;
    }

    @Transactional
    public String runOnce() {
        List<EvidenceFact> visible = facts.findAllForReport().stream()
                .filter(f -> f.getRawDoc().getDuplicateOfId() == null)
                .toList();
        if (visible.isEmpty()) {
            return "Không có evidence fact nào — chưa chạy /ingest/run hoặc chưa có fact.\n";
        }

        List<BucketGrouper.Candidate> candidates = grouper.groupCandidates(visible, homeCompany, recencyDays);
        StringBuilder summary = new StringBuilder();
        int written = 0, skippedEmpty = 0, skippedDup = 0;

        for (var c : candidates) {
            if (claims.existsByBucketAndSubjectKey(c.bucket(), c.subjectKey())) {
                skippedDup++;
                continue;
            }
            EvidencePack pack = new EvidencePack(null, c.facts());
            Interpreter.InterpretOutput out = interpreter.interpretSynthesis(pack, c.bucket(), c.subjectKey());

            if (out.schemaRejected()) {
                InterpretedClaim rej = new InterpretedClaim(nextCode(), null, Slot.SYNTHESIS, Origin.PIPELINE,
                        truncate(out.rawResponse(), 2000), null, GateStatus.SCHEMA_REJECTED,
                        "{\"reason\":\"output không đúng schema JSON\"}", interpreter.providerName())
                        .bucket(c.bucket()).subjectKey(c.subjectKey());
                rej.setRiskTier(tierRouter.assignTier(null, Origin.PIPELINE));
                rej.setReviewStatus(ReviewStatus.PENDING_REVIEW);
                claims.save(rej);
                summary.append(c.bucket()).append('/').append(c.subjectKey())
                        .append(": SCHEMA_REJECTED (").append(rej.getClaimCode()).append(")\n");
                continue;
            }

            if (out.sentences().isEmpty()) {
                skippedEmpty++;
                continue; // LLM tự đánh giá: evidence không đủ decision-relevant — không phải lỗi
            }

            Map<String, EvidenceFact> byCode = pack.byCode();
            for (var s : out.sentences()) {
                List<EvidenceFact> cited = s.factCodes().stream()
                        .map(byCode::get).filter(Objects::nonNull).toList();
                var r = gate.check(s.text(), s.factCodes(), cited, pack.codes());
                InterpretedClaim claim = new InterpretedClaim(nextCode(), null, Slot.SYNTHESIS, Origin.PIPELINE,
                        s.text(), String.join(",", s.factCodes()), r.status(), r.detailJson(),
                        interpreter.providerName())
                        .bucket(c.bucket()).subjectKey(c.subjectKey());
                claim.setRiskTier(tierRouter.assignTier(null, Origin.PIPELINE));
                claim.setReviewStatus(r.status() == GateStatus.PASS
                        ? ReviewStatus.PENDING_VERIFICATION : ReviewStatus.PENDING_REVIEW);
                claims.save(claim);
                written++;
                summary.append(c.bucket()).append('/').append(c.subjectKey()).append(" → ")
                        .append(r.status()).append(" (").append(claim.getClaimCode()).append(")\n");
                log.info("Synthesize {} {} → {}", c.bucket(), claim.getClaimCode(), r.status());
            }
        }

        summary.insert(0, "Synthesize xong: " + candidates.size() + " candidate, " + written + " claim mới, "
                + skippedEmpty + " candidate LLM đánh giá KHÔNG đủ decision-relevant, "
                + skippedDup + " candidate đã tổng hợp trước đó (bỏ qua).\n");
        return summary.toString();
    }

    /** C-001, C-002... — DÙNG CHUNG dãy mã với InterpretationJob (cùng bảng interpreted_claims). */
    private String nextCode() {
        return String.format("C-%03d", claims.count() + 1);
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
