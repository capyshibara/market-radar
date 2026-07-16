package com.marketradar.product;

/** Fail-closed rules for promoting a Product insight into decision-ready report copy. */
public final class ProductInsightContract {

    /** v3: only L1-visible evidence reaches the writer; actions are deterministic contract copy. */
    public static final String SCHEMA_VERSION = "product-insight-v3";

    private ProductInsightContract() {}

    public record Shape(String kiqCodes, String headlineVi, String headlineEn,
                        String whatVi, String whatEn, String patternVi, String patternEn,
                        String soWhatVi, String soWhatEn, String nowWhatVi, String nowWhatEn,
                        String caveatVi, String caveatEn) {}

    public static boolean complete(Shape s) {
        return s != null && ProductKiqContract.hasLeadKiqSet(s.kiqCodes())
                && present(s.headlineVi()) && present(s.headlineEn())
                && present(s.whatVi()) && present(s.whatEn())
                && present(s.patternVi()) && present(s.patternEn())
                && present(s.soWhatVi()) && present(s.soWhatEn())
                && present(s.nowWhatVi()) && present(s.nowWhatEn())
                && present(s.caveatVi()) && present(s.caveatEn())
                && productOwnedAction(s.nowWhatVi(), true)
                && productOwnedAction(s.nowWhatEn(), false);
    }

    /** Decision copy needs corroboration; anything weaker remains a watch signal. */
    public static boolean decisionReady(Shape s, long independentDocuments,
                                        long independentSources) {
        return complete(s) && independentDocuments >= 2 && independentSources >= 2;
    }

    public static boolean productOwnedAction(String nowWhat, boolean vi) {
        if (!present(nowWhat)) return false;
        String normalized = nowWhat.strip().toLowerCase(java.util.Locale.ROOT);
        boolean owner = vi ? normalized.startsWith("chủ trì: bộ phận sản phẩm")
                : normalized.startsWith("owner: product");
        boolean horizon = vi
                ? normalized.matches("(?s).*trong (30|45|60|90) ngày.*")
                : normalized.matches("(?s).*within (30|45|60|90) days.*");
        boolean gate = vi ? normalized.contains("tiêu chí") : normalized.contains("criterion");
        return owner && horizon && gate;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
