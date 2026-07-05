package vn.techcomlife.marketradar.report;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import vn.techcomlife.marketradar.domain.EvidenceFact;
import vn.techcomlife.marketradar.domain.InterpretedClaim;
import vn.techcomlife.marketradar.domain.InterpretedClaim.GateStatus;
import vn.techcomlife.marketradar.domain.InterpretedClaim.Origin;
import vn.techcomlife.marketradar.domain.InterpretedClaim.Slot;
import vn.techcomlife.marketradar.interpret.GroundingGateL1;
import vn.techcomlife.marketradar.interpret.InterpretationJob;
import vn.techcomlife.marketradar.review.RiskTierRouter;
import vn.techcomlife.marketradar.verify.VerificationJob;
import vn.techcomlife.marketradar.repo.EvidenceFactRepository;
import vn.techcomlife.marketradar.repo.InterpretedClaimRepository;

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

    public ClaimController(InterpretedClaimRepository claims, EvidenceFactRepository facts,
                           InterpretationJob job, GroundingGateL1 gate,
                           VerificationJob verifyJob, RiskTierRouter tierRouter) {
        this.claims = claims;
        this.facts = facts;
        this.job = job;
        this.gate = gate;
        this.verifyJob = verifyJob;
        this.tierRouter = tierRouter;
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

    /** View model để template không phải tự resolve fact từ CSV. */
    public record ClaimView(InterpretedClaim claim, List<EvidenceFact> citedFacts) {}

    @GetMapping("/claims")
    public String claimsAudit(Model model) {
        Map<String, EvidenceFact> factByCode = facts.findAllForReport().stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));
        List<ClaimView> views = claims.findAllForAudit().stream()
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
        List<String> cited = List.of(target.getFactCode());

        GroundingGateL1.GateResult r = gate.check(claimText, cited, List.of(target),
                Set.of(target.getFactCode()));

        InterpretedClaim c = new InterpretedClaim(
                String.format("C-%03d", claims.count() + 1),
                target.getRawDoc(), Slot.WHY_MATTERS, Origin.DEMO_INJECT,
                claimText, String.join(",", cited),
                r.status(), r.detailJson(), "DEMO");
        // Batch 4: DEMO_INJECT → T3 → vào thẳng Reviewer Console
        // (nhịp demo #4: gate chặn sống → mở /review → reviewer sửa trong vài giây)
        c.setRiskTier(tierRouter.assignTier(target.getRawDoc(), Origin.DEMO_INJECT));
        c.setReviewStatus(vn.techcomlife.marketradar.domain.InterpretedClaim.ReviewStatus.PENDING_REVIEW);
        claims.save(c);

        return "Đã inject claim không nguồn (" + c.getClaimCode() + ").\n"
                + "Gate L1 phán: " + r.status() + "\n"
                + "Chi tiết: " + r.detailJson() + "\n"
                + "→ Mở /claims để xem claim bị chặn, hoặc /review để xử lý (Batch 4).";
    }

    private static List<EvidenceFact> resolve(String csv, Map<String, EvidenceFact> byCode) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip).map(byCode::get).filter(Objects::nonNull).toList();
    }
}
