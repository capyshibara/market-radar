package com.marketradar.product;

import java.util.List;

/** Structured, grounded writer boundary. Implementations must fail closed. */
public interface ProductInsightWriter {

    WrittenInsight write(ProductBriefSynthesisRules.Draft draft);

    /**
     * One bounded repair attempt after a deterministic schema, grounding or verifier rejection.
     * Implementations that cannot repair retain the original fail-closed behavior.
     */
    default WrittenInsight writeCorrected(ProductBriefSynthesisRules.Draft draft, String rejection) {
        return write(draft);
    }

    Version version();

    record Version(String providerModel, String promptSha256, String schemaVersion) {}

    record WrittenInsight(String headlineVi, String headlineEn,
                          String whatVi, String whatEn,
                          String patternVi, String patternEn,
                          String soWhatVi, String soWhatEn,
                          String nowWhatVi, String nowWhatEn,
                          String caveatVi, String caveatEn,
                          List<String> citedFactCodes) {}
}
