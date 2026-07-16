import com.marketradar.product.CurrentProductNewsGroup;
import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.CurrentProductNewsTopic;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Standalone safety regression for deterministic Product news framing. */
public class CurrentProductNewsEnrichmentTest {
    private static int checks;

    public static void main(String[] args) {
        topicMappingUsesClosedLabelsAndSafeFallbacks();
        promptsAskForValidationRatherThanClaimingTrends();
        groupingIsStableAndImmutable();
        freshnessAndSourceMetadataAreDeterministic();
        System.out.println("CurrentProductNewsEnrichmentTest: " + checks + " checks passed");
    }

    private static void topicMappingUsesClosedLabelsAndSafeFallbacks() {
        check(CurrentProductNewsTopic.from(Set.of("PRODUCT_REGULATION"), "EVENT")
                        == CurrentProductNewsTopic.REGULATION,
                "regulation label maps to regulation lens");
        check(CurrentProductNewsTopic.from(Set.of("PRODUCT_LAUNCH"), "EVENT")
                        == CurrentProductNewsTopic.PRODUCT_AND_BENEFITS,
                "launch label maps to product lens");
        check(CurrentProductNewsTopic.from(Set.of("SALES_DATA"), "EVENT")
                        == CurrentProductNewsTopic.MARKET_METRICS,
                "sales label maps to metric lens");
        check(CurrentProductNewsTopic.from(Set.of("DISTRIBUTION_CHANNEL"), "EVENT")
                        == CurrentProductNewsTopic.DISTRIBUTION,
                "distribution label maps to distribution lens");
        check(CurrentProductNewsTopic.from(Set.of(), "FEE_CHANGE")
                        == CurrentProductNewsTopic.PRODUCT_AND_BENEFITS,
                "fact type is a deterministic fallback");
    }

    private static void promptsAskForValidationRatherThanClaimingTrends() {
        for (CurrentProductNewsTopic topic : CurrentProductNewsTopic.values()) {
            check(topic.getReviewQuestionEn().endsWith("?"), "English lens is a question");
            check(topic.getReviewQuestionVi().endsWith("?"), "Vietnamese lens is a question");
            String copy = (topic.getReviewQuestionEn() + " " + topic.getValidationStepEn()).toLowerCase();
            check(!copy.contains("market trend") && !copy.contains("we recommend"),
                    "lens does not invent a trend or recommendation");
        }
    }

    private static void groupingIsStableAndImmutable() {
        var metrics = item("F-2", CurrentProductNewsTopic.MARKET_METRICS, 2);
        var regulation = item("F-1", CurrentProductNewsTopic.REGULATION, 1);
        List<CurrentProductNewsGroup> groups = CurrentProductNewsGroup.from(List.of(metrics, regulation));
        check(groups.size() == 2, "two topics produce two groups");
        check(groups.get(0).topic() == CurrentProductNewsTopic.REGULATION,
                "groups follow stable editorial enum order");
        check(groups.get(1).items().get(0).factCode().equals("F-2"), "group retains cited item");
        try {
            groups.get(0).items().add(metrics);
            throw new AssertionError("group items must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    private static void freshnessAndSourceMetadataAreDeterministic() {
        CurrentProductNewsItem item = item("F-3", CurrentProductNewsTopic.PRODUCT_AND_BENEFITS, 1);
        check(item.getFreshnessLabelEn().equals("Published 1 day ago"), "singular freshness copy");
        check(item.getFreshnessLabelVi().equals("Công bố 1 ngày trước"), "Vietnamese freshness copy");
        check(item.getSourceTierLabelEn().equals("Tier 1 source"), "source tier remains explicit");
    }

    private static CurrentProductNewsItem item(String code, CurrentProductNewsTopic topic, long age) {
        return new CurrentProductNewsItem(code, age, "Source title", "SOURCE", "Source", 1,
                "https://example.test/" + code, LocalDate.of(2026, 7, 16).minusDays(age),
                "EVENT", "Exact verbatim source evidence.", topic, age);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
