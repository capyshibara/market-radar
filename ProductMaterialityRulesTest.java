import com.marketradar.intelligence.ProductMaterialityRules;
import com.marketradar.extract.ExtractionContentDiagnostics;

import java.time.LocalDate;
import java.util.Set;

/**
 * Dependency-free regression suite. Run:
 * javac -d /tmp/materiality-test src/main/java/com/marketradar/intelligence/ProductMaterialityRules.java ProductMaterialityRulesTest.java
 * java -ea -cp /tmp/materiality-test ProductMaterialityRulesTest
 */
public class ProductMaterialityRulesTest {
    private static final LocalDate NOW = LocalDate.of(2026, 7, 15);
    private static int checks;

    public static void main(String[] args) {
        check(ProductMaterialityRules.MIN_FULL_TEXT_CHARS
                        == ExtractionContentDiagnostics.MIN_ARTICLE_CHARS,
                "Product and extraction must share the same content floor");
        prioritizesBenefitChange();
        prioritizesProductRegulation();
        prioritizesDistributionInnovation();
        suppressesAwardEvenFromOfficialSource();
        suppressesCsr();
        suppressesGenericBanking();
        suppressesMarketingFluff();
        suppressesPromotionMislabelledAsRegulation();
        suppressesIndexMislabelledAsProductLaunch();
        rejectsTitleOnlyInput();
        rejectsShortVerifiedFullText();
        acceptsExactSharedContentFloor();
        rejectsDuplicate();
        rejectsUnconfirmedClassification();
        rejectsUnsupportedRegulationLabel();
        rejectsGenericAgentCampaign();
        keepsCredibilitySeparate();
        mapsCounterEvidenceKiq();
        System.out.println("ProductMaterialityRulesTest: " + checks + " checks passed");
    }

    private static void prioritizesBenefitChange() {
        var score = score("FEE_CHANGE", Set.of("FEE_BENEFIT_COMMISSION_CHANGE"),
                "Insurer raises critical illness benefit by 25%",
                article("The insurer changed its critical illness benefit and premium structure by 25 percent."), 2, true);
        check(score.publishEligible(), "benefit change should publish");
        check(score.total() >= 80, "benefit change should score highly");
        check(score.productKiqs().contains(ProductMaterialityRules.ProductKiq.KIQ_1_OFFER_CHANGE), "maps offer KIQ");
        check(score.productKiqs().contains(ProductMaterialityRules.ProductKiq.KIQ_6_CHANGE_OVER_TIME), "maps change KIQ");
    }

    private static void prioritizesProductRegulation() {
        var score = score("REGULATION", Set.of("PRODUCT_REGULATION"),
                "New insurance circular changes product approval rules",
                article("The regulator issued a circular changing insurance product approval and policy wording requirements."), 1, true);
        check(score.publishEligible(), "product regulation should publish");
        check(score.productKiqs().contains(ProductMaterialityRules.ProductKiq.KIQ_3_REGULATORY_RESPONSE), "maps regulatory KIQ");
        check(score.sourceCredibility() == ProductMaterialityRules.SourceCredibility.OFFICIAL, "tier 1 is official");
    }

    private static void prioritizesDistributionInnovation() {
        var score = score("EVENT", Set.of("DISTRIBUTION_CHANNEL"),
                "Insurer launches embedded insurance with e-KYC",
                article("A new embedded insurance journey uses e-KYC and online underwriting through a digital distribution partner."), 3, true);
        check(score.publishEligible(), "distribution innovation should publish");
        check(score.productKiqs().contains(ProductMaterialityRules.ProductKiq.KIQ_4_TRANSFERABLE_INNOVATION), "maps innovation KIQ");
    }

    private static void suppressesAwardEvenFromOfficialSource() {
        var score = score("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"),
                "Insurer wins best product award",
                article("The company received a best product award at an annual ceremony for its insurance product."), 1, true);
        check(!score.publishEligible(), "award must be suppressed");
        check(score.noisePenalty() <= -40, "award receives hard penalty");
        check(score.sourceCredibility() == ProductMaterialityRules.SourceCredibility.OFFICIAL, "credibility remains separate");
    }

    private static void suppressesCsr() {
        var score = score("EVENT", Set.of(), "Insurer CSR charity scholarship program",
                article("The insurer made a charity donation and awarded community scholarships as corporate social responsibility."), 2, true);
        check(!score.publishEligible(), "CSR must be suppressed");
    }

    private static void suppressesGenericBanking() {
        var score = score("METRIC", Set.of("SALES_DATA"), "Bank cuts mortgage rates",
                article("The bank lowered mortgage and credit card lending rates while deposits increased this quarter."), 2, true);
        check(!score.publishEligible(), "generic banking must be suppressed");
    }

    private static void suppressesMarketingFluff() {
        var score = score("EVENT", Set.of(), "Brand ambassador joins anniversary music festival",
                article("A brand ambassador attended the anniversary music festival and customer appreciation giveaway."), 2, true);
        check(!score.publishEligible(), "marketing activation must be suppressed");
    }

    private static void suppressesPromotionMislabelledAsRegulation() {
        var score = score("REGULATION", Set.of("PRODUCT_REGULATION"),
                "Insurance promotion offers e-vouchers",
                article("The promotional program gives customers an e-voucher subject to campaign terms."), 2, true);
        check(!score.publishEligible(), "promotion cannot become regulation because its terms mention legal boilerplate");
        check(score.noisePenalty() <= -35, "promotion receives a hard editorial-noise penalty");
    }

    private static void suppressesIndexMislabelledAsProductLaunch() {
        var score = score("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"),
                "Insurer unveils longevity index for wealthy families",
                article("The company launched a research index scoring family longevity preparedness."), 2, true);
        check(!score.publishEligible(), "a research index cannot become a product launch");
    }

    private static void rejectsTitleOnlyInput() {
        var score = score("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"), "New life insurance product", "short title", 2, false);
        check(!score.publishEligible(), "title-only item cannot publish");
        check(score.noisePenalty() <= -18, "title-only penalty is explicit");
    }

    private static void rejectsShortVerifiedFullText() {
        var input = base("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"),
                "Insurer launches a new life insurance product",
                article("The insurer launched a new life insurance product with expanded coverage."),
                2, true);
        input = withRawText(input, "x".repeat(599));
        var score = ProductMaterialityRules.score(input);
        check(!score.publishEligible(), "599-character legacy full text cannot enter Product synthesis");
        check(score.reasons().stream().anyMatch(r -> r.contains("insufficient article text")),
                "short full-text rejection is explicit");
    }

    private static void acceptsExactSharedContentFloor() {
        var input = base("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"),
                "Insurer launches a new life insurance product",
                article("The insurer launched a new life insurance product with expanded coverage."),
                2, true);
        input = withRawText(input, "x".repeat(600));
        check(ProductMaterialityRules.score(input).publishEligible(),
                "the shared 600-character floor is inclusive for otherwise valid evidence");
    }

    private static void rejectsDuplicate() {
        var input = base("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"), "New product", article("The insurer launched a new life insurance product with expanded coverage."), 2, true);
        input = new ProductMaterialityRules.Input(input.factType(), input.classificationLabels(), input.classificationStatus(), input.title(),
                input.evidenceSpan(), input.summary(), input.rawText(), input.company(), input.productName(), input.publishedDate(), input.eventDate(),
                input.asOfDate(), input.fullTextFetched(), input.parseStatus(), true, input.sourceTier());
        check(!ProductMaterialityRules.score(input).publishEligible(), "duplicate cannot publish");
    }

    private static void rejectsUnconfirmedClassification() {
        var input = base("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"), "New insurance product",
                article("The insurer launched a new life insurance product with expanded customer coverage."), 2, true);
        input = new ProductMaterialityRules.Input(input.factType(), input.classificationLabels(), "UNCERTAIN_REVIEW", input.title(),
                input.evidenceSpan(), input.summary(), input.rawText(), input.company(), input.productName(),
                input.publishedDate(), input.eventDate(), input.asOfDate(), input.fullTextFetched(), input.parseStatus(), false, input.sourceTier());
        check(!ProductMaterialityRules.score(input).publishEligible(), "unconfirmed classification cannot publish");
    }

    private static void rejectsUnsupportedRegulationLabel() {
        var score = score("REGULATION", Set.of("PRODUCT_REGULATION"),
                "Promotion program expands customer eligibility",
                article("The insurer expanded a promotional program for customers with a qualifying account balance."), 2, true);
        check(!score.publishEligible(), "a promotion mislabelled as regulation cannot publish");
    }

    private static void rejectsGenericAgentCampaign() {
        var score = score("EVENT", Set.of("DISTRIBUTION_CHANNEL"),
                "Campaign develops a professional advisor team",
                article("The campaign promotes recruitment and training for a professional consulting team."), 2, true);
        check(!score.publishEligible(), "generic agent campaign is not distribution innovation");
    }

    private static void keepsCredibilitySeparate() {
        var official = score("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"), "Insurer launches a new insurance product",
                article("The insurer launched a new insurance product with expanded customer coverage."), 1, true);
        var blog = score("PRODUCT_LAUNCH", Set.of("PRODUCT_LAUNCH"), "Insurer launches a new insurance product",
                article("The insurer launched a new insurance product with expanded customer coverage."), 4, true);
        check(official.total() == blog.total(), "source tier must not change materiality total");
        check(official.sourceCredibility().score() > blog.sourceCredibility().score(), "credibility is separately visible");
        check(official.publishEligible(), "credible source can pass publication gate");
        check(!blog.publishEligible(), "blog/social source requires corroboration before publication");
    }

    private static void mapsCounterEvidenceKiq() {
        var score = score("METRIC", Set.of("SALES_DATA"), "Insurance premium trend slows",
                article("Insurance premium growth declined by 12%. However, the result remains uncertain and contradicts the earlier trend."), 2, true);
        check(score.productKiqs().contains(ProductMaterialityRules.ProductKiq.KIQ_7_COUNTER_EVIDENCE), "maps counter-evidence KIQ");
    }

    private static ProductMaterialityRules.Score score(String factType, Set<String> labels, String title,
                                                        String text, int sourceTier, boolean fullText) {
        return ProductMaterialityRules.score(base(factType, labels, title, text, sourceTier, fullText));
    }

    private static ProductMaterialityRules.Input base(String factType, Set<String> labels, String title,
                                                       String text, int sourceTier, boolean fullText) {
        String span = text.length() > 180 ? text.substring(0, 180) : text + " This evidence span contains the material details of the documented market signal and its direct effect on customers.";
        return new ProductMaterialityRules.Input(factType, labels, "CONFIRMED", title, span, text, text,
                "Example Life", "Example Protect", NOW.minusDays(5), NOW.minusDays(5), NOW,
                fullText, "OK", false, sourceTier);
    }

    private static ProductMaterialityRules.Input withRawText(
            ProductMaterialityRules.Input input, String rawText) {
        return new ProductMaterialityRules.Input(input.factType(), input.classificationLabels(),
                input.classificationStatus(), input.title(), input.evidenceSpan(), input.summary(),
                rawText, input.company(), input.productName(), input.publishedDate(), input.eventDate(),
                input.asOfDate(), input.fullTextFetched(), input.parseStatus(), input.duplicate(),
                input.sourceTier());
    }

    private static String article(String lead) {
        return lead + " The article provides detailed terms, customer eligibility, implementation dates, distribution details, and direct source quotations. "
                + "It explains how the documented change affects the insurance offer and identifies the responsible company and named product. "
                + "Additional context describes the prior position, the effective date, and the relevant customer segment for comparison. "
                + "The full source also documents benefit definitions, exclusions, premium mechanics, servicing responsibilities, rollout scope, and the baseline used to assess whether the change is material for a Product decision. "
                + "These details make the evidence suitable for checking the proposition, implementation constraints, and the precise decision that requires follow-up.";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
