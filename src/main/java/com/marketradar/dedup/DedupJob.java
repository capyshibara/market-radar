package com.marketradar.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.marketradar.domain.DedupDecision;
import com.marketradar.domain.DedupDecision.Method;
import com.marketradar.domain.DedupDecision.Verdict;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.repo.DedupDecisionRepository;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.repo.RawDocRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * Dedup/Conflict MVP (Batch 5, bước 9). Với mọi CẶP raw_docs (parse OK)
 * trong cửa sổ 72h, chưa quyết trước đó, chạy thang DedupRules:
 *
 *   exact URL/hash → Jaccard title → (vùng xám) LLM pairwise same-event
 *
 * DUPLICATE_CONTENT → chọn bản GIỮ theo authority > recency. SAME_EVENT_INDEPENDENT
 * giữ CẢ HAI tài liệu để fact clustering có thể corroborate đúng. Bản sao thua chỉ
 * bị đánh dấu duplicateOfId (lọc khỏi synthesis) — không xoá gì (audit).
 *
 * LLM pairwise dùng WRITER client (@Primary). Đây KHÔNG phải bước verify đối kháng
 * (Invariant #2 chỉ ràng cặp writer/verifier của Gate L2) nên không cần khác họ.
 * Chạy STUB → không gọi LLM, vùng xám route thẳng NEEDS_REVIEW (không đoán).
 */
@Service
public class DedupJob {

    private static final Logger log = LoggerFactory.getLogger(DedupJob.class);

    private static final String SYSTEM = """
            MODE:DEDUP_PAIR — Phân loại quan hệ giữa HAI tài liệu.
            DUPLICATE_CONTENT = sao chép/đăng lại/thông cáo được lặp lại, không có đóng góp
            dữ kiện hay phân tích độc lập đáng kể. SAME_EVENT_INDEPENDENT = cùng sự kiện nhưng
            bài viết có tường thuật, dữ kiện, phỏng vấn hoặc phân tích độc lập đáng kể.
            DIFFERENT = khác sự kiện/chủ đề. Không được coi "cùng sự kiện" là "bản trùng".
            Trả về DUY NHẤT JSON, không markdown:
            {"relationship":"DUPLICATE_CONTENT|SAME_EVENT_INDEPENDENT|DIFFERENT"}
            Nếu không chắc, output không đúng schema sẽ được chuyển cho người duyệt.""";

    private final RawDocRepository rawDocs;
    private final DedupDecisionRepository decisions;
    private final LlmCallLogRepository callLog;
    private final LlmClient llm;   // WRITER (@Primary)
    private final double jaccardSame;
    private final double jaccardGray;
    private final double contentDuplicate;
    private final long windowMillis;
    private final boolean replayCache;
    private final TransactionTemplate transactions;

    public DedupJob(RawDocRepository rawDocs, DedupDecisionRepository decisions,
                    LlmCallLogRepository callLog, LlmClient llm,
                    @Value("${marketradar.dedup.jaccard-same:0.90}") double jaccardSame,
                    @Value("${marketradar.dedup.jaccard-gray:0.50}") double jaccardGray,
                    @Value("${marketradar.dedup.content-duplicate:0.92}") double contentDuplicate,
                    @Value("${marketradar.dedup.window-hours:72}") long windowHours,
                    @Value("${marketradar.llm.replay-cache:true}") boolean replayCache,
                    PlatformTransactionManager transactionManager) {
        this.rawDocs = rawDocs;
        this.decisions = decisions;
        this.callLog = callLog;
        this.llm = llm;
        this.jaccardSame = jaccardSame;
        this.jaccardGray = jaccardGray;
        this.contentDuplicate = contentDuplicate;
        this.windowMillis = windowHours * 60L * 60 * 1000;
        this.replayCache = replayCache;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public String runOnce() {
        List<RawDoc> docs = rawDocs.findAll().stream()
                .filter(d -> d.getParseStatus() == RawDoc.ParseStatus.OK)
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        if (docs.size() < 2) return "Not enough documents (parse OK) to dedup yet.\n";

        StringBuilder sb = new StringBuilder();
        int same = 0, diff = 0, flagged = 0, skipped = 0;

        for (int i = 0; i < docs.size(); i++) {
            for (int j = i + 1; j < docs.size(); j++) {
                RawDoc a = docs.get(i), b = docs.get(j);
                // Doc đã là bản trùng của doc khác → không so tiếp (tránh chuỗi trùng lồng nhau)
                if (a.getDuplicateOfId() != null || b.getDuplicateOfId() != null) { skipped++; continue; }
                if (decisions.existsByDocAIdAndDocBId(a.getId(), b.getId())) { skipped++; continue; }
                if (!DedupRules.within72h(timeOf(a), timeOf(b))) continue;

                Outcome o = decidePair(a, b);
                try {
                    transactions.executeWithoutResult(status -> persistPair(a, b, o));
                } catch (RuntimeException persistenceError) {
                    flagged++;
                    log.error("Could not persist dedup pair doc#{} / doc#{}; continuing",
                            a.getId(), b.getId(), persistenceError);
                    sb.append("doc#").append(a.getId()).append(" ↔ doc#").append(b.getId())
                            .append(" PERSISTENCE_ERROR — ").append(persistenceError.getMessage()).append('\n');
                    continue;
                }

                if (o.verdict == Verdict.DUPLICATE_CONTENT && o.winner != null) {
                    same++;
                    sb.append("doc#").append(a.getId()).append(" ↔ doc#").append(b.getId())
                      .append(" [").append(o.method).append("] DUPLICATE_CONTENT — kept doc#")
                      .append(o.winner.getId()).append(" (").append(o.detail).append(")\n");
                } else if (o.verdict == Verdict.SAME_EVENT_INDEPENDENT) {
                    diff++;
                    sb.append("doc#").append(a.getId()).append(" ↔ doc#").append(b.getId())
                            .append(" [").append(o.method).append("] SAME_EVENT_INDEPENDENT")
                            .append(" — retained both for corroboration\n");
                } else if (o.verdict == Verdict.NEEDS_REVIEW) {
                    flagged++;
                    sb.append("doc#").append(a.getId()).append(" ↔ doc#").append(b.getId())
                      .append(" [").append(o.method).append("] NEEDS_REVIEW — ")
                      .append(o.detail).append('\n');
                } else {
                    diff++;
                }
                log.info("Dedup doc#{} ↔ doc#{} [{}] → {}", a.getId(), b.getId(), o.method, o.verdict);
            }
        }
        sb.insert(0, "Dedup done: " + same + " duplicate pair(s) marked, " + diff
                + " different, " + flagged + " awaiting human review (/dedup), " + skipped + " pair(s) skipped.\n");
        return sb.toString();
    }

    /** One pair decision and its loser marker are committed atomically after any LLM call. */
    private void persistPair(RawDoc a, RawDoc b, Outcome outcome) {
        decisions.save(new DedupDecision(a.getId(), b.getId(), a.getTitle(), b.getTitle(),
                outcome.method, outcome.score, outcome.verdict,
                outcome.winner == null ? null : outcome.winner.getId(), outcome.detail));
        if (outcome.verdict == Verdict.DUPLICATE_CONTENT && outcome.winner != null) {
            RawDoc loser = outcome.winner == a ? b : a;
            loser.setDuplicateOfId(outcome.winner.getId());
            rawDocs.save(loser);
        }
    }

    // ---------- Thang quyết định (mirror DedupRules.decidePair, giữ lại method + detail) ----------

    private record Outcome(Method method, Double score, Verdict verdict, RawDoc winner, String detail) {}

    private Outcome decidePair(RawDoc a, RawDoc b) {
        if (a.getUrl() != null && a.getUrl().equals(b.getUrl()))
            return duplicateContent(a, b, Method.EXACT_URL, null);
        if (a.getContentHash() != null && a.getContentHash().equals(b.getContentHash()))
            return duplicateContent(a, b, Method.EXACT_HASH, null);

        double content = DedupRules.contentJaccard(a.getRawText(), b.getRawText());
        if (content >= contentDuplicate) {
            return duplicateContent(a, b, Method.JACCARD_TITLE, content);
        }

        double j = DedupRules.titleJaccard(a.getTitle(), b.getTitle());
        if (j < jaccardGray)
            return new Outcome(Method.JACCARD_TITLE, j, Verdict.DIFFERENT, null,
                    "Jaccard " + fmt(j) + " < gray threshold " + fmt(jaccardGray));

        // Even very high title overlap is only a candidate: publishers can use the
        // same press-release headline while adding independent reporting. The high
        // threshold controls diagnostics, never deterministic deletion.
        String overlapBand = j >= jaccardSame ? "high headline overlap" : "gray headline overlap";
        if ("STUB".equals(llm.providerName()))
            return new Outcome(Method.LLM_PAIRWISE, j, Verdict.NEEDS_REVIEW, null,
                    overlapBand + " (Jaccard " + fmt(j) + ") + LLM is STUB — not guessing, awaiting human review.");

        DedupRules.ContentRelationship relationship = askLlm(a, b);
        if (relationship == null)
            return new Outcome(Method.LLM_PAIRWISE, j, Verdict.NEEDS_REVIEW, null,
                    overlapBand + " (Jaccard " + fmt(j) + "), LLM output unparseable — awaiting human review.");
        return switch (relationship) {
            case DUPLICATE_CONTENT -> duplicateContent(a, b, Method.LLM_PAIRWISE, j);
            case SAME_EVENT_INDEPENDENT -> new Outcome(Method.LLM_PAIRWISE, j,
                    Verdict.SAME_EVENT_INDEPENDENT, null,
                    "Independent reporting retained for fact-level corroboration.");
            case DIFFERENT -> new Outcome(Method.LLM_PAIRWISE, j, Verdict.DIFFERENT, null,
                    "LLM pairwise: different event (Jaccard " + fmt(j) + ").");
        };
    }

    /** DUPLICATE_CONTENT → apply retention rule; 'F' means human review. */
    private Outcome duplicateContent(RawDoc a, RawDoc b, Method method, Double score) {
        char w = DedupRules.pickWinner(
                a.getSource().getAuthority().credibilityScore(), publishedMillis(a),
                b.getSource().getAuthority().credibilityScore(), publishedMillis(b));
        return switch (w) {
            case 'A' -> new Outcome(method, score, Verdict.DUPLICATE_CONTENT, a,
                    winReason(a, b));
            case 'B' -> new Outcome(method, score, Verdict.DUPLICATE_CONTENT, b,
                    winReason(b, a));
            default -> new Outcome(method, score, Verdict.NEEDS_REVIEW, null,
                    "Duplicate content but SAME source authority and no clear time order"
                    + " — flagged for reviewer (rule: never auto-decide).");
        };
    }

    private static String winReason(RawDoc winner, RawDoc loser) {
        if (winner.getSource().getAuthority() != loser.getSource().getAuthority())
            return winner.getSource().getAuthority() + " ("
                    + winner.getSource().getAuthority().credibilityScore() + ") beats "
                    + loser.getSource().getAuthority() + " ("
                    + loser.getSource().getAuthority().credibilityScore() + ")";
        return "newer > older: same source authority, more recently published wins";
    }

    // ---------- LLM pairwise + replay-cache (cùng cơ chế các job cũ) ----------

    private DedupRules.ContentRelationship askLlm(RawDoc a, RawDoc b) {
        String user = "TÀI LIỆU A:\nTiêu đề: " + nvl(a.getTitle())
                + "\nTrích đoạn: " + excerpt(a.getRawText())
                + "\n\nTÀI LIỆU B:\nTiêu đề: " + nvl(b.getTitle())
                + "\nTrích đoạn: " + excerpt(b.getRawText());
        // Hash gồm providerName (fix 2026-07-15, đồng bộ Interpreter/Extractor/Verifier):
        // đổi model xong không replay nhầm response của model cũ.
        String hash = sha256(llm.providerName() + "\n===\n" + SYSTEM + "\n---\n" + user);
        String raw;
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(hash, 0);
            if (cached.isPresent()) return DedupRules.parseRelationship(cached.get().getResponseText());
        }
        long t0 = System.currentTimeMillis();
        try {
            // temperature=null — cần deterministic, không cần đa dạng
            raw = llm.complete(SYSTEM, user, null);
            callLog.save(new LlmCallLog("DEDUP_PAIR", llm.providerName(), hash, 0,
                    raw, a.getId(), System.currentTimeMillis() - t0));
        } catch (LlmException e) {
            log.error("DEDUP_PAIR lỗi LLM: {}", e.getMessage());
            return null;
        }
        return DedupRules.parseRelationship(raw);
    }

    // ---------- helpers ----------

    private static long timeOf(RawDoc d) {
        Instant t = d.getPublishedAt() != null ? d.getPublishedAt() : d.getFetchedAt();
        return t.toEpochMilli();
    }

    private static Long publishedMillis(RawDoc d) {
        return d.getPublishedAt() == null ? null : d.getPublishedAt().toEpochMilli();
    }

    private static String excerpt(String text) {
        if (text == null) return "(trống)";
        String t = text.strip();
        if (t.length() <= 1800) return t;
        int segment = 600;
        int middle = Math.max(segment, (t.length() - segment) / 2);
        return t.substring(0, segment) + "\n[…MIDDLE…]\n"
                + t.substring(middle, Math.min(t.length(), middle + segment))
                + "\n[…END…]\n" + t.substring(t.length() - segment);
    }

    private static String nvl(String s) { return s == null ? "(không tiêu đề)" : s; }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.2f", d); }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
