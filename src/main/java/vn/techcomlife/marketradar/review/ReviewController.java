package vn.techcomlife.marketradar.review;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.techcomlife.marketradar.domain.*;
import vn.techcomlife.marketradar.domain.InterpretedClaim.GateStatus;
import vn.techcomlife.marketradar.domain.InterpretedClaim.ReviewStatus;
import vn.techcomlife.marketradar.interpret.GroundingGateL1;
import vn.techcomlife.marketradar.repo.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reviewer Console (Batch 4). Nguyên tắc thiết kế:
 *
 *  1. CHỐNG RUBBER-STAMP (bài học PaperTrail/E2): nút Approve & Force-approve
 *     bị khoá tới khi reviewer MỞ panel evidence. Enforce 2 tầng:
 *     JS mở khoá nút + server từ chối nếu thiếu cờ evidenceViewed.
 *     ⚠️ Trung thực về giới hạn: cờ do client gửi — chặn duyệt VÔ Ý,
 *     không chặn reviewer cố tình lách (giải pháp thật: đo dwell-time
 *     server-side + gold-task sampling — ngoài scope MVP).
 *
 *  2. SỬA = CHẠY LẠI GATE L1: text sau sửa phải qua lại exact-match.
 *     Pass → EDITED_APPROVED. Fail → KHÔNG lưu text, hiện lý do.
 *     Con người đã đối chiếu evidence nên đóng vai verifier L2 cho text sửa —
 *     không gọi lại LLM verifier (quyết định thiết kế, ghi trong NOTES).
 *
 *  3. FORCE-APPROVE có giá: bắt buộc lý do, ghi LabelLog — mỗi lần override
 *     là một datapoint đắt cho label store. Vẫn đòi claim có citation
 *     (Invariant #1 không nhượng bộ kể cả với con người).
 *
 *  4. MỌI hành động → LabelLog (MVP chỉ log, chưa học).
 */
@Controller
public class ReviewController {

    private final InterpretedClaimRepository claims;
    private final EvidenceFactRepository facts;
    private final ClaimVerificationRepository verifications;
    private final LabelLogRepository labels;
    private final GroundingGateL1 gate;
    private final vn.techcomlife.marketradar.alert.HotAlertService alerts;

    public ReviewController(InterpretedClaimRepository claims, EvidenceFactRepository facts,
                            ClaimVerificationRepository verifications,
                            LabelLogRepository labels, GroundingGateL1 gate,
                            vn.techcomlife.marketradar.alert.HotAlertService alerts) {
        this.claims = claims;
        this.facts = facts;
        this.verifications = verifications;
        this.labels = labels;
        this.gate = gate;
        this.alerts = alerts;
    }

    // ---------- Queue ----------

    public record QueueItem(InterpretedClaim claim, String verdict) {}

    @GetMapping("/review")
    public String queue(Model model) {
        List<InterpretedClaim> pending = claims.findByReviewStatusFetched(ReviewStatus.PENDING_REVIEW);
        List<QueueItem> items = pending.stream()
                .map(c -> new QueueItem(c, latestVerdict(c)))
                .toList();
        model.addAttribute("items", items);
        return "review-queue";
    }

    // ---------- Detail ----------

    @GetMapping("/review/{id}")
    public String detail(@PathVariable Long id, Model model) {
        InterpretedClaim c = claims.findByIdFetched(id)
                .orElseThrow(() -> new NoSuchElementException("Không có claim id=" + id));
        model.addAttribute("claim", c);
        model.addAttribute("citedFacts", resolveCited(c));
        model.addAttribute("verification",
                verifications.findFirstByClaimOrderByCreatedAtDescIdDesc(c).orElse(null));
        return "review-detail";
    }

    // ---------- Actions ----------

    @PostMapping("/review/{id}/approve")
    @Transactional
    public String approve(@PathVariable Long id,
                          @RequestParam(defaultValue = "false") boolean evidenceViewed,
                          @RequestParam(defaultValue = "reviewer-demo") String reviewerName,
                          RedirectAttributes redirect) {
        InterpretedClaim c = load(id);
        String err = requirePending(c);
        if (err == null) err = ReviewRules.validateApprove(evidenceViewed);
        if (err != null) return flashBack(redirect, id, err);

        c.setReviewStatus(ReviewStatus.APPROVED);
        claims.save(c);
        labels.save(logOf(c, LabelLog.Action.APPROVE, null, null, null, reviewerName));
        alerts.maybeAlert(c, resolveCited(c), "REVIEW:APPROVE");   // Batch 5: hot alert T3+
        redirect.addFlashAttribute("msg", c.getClaimCode() + " → APPROVED.");
        return "redirect:/review";
    }

    @PostMapping("/review/{id}/edit")
    @Transactional
    public String edit(@PathVariable Long id,
                       @RequestParam String newText,
                       @RequestParam(defaultValue = "false") boolean evidenceViewed,
                       @RequestParam(defaultValue = "reviewer-demo") String reviewerName,
                       RedirectAttributes redirect) {
        InterpretedClaim c = load(id);
        String err = requirePending(c);
        if (err == null) err = ReviewRules.validateEdit(evidenceViewed, newText);
        if (err != null) return flashBack(redirect, id, err);

        // Text sửa CHẠY LẠI Gate L1 với đúng citation cũ — fail loud với cả con người
        List<String> codes = c.getFactCodesCsv() == null || c.getFactCodesCsv().isBlank()
                ? List.of()
                : Arrays.stream(c.getFactCodesCsv().split(",")).map(String::strip).toList();
        List<EvidenceFact> cited = resolveCited(c);
        GroundingGateL1.GateResult r = gate.check(newText.strip(), codes, cited,
                new HashSet<>(codes));

        if (r.status() != GateStatus.PASS) {
            return flashBack(redirect, id, "Text sửa KHÔNG qua Gate L1 (" + r.status()
                    + ") — text cũ giữ nguyên. Chi tiết: " + r.detailJson()
                    + " — Sửa lại hoặc dùng force-approve (cần lý do).");
        }

        String oldText = c.getTextVi();
        c.setTextVi(newText.strip());
        c.setGateStatus(r.status());
        c.setGateDetailJson(r.detailJson());
        c.setReviewStatus(ReviewStatus.EDITED_APPROVED);
        claims.save(c);
        labels.save(logOf(c, LabelLog.Action.EDIT_APPROVE, oldText, newText.strip(),
                null, reviewerName));
        alerts.maybeAlert(c, cited, "REVIEW:EDIT_APPROVE");        // Batch 5: hot alert T3+
        redirect.addFlashAttribute("msg", c.getClaimCode()
                + " → EDITED_APPROVED (text mới đã qua lại Gate L1).");
        return "redirect:/review";
    }

    @PostMapping("/review/{id}/force-approve")
    @Transactional
    public String forceApprove(@PathVariable Long id,
                               @RequestParam String reason,
                               @RequestParam(defaultValue = "false") boolean evidenceViewed,
                               @RequestParam(defaultValue = "reviewer-demo") String reviewerName,
                               RedirectAttributes redirect) {
        InterpretedClaim c = load(id);
        String err = requirePending(c);
        if (err == null) err = ReviewRules.validateForceApprove(
                evidenceViewed, reason, c.getFactCodesCsv());
        if (err != null) return flashBack(redirect, id, err);

        c.setReviewStatus(ReviewStatus.FORCE_APPROVED);
        claims.save(c);
        labels.save(logOf(c, LabelLog.Action.FORCE_APPROVE, null, null,
                reason.strip(), reviewerName));
        alerts.maybeAlert(c, resolveCited(c), "REVIEW:FORCE_APPROVE"); // Batch 5: hot alert T3+
        redirect.addFlashAttribute("msg", c.getClaimCode()
                + " → FORCE_APPROVED (override đã ghi log kèm lý do).");
        return "redirect:/review";
    }

    @PostMapping("/review/{id}/reject")
    @Transactional
    public String reject(@PathVariable Long id,
                         @RequestParam String reason,
                         @RequestParam(defaultValue = "reviewer-demo") String reviewerName,
                         RedirectAttributes redirect) {
        InterpretedClaim c = load(id);
        String err = requirePending(c);
        if (err == null) err = ReviewRules.validateReject(reason);
        if (err != null) return flashBack(redirect, id, err);

        c.setReviewStatus(ReviewStatus.REJECTED);
        claims.save(c);
        labels.save(logOf(c, LabelLog.Action.REJECT, null, null, reason.strip(), reviewerName));
        redirect.addFlashAttribute("msg", c.getClaimCode() + " → REJECTED.");
        return "redirect:/review";
    }

    // ---------- Label log audit page ----------

    @GetMapping("/labels")
    public String labelLog(Model model) {
        model.addAttribute("labels", labels.findAllByOrderByCreatedAtDescIdDesc());
        return "labels";
    }

    // ---------- Helpers ----------

    private InterpretedClaim load(Long id) {
        return claims.findByIdFetched(id)
                .orElseThrow(() -> new NoSuchElementException("Không có claim id=" + id));
    }

    /** Chỉ claim PENDING_REVIEW mới nhận hành động — chặn double-submit/URL mò. */
    private static String requirePending(InterpretedClaim c) {
        return c.getReviewStatus() == ReviewStatus.PENDING_REVIEW ? null
                : "Claim " + c.getClaimCode() + " không ở trạng thái PENDING_REVIEW (hiện: "
                  + c.getReviewStatus() + ") — không nhận hành động nữa.";
    }

    private String flashBack(RedirectAttributes redirect, Long id, String msg) {
        redirect.addFlashAttribute("msg", msg);
        return "redirect:/review/" + id;
    }

    private LabelLog logOf(InterpretedClaim c, LabelLog.Action action,
                           String oldText, String newText, String reason, String reviewerName) {
        return new LabelLog(c.getClaimCode(), action, oldText, newText, reason,
                c.getGateStatus().name(), latestVerdict(c), c.getRiskTier(),
                reviewerName == null || reviewerName.isBlank() ? "reviewer-demo" : reviewerName.strip());
    }

    private String latestVerdict(InterpretedClaim c) {
        return verifications.findFirstByClaimOrderByCreatedAtDescIdDesc(c)
                .map(v -> v.getVerdict().name()).orElse("—");
    }

    private List<EvidenceFact> resolveCited(InterpretedClaim c) {
        String csv = c.getFactCodesCsv();
        if (csv == null || csv.isBlank()) return List.of();
        Map<String, EvidenceFact> byCode = facts.findAllForReport().stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));
        return Arrays.stream(csv.split(","))
                .map(String::strip).map(byCode::get).filter(Objects::nonNull).toList();
    }
}
