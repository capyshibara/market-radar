package com.marketradar.llm;

import java.util.Locale;

/** Pure fail-closed provider checks shared by service entry points. */
public final class ProviderSafetyRules {
    private ProviderSafetyRules() {}

    public static boolean isStub(String providerName) {
        if (providerName == null || providerName.isBlank()) return true;
        return providerName.strip().toUpperCase(Locale.ROOT).startsWith("STUB");
    }
}
