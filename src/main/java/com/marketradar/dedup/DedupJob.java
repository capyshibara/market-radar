package com.marketradar.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * SAME_EVENT → chọn bản GIỮ theo rule: official > media · mới > cũ ·
 * cùng tier không phân định → NEEDS_REVIEW (flag reviewer tại /dedup, KHÔNG tự quyết).
 * Bản THUA chỉ bị đánh dấu duplicateOfId (lọc khỏi report) — không xoá gì (audit).
 *
 * LLM pairwise dùng WRITER client (@Primary). Đây KHÔNG phải bước verify đối kháng
 * (Invariant #2 chỉ ràng cặp writer/verifier của Gate L2) nên không cần khác họ.
 * Chạy STUB → không gọi LLM, vùng xám route thẳng NEEDS_REVIEW (không đoán).
 */
@Service
public class DedupJob {

    private static final Logger log = LoggerFactory.getLogger(DedupJob.class);

    private static final String SYSTEM = """
            MODE:DEDUP_PAIR — Bạn nhận TIÊU ĐỀ + trích đoạn đầu của HAI tài liệu tin tức.
            Nhiệm vụ DUY NHẤT: hai tài liệu có nói về CÙNG MỘT SỰ KIỆN không
            (cùng công ty, cùng hành động, cùng khung thời gian)?
            Trả về DUY NHẤT JSON, không giải thích, không markdown:
            {"same_event": true} hoặc {"same_event": false}
            Nếu không chắc chắn, trả {"same_event": false} — hệ thống sẽ để người quyết.""";

    private final RawDocRepository rawDocs;
    private final DedupDecisionRepository decisions;
    private final LlmCallLogRepository callLog;
    private final LlmClient llm;   // WRITER (@Primary)
    private final double jaccardSame;
    private final double jaccardGray;
    private final long windowMillis;
    private final boolean replayCache;

    public DedupJob(RawDocRepository rawDocs, DedupDecisionRepository decisions,
                    LlmCallLogRepository callLog, LlmClient llm,
                    @Value("${marketradar.dedup.jaccard-same:0.90}") double jaccardSame,
                    @Value("${marketradar.dedup.jaccard-gray:0.50}") double jaccardGray,
                    @Value("${marketradar.dedup.window-hours:72}") long windowHours,
                    @Value("${marketradar.llm.replay-cache:true}") boolean replayCache) {
        this.rawDocs = rawDocs;
        this.decisions = decisions;
        this.callLog = callLog;
        this.llm = llm;
        this.jaccardSame = jaccardSame;
        this.jaccardGray = jaccardGray;
        this.windowMillis = windowHours * 60L * 60 * 1000;
        this.replayCache = replayCache;
    }

    @Transactional
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
                DedupDecision d = new DedupDecision(a.getId(), b.getId(),
                        a.getTitle(), b.getTitle(), o.method, o.score, o.verdict,
                        o.winner == null ? null : o.winner.getId(), o.detail);
                decisions.save(d);

                if (o.verdict == Verdict.SAME_EVENT && o.winner != null) {
                    RawDoc loser = (o.winner == a) ? b : a;
                    loser.setDuplicateOfId(o.winner.getId());
                    rawDocs.save(loser);
                    same++;
                    sb.append("doc#").append(a.getId()).append(" ↔ doc#").append(b.getId())
                      .append(" [").append(o.method).append("] SAME_EVENT — kept doc#")
                      .append(o.winner.getId()).append(" (").append(o.detail).append(")\n");
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

    // ---------- Thang quyết định (mirror DedupRules.decidePair, giữ lại method + detail) ----------

    private record Outcome(Method method, Double score, Verdict verdict, RawDoc winner, String detail) {}

    private Outcome decidePair(RawDoc a, RawDoc b) {
        if (a.getUrl() != null && a.getUrl().equals(b.getUrl()))
            return sameEvent(a, b, Method.EXACT_URL, null);
        if (a.getContentHash() != null && a.getContentHash().equals(b.getContentHash()))
            return sameEvent(a, b, Method.EXACT_HASH, null);

        double j = DedupRules.titleJaccard(a.getTitle(), b.getTitle());
        if (j >= jaccardSame) return sameEvent(a, b, Method.JACCARD_TITLE, j);
        if (j < jaccardGray)
            return new Outcome(Method.JACCARD_TITLE, j, Verdict.DIFFERENT, null,
                    "Jaccard " + fmt(j) + " < gray threshold " + fmt(jaccardGray));

        // Vùng xám → LLM pairwise
        if ("STUB".equals(llm.providerName()))
            return new Outcome(Method.LLM_PAIRWISE, j, Verdict.NEEDS_REVIEW, null,
                    "Gray zone (Jaccard " + fmt(j) + ") + LLM is STUB — not guessing, awaiting human review.");

        Boolean sameEvent = askLlm(a, b);
        if (sameEvent == null)
            return new Outcome(Method.LLM_PAIRWISE, j, Verdict.NEEDS_REVIEW, null,
                    "Gray zone (Jaccard " + fmt(j) + "), LLM output unparseable — awaiting human review.");
        if (!sameEvent)
            return new Outcome(Method.LLM_PAIRWISE, j, Verdict.DIFFERENT, null,
                    "LLM pairwise: different event (Jaccard " + fmt(j) + ").");
        return sameEvent(a, b, Method.LLM_PAIRWISE, j);
    }

    /** SAME_EVENT → áp rule xung đột chọn bản giữ; 'F' → NEEDS_REVIEW. */
    private Outcome sameEvent(RawDoc a, RawDoc b, Method method, Double score) {
        char w = DedupRules.pickWinner(
                a.getSource().getTier(), publishedMillis(a),
                b.getSource().getTier(), publishedMillis(b));
        return switch (w) {
            case 'A' -> new Outcome(method, score, Verdict.SAME_EVENT, a,
                    winReason(a, b));
            case 'B' -> new Outcome(method, score, Verdict.SAME_EVENT, b,
                    winReason(b, a));
            default -> new Outcome(method, score, Verdict.NEEDS_REVIEW, null,
                    "Same event but SAME source tier and no clear time order"
                    + " — flagged for reviewer (rule: never auto-decide).");
        };
    }

    private static String winReason(RawDoc winner, RawDoc loser) {
        if (winner.getSource().getTier() != loser.getSource().getTier())
            return "official > media: tier " + winner.getSource().getTier()
                    + " beats tier " + loser.getSource().getTier();
        return "newer > older: same tier, more recently published wins";
    }

    // ---------- LLM pairwise + replay-cache (cùng cơ chế các job cũ) ----------

    private Boolean askLlm(RawDoc a, RawDoc b) {
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
            if (cached.isPresent()) return DedupRules.parseSameEvent(cached.get().getResponseText());
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
        return DedupRules.parseSameEvent(raw);
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
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
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
