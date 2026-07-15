package com.marketradar.product;

import java.util.List;

/** Structured, grounded writer boundary. Implementations must fail closed. */
public interface ProductInsightWriter {

    WrittenInsight write(ProductBriefSynthesisRules.Draft draft);

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
