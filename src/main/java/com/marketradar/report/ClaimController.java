package com.marketradar.report;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.InterpretedClaim.GateStatus;
import com.marketradar.domain.InterpretedClaim.Origin;
import com.marketradar.domain.InterpretedClaim.Slot;
import com.marketradar.interpret.GroundingGateL1;
import com.marketradar.interpret.InterpretationJob;
import com.marketradar.review.RiskTierRouter;
import com.marketradar.verify.VerificationJob;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.LlmCallLogRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Batch 3 endpoints:
 *  POST /interpret/run          — chạy AI#3 + Gate L1 (nút chạy tay, demo deterministic)
 *  GET  /claims                 — trang audit: câu ↔ evidence ↔ gate status/lý do
 *  POST /demo/inject-ungrounded — NHỊP DEMO #4: chèn claim "bán chạy nhất tuần"
 *                                 không nguồn → gate chặn SỐNG, lý do hiển thị ở /claims
 *
 * Trang /claims là READ-ONLY audit — Reviewer Console thật (approve/edit/reject,
 * nút duyệt khoá tới khi mở evidence) là scope batch 4.
 */
@Controller
public class ClaimController {

    private final InterpretedClaimRepository claims;
    private final EvidenceFactRepository facts;
    private final InterpretationJob job;
    private final GroundingGateL1 gate;
    private final VerificationJob verifyJob;
    private final RiskTierRouter tierRouter;
    private final LlmCallLogRepository callLog;

    public ClaimController(InterpretedClaimRepository claims, EvidenceFactRepository facts,
                           InterpretationJob job, GroundingGateL1 gate,
                           VerificationJob verifyJob, RiskTierRouter tierRouter,
                           LlmCallLogRepository callLog) {
        this.claims = claims;
        this.facts = facts;
        this.job = job;
        this.gate = gate;
        this.verifyJob = verifyJob;
        this.tierRouter = tierRouter;
        this.callLog = callLog;
    }

    /** Batch 4: chạy Gate L2 (entailment độc lập) cho mọi claim PENDING_VERIFICATION. */
    @PostMapping("/verify/run")
    @ResponseBody
    public String runVerify() {
        return "Kết quả Gate L2:\n" + verifyJob.runOnce();
    }

    @PostMapping("/interpret/run")
    @ResponseBody
    public String runInterpret() {
        return "Kết quả interpret + gate L1:\n" + job.runOnce();
    }

    /**
     * Force Retry append-only: đánh dấu edition SCHEMA_REJECTED hiện tại là superseded
     * (không xoá claim/verification) và xoá replay cache để lần chạy tiếp theo gọi lại.
     * can thiệp SQL tay (đúng thứ đêm nay phải làm thủ công cho doc#135/136).
     * CHỈ xoá khi claim đang là SCHEMA_REJECTED — không cho xoá claim PASS/FAIL đã có
     * nội dung thật (tránh bấm nhầm làm mất audit trail của kết quả hợp lệ).
     */
    @PostMapping("/claims/force-retry/{rawDocId}")
    @ResponseBody
    @Transactional
    public String forceRetry(@PathVariable Long rawDocId) {
        boolean hasSchemaRejected = claims.findAllForAudit().stream()
                .anyMatch(c -> c.getRawDoc() != null && rawDocId.equals(c.getRawDoc().getId())
                        && c.getGateStatus() == GateStatus.SCHEMA_REJECTED);
        if (!hasSchemaRejected) {
            return "Không tìm thấy claim SCHEMA_REJECTED cho doc#" + rawDocId + " — không có gì để retry.";
        }
        callLog.deleteByPurposeAndRawDocId("INTERPRET_DOC", rawDocId);
        return "Đã giữ nguyên mọi claim edition và xoá replay cache của doc#" + rawDocId
                + " — lần chạy POST /interpret/run tiếp theo sẽ thử lại nếu chưa có edition hiện hành.";
    }

    /**
     * Force Retry cho EXEC_SUMMARY: supersede edition lỗi nhưng giữ claim cũ để audit.
     */
    @PostMapping("/claims/force-retry-exec-summary")
    @ResponseBody
    @Transactional
    public String forceRetryExecSummary() {
        boolean stuck = claims.findAllForAudit().stream()
                .anyMatch(c -> c.getSlot() == Slot.EXEC_SUMMARY
                        && c.getGateStatus() == GateStatus.SCHEMA_REJECTED);
        if (!stuck) {
            return "Không tìm thấy EXEC_SUMMARY claim SCHEMA_REJECTED — không có gì để retry.";
        }
        callLog.deleteByPurposeAndRawDocIdIsNull("INTERPRET_EXEC");
        return "Đã giữ nguyên EXEC_SUMMARY audit trail và xoá replay cache"
                + " — lần chạy tiếp theo sẽ thử lại nếu chưa có edition hiện hành.";
    }

    /**
     * Force Retry cho một NARRATIVE edition. Bình thường signature + input hash tự tạo
     * edition mới khi prompt/model/evidence đổi; endpoint này chỉ dùng để yêu cầu thử lại
     * cùng input. Cache narrative dùng rawDocId null nên thao tác xoá áp dụng cả chương.
     */
    @PostMapping("/claims/force-retry-narrative/{chapterCode}")
    @ResponseBody
    @Transactional
    public String forceRetryNarrative(@PathVariable String chapterCode) {
        try {
            com.marketradar.interpret.Chapter.valueOf(chapterCode);
        } catch (IllegalArgumentException e) {
            return "Không nhận diện được chương '" + chapterCode + "'.";
        }
        callLog.deleteByPurposeAndRawDocIdIsNull("INTERPRET_NARRATIVE");
        return "Đã giữ nguyên narrative editions của chương " + chapterCode
                + " và xoá cache — input/prompt/model mới sẽ tạo edition mới ở lần chạy tiếp theo.";
    }

    /** View model để template không phải tự resolve fact từ CSV. */
    public record ClaimView(InterpretedClaim claim, List<EvidenceFact> citedFacts) {}

    @GetMapping("/claims")
    public String claimsAudit(Model model) {
        List<InterpretedClaim> auditedClaims = claims.findAllForAudit();
        List<String> citedCodes = auditedClaims.stream()
                .flatMap(c -> factCodes(c.getFactCodesCsv()).stream()).distinct().toList();
        Map<String, EvidenceFact> factByCode = (citedCodes.isEmpty()
                ? List.<EvidenceFact>of()
                : facts.findAllByFactCodeInForAudit(citedCodes)).stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));
        List<ClaimView> views = auditedClaims.stream()
                .map(c -> new ClaimView(c, resolve(c.getFactCodesCsv(), factByCode)))
                .toList();
        long passCount = views.stream().filter(v -> v.claim().getGateStatus() == GateStatus.PASS).count();
        model.addAttribute("views", views);
        model.addAttribute("passCount", passCount);
        model.addAttribute("failCount", views.size() - passCount);
        return "claims";
    }

    /**
     * Demo moment: claim cố tình vi phạm — trích dẫn fact thật nhưng chứa
     * tên dịch (không verbatim script gốc) + con số bịa. Gate L1 chạy THẬT
     * trên claim này, không dàn dựng kết quả.
     */
    @PostMapping("/demo/inject-ungrounded")
    @ResponseBody
    public String injectUngrounded() {
        List<EvidenceFact> all = facts.findAllForReport();
        if (all.isEmpty()) return "Chưa có evidence fact nào để demo.";
        EvidenceFact target = all.get(0);

        String claimText = "Sản phẩm \"Kim Phúc Trường Doanh\" là sản phẩm bán chạy nhất tuần qua "
                + "với 25.000 hợp đồng khai thác mới.";
        String claimTextEn = "\"Kim Phúc Trường Doanh\" was the best-selling product this week "
                + "with 25,000 newly written policies.";
        List<String> cited = List.of(target.getFactCode());

        GroundingGateL1.GateResult r = gate.checkBilingual(claimText, claimTextEn, cited,
                List.of(target), Set.of(target.getFactCode()));

        InterpretedClaim c = new InterpretedClaim(
                nextClaimCode(),
                target.getRawDoc(), Slot.WHY_MATTERS, Origin.DEMO_INJECT,
                claimText, claimTextEn, String.join(",", cited),
                r.status(), r.detailJson(), "DEMO");
        // Batch 4: DEMO_INJECT → T3 → vào thẳng Reviewer Console
        // (nhịp demo #4: gate chặn sống → mở /review → reviewer sửa trong vài giây)
        c.setRiskTier(tierRouter.assignTier(target.getRawDoc(), Origin.DEMO_INJECT));
        c.setReviewStatus(com.marketradar.domain.InterpretedClaim.ReviewStatus.PENDING_REVIEW);
        claims.save(c);

        return "Đã inject claim không nguồn (" + c.getClaimCode() + ").\n"
                + "Gate L1 phán: " + r.status() + "\n"
                + "Chi tiết: " + r.detailJson() + "\n"
                + "→ Mở /claims để xem claim bị chặn, hoặc /review để xử lý (Batch 4).";
    }

    /** Cùng cách tính với InterpretationJob.nextCode() (fix 2026-07-13: count()+1 vỡ khi có row bị xoá). */
    private String nextClaimCode() {
        int max = claims.findAllClaimCodes().stream()
                .mapToInt(code -> { try { return Integer.parseInt(code.substring(2)); } catch (Exception e) { return 0; } })
                .max().orElse(0);
        return String.format("C-%03d", max + 1);
    }

    private static List<EvidenceFact> resolve(String csv, Map<String, EvidenceFact> byCode) {
        return factCodes(csv).stream().map(byCode::get).filter(Objects::nonNull).toList();
    }

    private static List<String> factCodes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::strip)
                .filter(code -> !code.isBlank()).distinct().toList();
    }
}
