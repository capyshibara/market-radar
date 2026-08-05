package com.marketradar.product;

import com.marketradar.domain.SourceAuthority;
import com.marketradar.intelligence.ProductMaterialityRules;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic admission policy for source-backed Current Product News.
 *
 * <p>This deliberately has a lower editorial bar than a Product decision
 * insight. It may surface a single current, confirmed source item, but it
 * never turns that item into a trend, recommendation, or generated claim.
 * The policy is pure so its safety boundary can be regression-tested without
 * Spring or a database.</p>
 */
public final class CurrentProductNewsPolicy {

    public static final String VERSION = "current-product-news-v3-authority-entity";

    private static final Set<String> PRODUCT_LABELS = Set.of(
            "PRODUCT_LAUNCH",
            "FEE_BENEFIT_COMMISSION_CHANGE",
            "PRODUCT_REGULATION",
            "SALES_DATA",
            "DISTRIBUTION_CHANNEL");

    private static final String[] NON_LIFE_TERMS = {
            "non-life", "property and casualty", "p&c", "bảo hiểm phi nhân thọ", "phi nhân thọ",
            "travel insurance", "bảo hiểm du lịch", "motor insurance", "bảo hiểm xe"
    };
    private CurrentProductNewsPolicy() {}

    public record Input(
            boolean factActive,
            boolean sourceActive,
            String rawText,
            boolean fullTextFetched,
            String parseStatus,
            boolean sampleData,
            boolean duplicate,
            Integer sourceTier,
            LocalDate publishedDate,
            String classificationStatus,
            Set<String> classificationLabels,
            String title,
            String verbatimEvidenceSpan,
            String sourceAuthority,
            boolean attributionSafe) {
        public Input {
            classificationLabels = classificationLabels == null ? Set.of() : Set.copyOf(classificationLabels);
        }

        /** Compatibility for standalone policy callers while persisted sources migrate. */
        public Input(boolean factActive, boolean sourceActive, String rawText,
                     boolean fullTextFetched, String parseStatus, boolean sampleData,
                     boolean duplicate, Integer sourceTier, LocalDate publishedDate,
                     String classificationStatus, Set<String> classificationLabels,
                     String title, String verbatimEvidenceSpan) {
            this(factActive, sourceActive, rawText, fullTextFetched, parseStatus, sampleData,
                    duplicate, sourceTier, publishedDate, classificationStatus,
                    classificationLabels, title, verbatimEvidenceSpan,
                    legacyAuthority(sourceTier).name(), true);
        }

        /** Compatibility for callers that already provide explicit authority metadata. */
        public Input(boolean factActive, boolean sourceActive, String rawText,
                     boolean fullTextFetched, String parseStatus, boolean sampleData,
                     boolean duplicate, Integer sourceTier, LocalDate publishedDate,
                     String classificationStatus, Set<String> classificationLabels,
                     String title, String verbatimEvidenceSpan, String sourceAuthority) {
            this(factActive, sourceActive, rawText, fullTextFetched, parseStatus, sampleData,
                    duplicate, sourceTier, publishedDate, classificationStatus,
                    classificationLabels, title, verbatimEvidenceSpan, sourceAuthority, true);
        }
    }

    public record Decision(boolean eligible, String reason) {}

    public static Decision evaluate(Input input, ProductReportCadence cadence, LocalDate asOf) {
        if (input == null || cadence == null || asOf == null) {
            return reject("missing current-news input, cadence, or as-of date");
        }
        if (!input.factActive() || !input.sourceActive()) return reject("inactive fact or source");
        if (!"OK".equalsIgnoreCase(input.parseStatus())) return reject("source document did not parse successfully");
        if (input.sampleData()) return reject("sample data is never current news");
        if (input.duplicate()) return reject("duplicate document");
        if (!input.attributionSafe()) {
            return reject("entity attribution is unresolved, ambiguous, or conflicting");
        }
        if (!input.fullTextFetched() || length(input.rawText()) < ProductMaterialityRules.MIN_FULL_TEXT_CHARS) {
            return reject("full article text is unavailable or below the shared content floor");
        }
        SourceAuthority authority = parseAuthority(input.sourceAuthority(), input.sourceTier());
        if (authority == SourceAuthority.UNKNOWN || authority == SourceAuthority.SOCIAL_OR_BLOG) {
            return reject("source authority is unknown or social-only");
        }
        if (input.publishedDate() == null || input.publishedDate().isBefore(cadence.start(asOf))
                || input.publishedDate().isAfter(asOf)) {
            return reject("source item is outside the selected current cadence");
        }
        if (!"CONFIRMED".equalsIgnoreCase(input.classificationStatus())
                || !hasRelevantLabel(input.classificationLabels())) {
            return reject("Product classification is not confirmed and relevant");
        }
        if (length(input.verbatimEvidenceSpan()) < ProductMaterialityRules.MIN_EVIDENCE_SPAN_CHARS) {
            return reject("verbatim evidence span is below the minimum length");
        }
        if (input.rawText() == null || !input.rawText().contains(input.verbatimEvidenceSpan())) {
            return reject("evidence span is not an exact substring of the stored source text");
        }
        String evidenceText = normalize(join(input.title(), input.verbatimEvidenceSpan()));
        if (containsAny(evidenceText, NON_LIFE_TERMS)) return reject("non-life item is outside the life Product scope");
        return new Decision(true, "eligible");
    }

    private static boolean hasRelevantLabel(Set<String> labels) {
        for (String label : labels) {
            if (label != null && PRODUCT_LABELS.contains(label.strip().toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Decision reject(String reason) { return new Decision(false, reason); }
    private static int length(String text) { return text == null ? 0 : text.strip().length(); }
    private static String join(String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) if (value != null && !value.isBlank()) joined.append(' ').append(value.strip());
        return joined.toString();
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
    private static boolean containsAny(String text, String[] terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static SourceAuthority parseAuthority(String value, Integer legacyTier) {
        if (value != null && !value.isBlank()) {
            try {
                return SourceAuthority.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return SourceAuthority.UNKNOWN;
            }
        }
        return legacyAuthority(legacyTier);
    }

    private static SourceAuthority legacyAuthority(Integer tier) {
        if (tier == null) return SourceAuthority.UNKNOWN;
        return switch (tier) {
            case 1 -> SourceAuthority.OFFICIAL_COMPANY;
            case 2 -> SourceAuthority.ESTABLISHED_MEDIA;
            case 3 -> SourceAuthority.OTHER_PUBLISHER;
            default -> SourceAuthority.UNKNOWN;
        };
    }
}
