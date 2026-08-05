package com.marketradar.intelligence;

import com.marketradar.domain.Department;
import com.marketradar.domain.IntelligenceTopic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reusable curation priority for the CFO's step 2: decide what deserves attention.
 *
 * <p>Authority, relevance, market fit, freshness and corroboration remain separate
 * dimensions. The score only orders already traceable evidence; it never turns a
 * low-quality claim into truth and never replaces entity/entailment gates.</p>
 */
public final class CurationPriorityRules {

    private CurationPriorityRules() {}

    public record Input(Department audience, Set<IntelligenceTopic> topics,
                        String targetMarketCode, Set<String> evidenceMarketCodes,
                        int highestAuthorityScore, int independentSourceCount,
                        long ageDays, boolean highlighted, boolean entitySafe) {
        public Input {
            audience = audience == null ? Department.STRATEGY : audience;
            topics = topics == null ? Set.of() : Set.copyOf(topics);
            evidenceMarketCodes = evidenceMarketCodes == null ? Set.of() : Set.copyOf(evidenceMarketCodes);
            highestAuthorityScore = clamp(highestAuthorityScore, 0, 100);
            independentSourceCount = Math.max(0, independentSourceCount);
            ageDays = Math.max(0, ageDays);
        }
    }

    public record Score(int total, int audienceRelevance, int authority,
                        int marketFit, int freshness, int corroboration,
                        int editorialBoost, String band, List<String> rationale) {
        public Score { rationale = List.copyOf(rationale); }
    }

    public static Score score(Input input) {
        if (input == null) throw new IllegalArgumentException("curation input is required");
        List<String> rationale = new ArrayList<>();
        if (!input.entitySafe()) {
            return new Score(0, 0, 0, 0, 0, 0, 0, "HOLD_ENTITY_REVIEW",
                    List.of("Legal-entity attribution is unsafe; do not surface as decision-grade."));
        }

        int relevance = input.topics().stream()
                .mapToInt(topic -> relevance(input.audience(), topic)).max().orElse(4);
        int authority = Math.round(input.highestAuthorityScore() * 0.20f);
        int market = marketFit(input.targetMarketCode(), input.evidenceMarketCodes());
        int freshness = freshness(input.ageDays());
        int corroboration = corroboration(input.independentSourceCount());
        int boost = input.highlighted() ? 5 : 0;
        int total = clamp(relevance + authority + market + freshness + corroboration + boost, 0, 100);

        rationale.add("Audience relevance=" + relevance + "/35 for " + input.audience());
        rationale.add("Source authority=" + authority + "/20; truth is still decided by verification gates");
        rationale.add("Market fit=" + market + "/15 for target " + value(input.targetMarketCode()));
        rationale.add("Freshness=" + freshness + "/15 at age " + input.ageDays() + " day(s)");
        rationale.add("Corroboration=" + corroboration + "/15 from "
                + input.independentSourceCount() + " independent source(s)");
        if (boost > 0) rationale.add("Editorial highlight boost=5/5");
        return new Score(total, relevance, authority, market, freshness,
                corroboration, boost, band(total), rationale);
    }

    private static int relevance(Department audience, IntelligenceTopic topic) {
        if (topic == null) return 4;
        return switch (audience) {
            case STRATEGY -> switch (topic) {
                case MACRO_ECONOMIC, REGULATION_POLICY, FINANCIAL_PERFORMANCE,
                        MARKET_SHARE, MARKET_STRUCTURE, CORPORATE_ACTION -> 35;
                case TECHNOLOGY_AI, CUSTOMER_ENGAGEMENT -> 31;
                case PRODUCT_OFFER, DISTRIBUTION, PEOPLE_TALENT, BRAND_REPUTATION -> 24;
                case OTHER -> 6;
            };
            case PRODUCT -> switch (topic) {
                case PRODUCT_OFFER, REGULATION_POLICY, CUSTOMER_ENGAGEMENT, DISTRIBUTION -> 35;
                case TECHNOLOGY_AI, MARKET_STRUCTURE, MARKET_SHARE -> 28;
                case MACRO_ECONOMIC, FINANCIAL_PERFORMANCE, CORPORATE_ACTION -> 18;
                case PEOPLE_TALENT, BRAND_REPUTATION -> 12;
                case OTHER -> 6;
            };
            case SALES -> switch (topic) {
                case DISTRIBUTION, CUSTOMER_ENGAGEMENT, MARKET_SHARE, PRODUCT_OFFER -> 35;
                case BRAND_REPUTATION, MARKET_STRUCTURE, CORPORATE_ACTION -> 27;
                case TECHNOLOGY_AI, FINANCIAL_PERFORMANCE -> 20;
                case MACRO_ECONOMIC, REGULATION_POLICY, PEOPLE_TALENT -> 16;
                case OTHER -> 6;
            };
            case COMPLIANCE -> switch (topic) {
                case REGULATION_POLICY -> 35;
                case CORPORATE_ACTION, DISTRIBUTION, PRODUCT_OFFER, TECHNOLOGY_AI -> 28;
                case CUSTOMER_ENGAGEMENT, MARKET_STRUCTURE -> 22;
                case MACRO_ECONOMIC, FINANCIAL_PERFORMANCE, MARKET_SHARE,
                        PEOPLE_TALENT, BRAND_REPUTATION -> 12;
                case OTHER -> 6;
            };
        };
    }

    private static int marketFit(String target, Set<String> evidenceMarkets) {
        if (evidenceMarkets.isEmpty()) return 5;
        String wanted = value(target);
        if (evidenceMarkets.stream().map(CurationPriorityRules::value).anyMatch(wanted::equals)) return 15;
        if (evidenceMarkets.stream().map(CurationPriorityRules::value)
                .anyMatch(code -> code.equals("GLOBAL") || code.equals("REGIONAL") || code.equals("MULTI"))) {
            return 10;
        }
        return 6; // useful transfer/comparison context, never presented as domestic evidence
    }

    private static int freshness(long ageDays) {
        if (ageDays <= 7) return 15;
        if (ageDays <= 30) return 12;
        if (ageDays <= 90) return 8;
        if (ageDays <= 180) return 4;
        return 1;
    }

    private static int corroboration(int sources) {
        if (sources >= 3) return 15;
        if (sources == 2) return 11;
        if (sources == 1) return 5;
        return 0;
    }

    private static String band(int score) {
        if (score >= 80) return "SURFACE_NOW";
        if (score >= 60) return "PRIORITY";
        if (score >= 40) return "WATCH";
        return "BACKGROUND";
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.strip().toUpperCase();
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
