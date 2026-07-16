package com.marketradar.product;

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

    public static final String VERSION = "current-product-news-v1";

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
    private static final String[] CLAIMS_ONLY_TERMS = {
            "claim payment", "claims payment", "claim payout", "payout", "paid a claim",
            "chi trả bồi thường", "bồi thường bảo hiểm", "chi trả quyền lợi bảo hiểm"
    };
    private static final String[] EDITORIAL_NOISE_TERMS = {
            " award", "awards", "vinh danh", "mdrt ranking", "top employer", "best workplace",
            "corporate social responsibility", "trách nhiệm xã hội", "charity", "từ thiện",
            "music festival", "giveaway", "anniversary celebration"
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
            String verbatimEvidenceSpan) {
        public Input {
            classificationLabels = classificationLabels == null ? Set.of() : Set.copyOf(classificationLabels);
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
        if (!input.fullTextFetched() || length(input.rawText()) < ProductMaterialityRules.MIN_FULL_TEXT_CHARS) {
            return reject("full article text is unavailable or below the shared content floor");
        }
        if (input.sourceTier() == null || input.sourceTier() < 1 || input.sourceTier() > 3) {
            return reject("source tier is outside the current-news credibility range");
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
        if (containsAny(evidenceText, CLAIMS_ONLY_TERMS)
                || (containsAny(evidenceText, new String[]{"chi trả", "bồi thường"})
                && containsAny(evidenceText, new String[]{"quyền lợi bảo hiểm", "claim"}))) {
            return reject("claims-payment item is outside the Product news scope");
        }
        if (containsAny(evidenceText, EDITORIAL_NOISE_TERMS)) return reject("award, CSR, or marketing item is not Product news");
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
}
