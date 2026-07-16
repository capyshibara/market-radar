package com.marketradar.report;

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

    public ProductReportModel(ProductReportAdapter reports) { this.reports = reports; }

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
        model.put("references", snapshot.references());
        return model;
    }
}
