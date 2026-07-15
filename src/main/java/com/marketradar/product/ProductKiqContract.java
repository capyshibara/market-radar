package com.marketradar.product;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stable Product KIQ identifiers used by persisted insights and prompt schemas. */
public final class ProductKiqContract {

    public static final String CONTRACT_VERSION = "product-v1.1";

    public enum Kiq {
        OFFER_CHANGE("P-KIQ-01"),
        MARKET_PATTERN("P-KIQ-02"),
        REGULATORY_RESPONSE("P-KIQ-03"),
        TRANSFERABLE_INNOVATION("P-KIQ-04"),
        CUSTOMER_NEED("P-KIQ-05"),
        NEAR_TERM_ACTION("P-KIQ-06"),
        COUNTER_EVIDENCE("P-KIQ-07");

        private final String id;
        Kiq(String id) { this.id = id; }
        public String id() { return id; }
    }

    private ProductKiqContract() {}

    public static String leadCodes(Kiq primary) {
        return String.join(",", primary.id(), Kiq.NEAR_TERM_ACTION.id(),
                Kiq.COUNTER_EVIDENCE.id());
    }

    public static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isBlank())
                .forEach(values::add);
        return Set.copyOf(values);
    }

    public static boolean hasLeadKiqSet(String csv) {
        Set<String> values = split(csv);
        boolean primary = values.stream().anyMatch(id -> id.matches("P-KIQ-0[1-5]"));
        return primary && values.contains(Kiq.NEAR_TERM_ACTION.id())
                && values.contains(Kiq.COUNTER_EVIDENCE.id());
    }
}
