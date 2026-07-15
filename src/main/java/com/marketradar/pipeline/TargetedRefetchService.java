package com.marketradar.pipeline;

import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.TargetedRefetchAttempt;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.parse.ContentParsers;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.TargetedRefetchAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded remediation for shallow documents. Planning is read-only; mutation is
 * only available for explicit document IDs and an affirmative confirmation.
 */
@Service
public class TargetedRefetchService {

    public static final int MAX_DOCUMENTS = TargetedRefetchPolicy.MAX_DOCUMENTS;
    public static final int MIN_FULL_TEXT_CHARS = TargetedRefetchPolicy.MIN_FULL_TEXT_CHARS;

    private final RawDocRepository docs;
    private final TargetedRefetchAttemptRepository attempts;
    private final SafeFetcher fetcher;
    private final ContentParsers parsers;

    public TargetedRefetchService(RawDocRepository docs,
                                  TargetedRefetchAttemptRepository attempts,
                                  SafeFetcher fetcher, ContentParsers parsers) {
        this.docs = docs;
        this.attempts = attempts;
        this.fetcher = fetcher;
        this.parsers = parsers;
    }

    /** No writes and no network calls. Empty IDs means the first bounded remediation batch. */
    @Transactional(readOnly = true)
    public Plan plan(List<Long> requestedIds) {
        List<Long> ids = TargetedRefetchPolicy.normalizeIds(requestedIds, false);
        List<RawDoc> examined = docs.findAllWithSource().stream()
                .sorted(Comparator.comparing(RawDoc::getId))
                .filter(doc -> ids.isEmpty() || ids.contains(doc.getId()))
                .toList();

        Map<String, Integer> funnel = new LinkedHashMap<>();
        funnel.put("examined", examined.size());
        funnel.put("sample_excluded", 0);
        funnel.put("duplicate_excluded", 0);
        funnel.put("parse_status_excluded", 0);
        funnel.put("not_shallow_excluded", 0);
        funnel.put("eligible", 0);

        List<Candidate> eligible = new ArrayList<>();
        for (RawDoc doc : examined) {
            if (doc.isSampleData()) { increment(funnel, "sample_excluded"); continue; }
            if (doc.getDuplicateOfId() != null) { increment(funnel, "duplicate_excluded"); continue; }
            if (doc.getParseStatus() != RawDoc.ParseStatus.OK) {
                increment(funnel, "parse_status_excluded"); continue;
            }
            TargetedRefetchPolicy.EligibilityReason reason = eligibility(doc);
            if (reason == null) { increment(funnel, "not_shallow_excluded"); continue; }
            increment(funnel, "eligible");
            String allowedFetchHost = TargetedRefetchPolicy.articleFetchHost(
                    doc.getSource().getCode(), doc.getSource().getAllowedHost(), safeHost(doc.getUrl()));
            eligible.add(new Candidate(doc.getId(), doc.getSource().getCode(), doc.getUrl(),
                    reason, chars(doc), allowedFetchHost != null, allowedFetchHost,
                    "DRY_RUN_ONLY"));
        }
        List<Candidate> returned = eligible.stream().limit(MAX_DOCUMENTS).toList();
        return new Plan(false, MAX_DOCUMENTS, eligible.size(), returned.size(),
                eligible.size() - returned.size(), Map.copyOf(funnel), returned,
                "Execute only explicit rawDocIds (max 25) with confirm=true");
    }

    /** Executes only explicit IDs; old content remains untouched on every non-success status. */
    @Transactional
    public Execution execute(List<Long> requestedIds, boolean confirm) {
        if (!confirm) throw new IllegalArgumentException("confirm=true is required for mutation");
        List<Long> ids = TargetedRefetchPolicy.normalizeIds(requestedIds, true);
        List<Result> results = new ArrayList<>();
        for (Long id : ids) results.add(executeOne(id));
        long successes = results.stream()
                .filter(r -> r.status() == TargetedRefetchAttempt.Status.SUCCESS).count();
        return new Execution(true, ids.size(), (int) successes, ids.size() - (int) successes,
                List.copyOf(results));
    }

    private Result executeOne(Long id) {
        RawDoc doc = docs.findByIdWithSource(id).orElse(null);
        if (doc == null) {
            return record(id, null, null, "REQUESTED_ID",
                    TargetedRefetchAttempt.Status.DOCUMENT_NOT_FOUND, null, null,
                    "No RawDoc exists for the requested ID");
        }
        TargetedRefetchPolicy.EligibilityReason reason = eligibility(doc);
        if (doc.isSampleData() || doc.getDuplicateOfId() != null
                || doc.getParseStatus() != RawDoc.ParseStatus.OK || reason == null) {
            return record(doc.getId(), doc.getSource().getCode(), doc.getUrl(),
                    reason == null ? "NOT_SHALLOW" : reason.name(),
                    TargetedRefetchAttempt.Status.NOT_ELIGIBLE, doc.getContentHash(), null,
                    "Document is sample, duplicate, non-OK, or already has sufficient verified text");
        }

        Source source = doc.getSource();
        String allowedHost = TargetedRefetchPolicy.articleFetchHost(
                source.getCode(), source.getAllowedHost(), safeHost(doc.getUrl()));
        if (allowedHost == null) {
            return record(doc.getId(), source.getCode(), doc.getUrl(), reason.name(),
                    TargetedRefetchAttempt.Status.HOST_REJECTED, doc.getContentHash(), null,
                    "Document URL is outside the source host and explicit article-host override");
        }

        final ContentParsers.ParsedText parsed;
        try {
            if (source.getType() == Source.SourceType.PDF) {
                parsed = parsers.parsePdf(fetcher.fetch(doc.getUrl(), allowedHost,
                        SafeFetcher.ExpectedKind.PDF).body());
            } else {
                parsed = parsers.parseArticleHtml(fetcher.fetch(doc.getUrl(), allowedHost,
                        SafeFetcher.ExpectedKind.HTML).body());
            }
        } catch (Exception e) {
            return record(doc.getId(), source.getCode(), doc.getUrl(), reason.name(),
                    TargetedRefetchAttempt.Status.FETCH_OR_PARSE_FAILED,
                    doc.getContentHash(), null, concise(e.getMessage()));
        }

        String newText = parsed.text();
        if (newText == null || newText.strip().length() < MIN_FULL_TEXT_CHARS) {
            return record(doc.getId(), source.getCode(), doc.getUrl(), reason.name(),
                    TargetedRefetchAttempt.Status.INSUFFICIENT_TEXT,
                    doc.getContentHash(), null, "Parsed text below 600-character safety floor");
        }
        String newHash = sha256(normalizeForHash(newText));
        if (docs.existsByContentHashAndIdNot(newHash, doc.getId())) {
            return record(doc.getId(), source.getCode(), doc.getUrl(), reason.name(),
                    TargetedRefetchAttempt.Status.DUPLICATE_CONTENT,
                    doc.getContentHash(), newHash, "Fetched body duplicates another RawDoc; prior text preserved");
        }

        String previousHash = doc.getContentHash();
        doc.upgradeToFullText(newHash, newText, parsed.note());
        docs.save(doc);
        return record(doc.getId(), source.getCode(), doc.getUrl(), reason.name(),
                TargetedRefetchAttempt.Status.SUCCESS, previousHash, newHash,
                "Verified full text replaced prior shallow content");
    }

    private Result record(Long rawDocId, String sourceCode, String url, String reason,
                          TargetedRefetchAttempt.Status status, String oldHash,
                          String newHash, String message) {
        TargetedRefetchAttempt saved = attempts.save(new TargetedRefetchAttempt(
                rawDocId, sourceCode, url, reason, status, oldHash, newHash, concise(message)));
        return new Result(saved.getId(), rawDocId, sourceCode, url, reason, status,
                oldHash, newHash, concise(message));
    }

    private static TargetedRefetchPolicy.EligibilityReason eligibility(RawDoc doc) {
        return TargetedRefetchPolicy.eligibility(doc.isFullTextFetched(), chars(doc));
    }

    private static int chars(RawDoc doc) {
        return TargetedRefetchPolicy.contentCharacters(doc.getRawText());
    }
    private static void increment(Map<String, Integer> counts, String key) {
        counts.compute(key, (ignored, value) -> value == null ? 1 : value + 1);
    }
    private static String safeHost(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }
    private static String concise(String value) {
        if (value == null || value.isBlank()) return "Unspecified failure";
        return value.length() <= 1000 ? value : value.substring(0, 1000) + "…";
    }
    private static String normalizeForHash(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .strip().replaceAll("\\s+", " ");
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Candidate(Long rawDocId, String sourceCode, String url,
                            TargetedRefetchPolicy.EligibilityReason reason, int currentCharacters,
                            boolean hostAllowed, String allowedFetchHost,
                            String action) {}
    public record Plan(boolean mutationPerformed, int maxDocuments, int totalEligible,
                       int returnedCandidates, int candidatesBeyondLimit,
                       Map<String, Integer> rejectionFunnel, List<Candidate> candidates,
                       String executionRequirement) {}
    public record Result(Long attemptId, Long rawDocId, String sourceCode, String url,
                         String eligibilityReason, TargetedRefetchAttempt.Status status,
                         String previousContentHash, String newContentHash, String message) {}
    public record Execution(boolean mutationPerformed, int requested, int succeeded,
                            int failedOrRejected, List<Result> results) {}
}
