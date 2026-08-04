package com.marketradar.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Pure guards shared by the refetch API and focused regression tests. */
public final class TargetedRefetchPolicy {
    public static final int MAX_DOCUMENTS = 25;
    public static final int MIN_FULL_TEXT_CHARS = 600;

    public enum EligibilityReason { UNVERIFIED_FULL_TEXT, SHORT_FULL_TEXT }

    private static final Map<String, String> ARTICLE_HOST_OVERRIDE = Map.of(
            "CHUBB_VN", "chubb.mediaroom.com",
            "DAIICHI_VN", "kh.dai-ichi-life.com.vn",
            // AIA publishes its statutory summary as an official Scene7 image; requesting
            // fmt=pdf yields the same asset in a deterministic OCR-friendly container.
            "AIA_VN_FINANCIALS", "s7ap1.scene7.com",
            // Techcom Life publishes its signed statutory PDF on this fixed CDN host.
            "TECHCOM_LIFE_FINANCIALS", "d2pbhagi66anjy.cloudfront.net",
            // FWD's own page publishes statutory PDFs from this fixed Contentstack host.
            "FWD_VN_FINANCIALS", "assets.contentstack.io");

    private TargetedRefetchPolicy() {}

    public static EligibilityReason eligibility(boolean fullTextFetched, int characters) {
        if (!fullTextFetched) return EligibilityReason.UNVERIFIED_FULL_TEXT;
        return characters < MIN_FULL_TEXT_CHARS ? EligibilityReason.SHORT_FULL_TEXT : null;
    }

    /** Match classification/extraction semantics: surrounding whitespace is not article content. */
    public static int contentCharacters(String rawText) {
        return rawText == null ? 0 : rawText.strip().length();
    }

    public static List<Long> normalizeIds(List<Long> requestedIds, boolean requireExplicit) {
        List<Long> ids = requestedIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(requestedIds));
        if (requireExplicit && ids.isEmpty()) {
            throw new IllegalArgumentException("At least one explicit rawDocId is required");
        }
        if (ids.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("At most 25 rawDocIds are allowed");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("rawDocIds must be positive integers");
        }
        return List.copyOf(ids);
    }

    /** Exact source host, or a narrowly declared per-source article host; never a wildcard. */
    public static String articleFetchHost(String sourceCode, String allowedHost, String linkHost) {
        if (linkHost == null) return null;
        if (linkHost.equalsIgnoreCase(allowedHost)) return allowedHost;
        String override = ARTICLE_HOST_OVERRIDE.get(sourceCode);
        return override != null && linkHost.equalsIgnoreCase(override) ? override : null;
    }
}
