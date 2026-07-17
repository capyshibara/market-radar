package com.marketradar.report;

import com.marketradar.product.CurrentProductNewsScopeGroup;
import com.marketradar.product.ProductMarketScope;
import com.marketradar.product.ProductMarketScopeClassifier;
import com.marketradar.product.ProductReportCadence;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One bilingual view-model path shared by every Product report cadence/surface. */
@Component
public class ProductReportModel {

    public static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ProductReportAdapter reports;
    private final ProductReportEditorialService editorial;

    public ProductReportModel(ProductReportAdapter reports,
                              ProductReportEditorialService editorial) {
        this.reports = reports;
        this.editorial = editorial;
    }

    public Map<String, Object> build(ProductReportCadence cadence, Locale locale) {
        return build(cadence, locale, LocalDate.now(REPORT_ZONE));
    }

    /** Public as-of seam keeps period regression tests deterministic. */
    public Map<String, Object> build(ProductReportCadence cadence, Locale locale, LocalDate asOf) {
        boolean vi = "vi".equals(locale.getLanguage());
        ProductReportAdapter.Snapshot snapshot = reports.current(cadence, asOf);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("vi", vi);
        model.put("lang", vi ? "vi" : "en");
        model.put("cadence", cadence);
        model.put("cadenceLabel", cadence.label(vi));
        model.put("period", cadence.periodLabel(asOf, vi));
        model.put("windowStart", cadence.start(asOf));
        model.put("windowEnd", asOf);
        model.put("generatedAt", ZonedDateTime.now(REPORT_ZONE).format(DateTimeFormatter.ofPattern(
                vi ? "dd/MM/yyyy HH:mm" : "MMM d, yyyy HH:mm", locale)));
        model.put("snapshot", snapshot);
        model.put("availability", snapshot.availability());
        model.put("insufficientReason", snapshot.insufficientReason());
        model.put("decisionReady", snapshot.decisionReady());
        model.put("watchBrief", snapshot.watchBrief());
        model.put("hasProductEdition", snapshot.edition() != null);
        model.put("productEdition", snapshot.edition());
        model.put("productExecutiveInsights", snapshot.executiveInsights());
        model.put("productWatchSignals", snapshot.watchSignals());
        model.put("productWatchBriefInsights", snapshot.watchBriefInsights());
        model.put("productEvidenceByInsight", snapshot.evidenceByInsight());
        model.put("productLeadInsight", snapshot.leadInsight());
        model.put("currentProductNews", snapshot.currentNews());
        model.put("hasCurrentProductNews", !snapshot.currentNews().isEmpty());
        model.put("currentProductNewsScopes", CurrentProductNewsScopeGroup.from(snapshot.currentNews()));
        model.put("vietnamProductNewsCount", snapshot.currentNews().stream()
                .filter(item -> item.marketScope() == ProductMarketScope.VIETNAM).count());
        model.put("internationalProductNewsCount", snapshot.currentNews().stream()
                .filter(item -> item.marketScope() == ProductMarketScope.INTERNATIONAL).count());
        model.put("currentProductNewsCount", snapshot.currentNews().size());
        model.put("currentProductNewsTopicCount", snapshot.currentNews().stream()
                .map(item -> item.topic()).distinct().count());
        model.put("currentProductNewsSourceCount", snapshot.currentNews().stream()
                .map(item -> item.sourceCode()).distinct().count());
        model.put("executiveBrief", ProductExecutiveBrief.from(snapshot, vi));
        ProductReportEditorialService.EditorialBrief editorialBrief = editorial.current(cadence, locale);
        model.put("editorialBrief", editorialBrief);
        java.util.Set<String> currentCodes = snapshot.currentNews().stream()
                .map(item -> item.factCode()).collect(java.util.stream.Collectors.toSet());
        model.put("editorialReferences", editorial.references(editorialBrief, currentCodes));
        model.put("editorialAllReferences", editorial.references(editorialBrief));
        model.put("references", snapshot.references());
        model.put("referenceMarketPositions", snapshot.references().stream()
                .collect(java.util.stream.Collectors.toMap(fact -> fact.getFactCode(),
                        ProductMarketScopeClassifier::classify, (first, ignored) -> first)));
        return model;
    }
}
