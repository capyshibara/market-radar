package com.marketradar.dedup;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.repo.DedupDecisionRepository;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Batch 5:
 *  GET  /dedup                — audit quyết định dedup/conflict; NEEDS_REVIEW nổi lên đầu
 *  POST /dedup/run            — chạy dedup tay (demo deterministic, cùng pattern /ingest/run)
 *  POST /demo/inject-duplicate — NHỊP DEMO: chèn bản "đăng lại" của doc mới nhất
 *                               (title y hệt, URL khác, nguồn tier thấp hơn, publishedAt cũ hơn)
 *                               → /dedup/run bắt SỐNG bằng Jaccard, giữ bản chính thống.
 */
@Controller
public class DedupController {

    private final DedupJob dedupJob;
    private final DedupDecisionRepository decisions;
    private final RawDocRepository rawDocs;
    private final SourceRepository sources;

    public DedupController(DedupJob dedupJob, DedupDecisionRepository decisions,
                           RawDocRepository rawDocs, SourceRepository sources) {
        this.dedupJob = dedupJob;
        this.decisions = decisions;
        this.rawDocs = rawDocs;
        this.sources = sources;
    }

    @GetMapping("/dedup")
    public String dedupPage(Model model) {
        var all = decisions.findAllByOrderByCreatedAtDescIdDesc();
        model.addAttribute("needsReview", all.stream()
                .filter(d -> d.getVerdict() == com.marketradar.domain.DedupDecision.Verdict.NEEDS_REVIEW)
                .toList());
        model.addAttribute("decisions", all);
        return "dedup";
    }

    @PostMapping("/dedup/run")
    @ResponseBody
    public String runDedup() {
        return "Kết quả dedup/conflict:\n" + dedupJob.runOnce();
    }

    @PostMapping("/demo/inject-duplicate")
    @ResponseBody
    @Transactional
    public String injectDuplicate() {
        List<RawDoc> ok = rawDocs.findAll().stream()
                .filter(d -> d.getParseStatus() == RawDoc.ParseStatus.OK
                        && d.getDuplicateOfId() == null)
                .toList();
        if (ok.isEmpty()) return "Chưa có tài liệu (parse OK) nào để nhân bản demo.";
        RawDoc orig = ok.get(ok.size() - 1);

        // Nguồn tier THẤP hơn (số lớn hơn) nếu có — để rule official>media giữ bản gốc;
        // không có thì dùng chính nguồn gốc, khi đó rule mới>cũ giữ bản gốc (publishedAt cũ hơn 1h).
        Source dupSource = sources.findAll().stream()
                .filter(s -> s.getTier() > orig.getSource().getTier())
                .max(Comparator.comparingInt(Source::getTier))
                .orElse(orig.getSource());

        Instant origTime = orig.getPublishedAt() != null ? orig.getPublishedAt() : orig.getFetchedAt();
        String rawText = (orig.getRawText() == null ? "" : orig.getRawText())
                + "\n[Bản đăng lại phục vụ demo dedup]";
        RawDoc dup = new RawDoc(dupSource,
                orig.getUrl() + (orig.getUrl().contains("?") ? "&" : "?") + "repost=demo",
                orig.getTitle(),                          // title Y HỆT → Jaccard = 1.0, bắt deterministic
                origTime.minus(1, ChronoUnit.HOURS),      // cũ hơn → nếu cùng tier, bản gốc vẫn thắng
                Instant.now(), sha256(rawText), rawText,
                orig.getLanguage(), RawDoc.ParseStatus.OK,
                "DEMO_INJECT_DUPLICATE của doc#" + orig.getId());
        dup.setSampleData(true);
        rawDocs.save(dup);

        return "Đã chèn bản trùng demo: doc#" + dup.getId() + " (nguồn " + dupSource.getCode()
                + ", tier " + dupSource.getTier() + ") nhân bản từ doc#" + orig.getId()
                + " (tier " + orig.getSource().getTier() + ").\n"
                + "→ Chạy POST /dedup/run để hệ thống bắt SỐNG, rồi xem /dedup và /report/weekly.";
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
