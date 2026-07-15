package com.marketradar.intelligence;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic Product-department materiality and editorial rules.
 *
 * <p>This class deliberately has no Spring/JPA imports. It scores the usefulness
 * of a signal for Product decisions; source credibility is returned separately
 * and never contributes points to materiality. This prevents an official but
 * irrelevant article from outranking a relevant product move.</p>
 */
public final class ProductMaterialityRules {

    private ProductMaterialityRules() {}

    public static final String RULES_VERSION = "product-materiality-v5-content-floor";
    public static final int PUBLISH_THRESHOLD = 60;
    // Kept dependency-free for the standalone golden evaluator; regression tests
    // assert equality with ExtractionContentDiagnostics.MIN_ARTICLE_CHARS.
    public static final int MIN_FULL_TEXT_CHARS = 600;
    public static final int MIN_EVIDENCE_SPAN_CHARS = 80;

    public enum ProductKiq {
        KIQ_1_OFFER_CHANGE("Which benefits, features, prices or target segments are competitors changing?"),
        KIQ_2_MARKET_PATTERN("Which moves may represent a broader market shift?"),
        KIQ_3_REGULATORY_RESPONSE("Which regulations require a product, wording, pricing or approval response?"),
        KIQ_4_TRANSFERABLE_INNOVATION("Which regional innovations may transfer to Vietnam?"),
        KIQ_5_NEAR_TERM_ACTION("What should Product investigate, test or decide in the next 30-90 days?"),
        KIQ_6_CHANGE_OVER_TIME("What changed versus the recent baseline?"),
        KIQ_7_COUNTER_EVIDENCE("What evidence weakens or contradicts the apparent trend?");

        private final String question;

        ProductKiq(String question) { this.question = question; }
        public String question() { return question; }
    }

    public enum SourceCredibility {
        OFFICIAL(100), ESTABLISHED_MEDIA(80), SECONDARY(60), BLOG_OR_SOCIAL(35), UNKNOWN(50);

        private final int score;
        SourceCredibility(int score) { this.score = score; }
        public int score() { return score; }
    }

    /** Flat input keeps the core independent from persistence objects. */
    public record Input(
            String factType,
            Set<String> classificationLabels,
            String classificationStatus,
            String title,
            String evidenceSpan,
            String summary,
            String rawText,
            String company,
            String productName,
            LocalDate publishedDate,
            LocalDate eventDate,
            LocalDate asOfDate,
            boolean fullTextFetched,
            String parseStatus,
            boolean duplicate,
            Integer sourceTier) {

        public Input {
            classificationLabels = classificationLabels == null ? Set.of() : Set.copyOf(classificationLabels);
            asOfDate = asOfDate == null ? LocalDate.now() : asOfDate;
        }
    }

    public record Score(
            int total,
            int decisionRelevance,
            int impact,
            int noveltyAndTimeliness,
            int evidenceStrength,
            int noisePenalty,
            boolean publishEligible,
            SourceCredibility sourceCredibility,
            Set<ProductKiq> productKiqs,
            List<String> reasons) {

        public Score {
            productKiqs = Set.copyOf(productKiqs);
            reasons = List.copyOf(reasons);
        }
    }

    private static final Pattern NUMBER = Pattern.compile("(?<![a-z])(?:\\d[\\d.,]*|\\d+\\s*%)(?![a-z])");

    private static final String[] AWARD_NOISE = {
            "award", "awards", "giải thưởng", "vinh danh", "top employer", "best workplace",
            "nơi làm việc tốt nhất", "thương hiệu xuất sắc", "brand award"
    };
    private static final String[] CSR_NOISE = {
            "csr", "corporate social responsibility", "trách nhiệm xã hội", "từ thiện",
            "charity", "community donation", "trồng cây", "học bổng", "trao quà"
    };
    private static final String[] MARKETING_NOISE = {
            "brand ambassador", "đại sứ thương hiệu", "sponsorship", "tài trợ", "anniversary",
            "kỷ niệm thành lập", "customer appreciation", "tri ân khách hàng", "giveaway",
            "minigame", "cuộc thi ảnh", "music festival", "promotion", "promotional program",
            "khuyến mãi", "e-voucher", "voucher"
    };
    private static final String[] BANKING_TERMS = {
            "bank", "banking", "ngân hàng", "deposit", "tiền gửi", "mortgage", "thế chấp",
            "credit card", "thẻ tín dụng", "lending", "cho vay"
    };
    private static final String[] INSURANCE_TERMS = {
            "insurance", "insurer", "life insurance", "bảo hiểm", "bảo hiểm nhân thọ",
            "policy", "policyholder", "premium", "quyền lợi", "hợp đồng bảo hiểm"
    };
    private static final String[] OFFER_TERMS = {
            "benefit", "coverage", "premium", "fee", "price", "pricing", "feature", "rider",
            "product", "policy", "segment", "quyền lợi", "phạm vi bảo hiểm", "phí", "giá",
            "tính năng", "sản phẩm", "bổ trợ", "phân khúc", "điều khoản"
    };
    private static final String[] REGULATION_TERMS = {
            "regulation", "regulatory", "circular", "decree", "law", "compliance", "approval",
            "quy định", "thông tư", "nghị định", "luật", "tuân thủ", "phê duyệt"
    };
    private static final String[] DISTRIBUTION_INNOVATION_TERMS = {
            "digital distribution", "embedded insurance", "bancassurance", "digital agent",
            "e-kyc", "online underwriting", "digital underwriting", "ai underwriting",
            "phân phối số", "bảo hiểm nhúng", "đại lý số", "thẩm định trực tuyến", "kênh số"
    };
    private static final String[] LAUNCH_ACTION_TERMS = {
            "launch", "launched", "launches", "introduce", "introduced", "introduces", "unveil",
            "unveiled", "new insurance plan", "new insurance product", "ra mắt", "giới thiệu",
            "sản phẩm mới", "kế hoạch bảo hiểm mới"
    };
    private static final String[] DISTRIBUTION_CHANGE_TERMS = {
            "launch", "launched", "introduce", "introduced", "new", "partner", "partnership",
            "integrate", "integrated", "deploy", "deployed", "transfer", "transfers", "use", "uses",
            "ra mắt", "giới thiệu", "mới", "hợp tác", "tích hợp", "triển khai", "chuyển giao", "sử dụng"
    };
    private static final String[] OFFER_CHANGE_TERMS = {
            "increase", "decrease", "change", "changed", "adjust", "adjusted", "revise", "revised",
            "expand", "expanded", "reduce", "reduced", "tăng", "giảm", "thay đổi", "điều chỉnh",
            "mở rộng", "sửa đổi"
    };
    private static final String[] PATTERN_TERMS = {
            "trend", "across the market", "multiple insurers", "industry-wide", "market shift",
            "xu hướng", "toàn thị trường", "nhiều doanh nghiệp bảo hiểm", "chuyển dịch thị trường"
    };
    private static final String[] RESEARCH_CONTENT_TERMS = {
            "survey", "research report", "study", "index", "khảo sát", "báo cáo nghiên cứu", "chỉ số"
    };
    private static final String[] EXPLICIT_PRODUCT_TITLE_TERMS = {
            "insurance plan", "insurance product", "policy", "sản phẩm bảo hiểm", "hợp đồng bảo hiểm"
    };
    private static final String[] COUNTER_EVIDENCE_TERMS = {
            "however", "but", "decline", "risk", "uncertain", "caveat", "contradict",
            "tuy nhiên", "nhưng", "suy giảm", "rủi ro", "không chắc chắn", "mâu thuẫn"
    };

    public static Score score(Input input) {
        List<String> reasons = new ArrayList<>();
        EnumSet<ProductKiq> kiqs = EnumSet.noneOf(ProductKiq.class);
        Set<String> labels = upper(input.classificationLabels());
        // Decision semantics must be visible in the cited fact itself. Searching the
        // whole page made navigation, boilerplate and unrelated paragraphs validate
        // bad extractor labels (for example a promotion mislabelled as regulation).
        String text = normalize(join(input.title(), input.summary(), input.evidenceSpan()));
        String title = normalize(join(input.title()));

        boolean launchLabel = labels.contains("PRODUCT_LAUNCH") || eq(input.factType(), "PRODUCT_LAUNCH");
        boolean feeBenefitLabel = labels.contains("FEE_BENEFIT_COMMISSION_CHANGE") || eq(input.factType(), "FEE_CHANGE");
        boolean regulationLabel = labels.contains("PRODUCT_REGULATION") || eq(input.factType(), "REGULATION");
        boolean launch = launchLabel && containsAny(text, LAUNCH_ACTION_TERMS)
                && containsAny(text, OFFER_TERMS);
        boolean feeBenefit = feeBenefitLabel && containsAny(text, OFFER_TERMS)
                && containsAny(text, OFFER_CHANGE_TERMS);
        boolean regulation = regulationLabel && containsAny(text, REGULATION_TERMS);
        // A generic agent campaign or a DISTRIBUTION_CHANNEL label is not innovation.
        // Require the evidence span to name a concrete mechanism.
        boolean distribution = containsAny(text, DISTRIBUTION_INNOVATION_TERMS)
                && containsAny(text, DISTRIBUTION_CHANGE_TERMS);
        boolean sales = labels.contains("SALES_DATA") || eq(input.factType(), "METRIC");

        if ((launchLabel && !launch) || (feeBenefitLabel && !feeBenefit)
                || (regulationLabel && !regulation)
                || (labels.contains("DISTRIBUTION_CHANNEL") && !distribution)) {
            reasons.add("Extractor/classifier label lacks semantic support in the cited fact; label was not trusted.");
        }

        int relevance = 0;
        if (feeBenefit) relevance = 29;
        else if (launch) relevance = 28;
        else if (regulation) relevance = 27;
        else if (distribution) relevance = 24;
        else if (sales) relevance = 12;
        else if (containsAny(text, OFFER_TERMS)) relevance = 16;
        if (containsAny(text, OFFER_TERMS) && relevance > 0) relevance = Math.min(30, relevance + 1);
        if (relevance >= 24) reasons.add("High Product decision relevance: product, benefit, regulation or distribution move.");
        else if (relevance > 0) reasons.add("Partial Product relevance; the signal may be contextual rather than decision-driving.");
        else reasons.add("No explicit Product decision signal was found.");

        int impact = 0;
        if (regulation) impact = 22;
        else if (feeBenefit) impact = 21;
        else if (launch) impact = 19;
        else if (distribution) impact = 17;
        else if (sales) impact = 9;
        if (notBlank(input.productName())) impact += 2;
        if (notBlank(input.company())) impact += 1;
        if (NUMBER.matcher(text).find()) impact += 2;
        impact = Math.min(25, impact);
        if (impact >= 18) reasons.add("Material move with likely effect on an offer, customer value or Product response.");

        int timeliness = timeliness(input.publishedDate(), input.asOfDate());
        reasons.add(timelinessReason(input.publishedDate(), input.asOfDate(), timeliness));

        int evidence = 0;
        if (eq(input.parseStatus(), "OK")) evidence += 4;
        int rawLength = length(input.rawText());
        if (input.fullTextFetched() && rawLength >= MIN_FULL_TEXT_CHARS) evidence += 7;
        else if (rawLength >= MIN_FULL_TEXT_CHARS) evidence += 4;
        int spanLength = length(input.evidenceSpan());
        if (spanLength >= 160) evidence += 6;
        else if (spanLength >= MIN_EVIDENCE_SPAN_CHARS) evidence += 4;
        else if (spanLength >= 30) evidence += 1;
        if (eq(input.classificationStatus(), "CONFIRMED")) evidence += 3;
        if (notBlank(input.company()) || notBlank(input.productName())) evidence += 1;
        evidence = Math.min(20, evidence);
        if (evidence >= 16) reasons.add("Strong traceability: full article, usable evidence span and confirmed classification.");
        else reasons.add("Evidence is incomplete; full text, a substantive span and confirmed classification are expected.");
        boolean confirmedClassification = eq(input.classificationStatus(), "CONFIRMED");
        if (!confirmedClassification) reasons.add("Not publishable: Product classification is not confirmed.");

        int penalty = 0;
        boolean hardSuppressed = false;
        if (input.duplicate()) {
            penalty -= 40;
            hardSuppressed = true;
            reasons.add("Suppressed: duplicate document.");
        }
        if (eq(input.classificationStatus(), "OUT_OF_SCOPE")) {
            penalty -= 40;
            hardSuppressed = true;
            reasons.add("Suppressed: classifier marked the document out of scope.");
        }
        if (!eq(input.parseStatus(), "OK")) {
            penalty -= 25;
            hardSuppressed = true;
            reasons.add("Suppressed: source document did not parse successfully.");
        }
        if (containsAny(text, AWARD_NOISE)) {
            penalty -= 40;
            hardSuppressed = true;
            reasons.add("Suppressed editorial noise: award or employer-branding story.");
        }
        if (containsAny(text, CSR_NOISE)) {
            penalty -= 40;
            hardSuppressed = true;
            reasons.add("Suppressed editorial noise: CSR or philanthropy story.");
        }
        if (containsAny(text, MARKETING_NOISE)) {
            penalty -= 35;
            hardSuppressed = true;
            reasons.add("Suppressed editorial noise: sponsorship, celebration or marketing activation.");
        }
        if (launchLabel && containsAny(title, RESEARCH_CONTENT_TERMS)
                && !containsAny(title, EXPLICIT_PRODUCT_TITLE_TERMS)) {
            penalty -= 35;
            hardSuppressed = true;
            reasons.add("Suppressed: survey, study or index mislabelled as a product launch.");
        }
        if ((containsAny(title, BANKING_TERMS) && !containsAny(title, INSURANCE_TERMS))
                || (containsAny(text, BANKING_TERMS) && !containsAny(text, INSURANCE_TERMS))) {
            penalty -= 40;
            hardSuppressed = true;
            reasons.add("Suppressed: generic banking story with no insurance/product connection.");
        }
        boolean sufficientFullText = input.fullTextFetched() && rawLength >= MIN_FULL_TEXT_CHARS;
        if (!sufficientFullText) {
            penalty -= 18;
            reasons.add("Not publishable: title-only or insufficient article text.");
        }
        penalty = Math.max(-60, penalty);

        mapKiqs(kiqs, input, text, launch, feeBenefit, regulation, distribution, sales);
        if (kiqs.isEmpty()) reasons.add("No Product KIQ mapping; keep out of the decision brief.");

        SourceCredibility credibility = credibility(input.sourceTier());
        boolean credibleForPublication = credibility.score() >= SourceCredibility.SECONDARY.score();
        if (!credibleForPublication) {
            reasons.add("Source credibility warning: corroboration is required; credibility is not part of materiality points.");
        }

        int total = clamp(relevance + impact + timeliness + evidence + penalty, 0, 100);
        boolean eligible = !hardSuppressed
                && sufficientFullText
                && spanLength >= MIN_EVIDENCE_SPAN_CHARS
                && confirmedClassification
                && credibleForPublication
                && !kiqs.isEmpty()
                && total >= PUBLISH_THRESHOLD;
        if (eligible) reasons.add("Eligible for Product brief at threshold " + PUBLISH_THRESHOLD + ".");
        else reasons.add("Not eligible for Product brief; threshold and editorial gates must all pass.");

        return new Score(total, relevance, impact, timeliness, evidence, penalty,
                eligible, credibility, kiqs, reasons);
    }

    private static void mapKiqs(EnumSet<ProductKiq> kiqs, Input input, String text,
                                boolean launch, boolean feeBenefit, boolean regulation,
                                boolean distribution, boolean sales) {
        if (launch || feeBenefit || containsAny(text, OFFER_TERMS)) kiqs.add(ProductKiq.KIQ_1_OFFER_CHANGE);
        if (sales || containsAny(text, PATTERN_TERMS)) kiqs.add(ProductKiq.KIQ_2_MARKET_PATTERN);
        if (regulation || containsAny(text, REGULATION_TERMS)) kiqs.add(ProductKiq.KIQ_3_REGULATORY_RESPONSE);
        // Geography is not represented reliably in RawDoc. Do not infer a market
        // from article language; map KIQ 4 only when the signal itself describes
        // a potentially transferable distribution innovation.
        if (distribution) {
            kiqs.add(ProductKiq.KIQ_4_TRANSFERABLE_INNOVATION);
        }
        if (launch || feeBenefit || regulation || distribution) kiqs.add(ProductKiq.KIQ_5_NEAR_TERM_ACTION);
        if (launch || feeBenefit) kiqs.add(ProductKiq.KIQ_6_CHANGE_OVER_TIME);
        if (containsAny(text, COUNTER_EVIDENCE_TERMS)) kiqs.add(ProductKiq.KIQ_7_COUNTER_EVIDENCE);
    }

    private static int timeliness(LocalDate published, LocalDate asOf) {
        if (published == null) return 3;
        long days = ChronoUnit.DAYS.between(published, asOf);
        if (days < 0) return 10; // future-dated publication is suspicious, not maximally fresh
        if (days <= 14) return 15;
        if (days <= 30) return 12;
        if (days <= 90) return 8;
        if (days <= 180) return 4;
        return 1;
    }

    private static String timelinessReason(LocalDate published, LocalDate asOf, int score) {
        if (published == null) return "Publication date missing; freshness cannot be established.";
        long days = ChronoUnit.DAYS.between(published, asOf);
        if (days < 0) return "Publication date is in the future; verify date semantics.";
        return "Published " + days + " day(s) before scoring; timeliness score " + score + "/15.";
    }

    private static SourceCredibility credibility(Integer tier) {
        if (tier == null) return SourceCredibility.UNKNOWN;
        return switch (tier) {
            case 1 -> SourceCredibility.OFFICIAL;
            case 2 -> SourceCredibility.ESTABLISHED_MEDIA;
            case 3 -> SourceCredibility.SECONDARY;
            case 4 -> SourceCredibility.BLOG_OR_SOCIAL;
            default -> SourceCredibility.UNKNOWN;
        };
    }

    private static Set<String> upper(Set<String> values) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        for (String value : values) if (value != null) result.add(value.strip().toUpperCase(Locale.ROOT));
        return result;
    }

    private static boolean containsAny(String text, String[] terms) {
        for (String term : terms) {
            // Substring checks made "law" match "allowing" and could turn promotion
            // terms into a regulatory signal. Match term boundaries for every phrase.
            String regex = "(?<![\\p{L}\\p{N}])" + Pattern.quote(term)
                    + "(?![\\p{L}\\p{N}])";
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text).find()) return true;
        }
        return false;
    }

    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
    private static String join(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) if (notBlank(value)) out.append(' ').append(value.strip());
        return out.toString();
    }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static int length(String value) { return value == null ? 0 : value.strip().length(); }
    private static boolean eq(String value, String expected) { return value != null && value.equalsIgnoreCase(expected); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
