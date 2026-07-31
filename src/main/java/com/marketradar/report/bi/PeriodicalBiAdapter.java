package com.marketradar.report.bi;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.ProductBriefInsight;
import com.marketradar.report.ProductReportAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bản BI report của MỘT kỳ report định kỳ (tuần/tháng/quý) — đọc thẳng từ
 * ProductReportAdapter.Snapshot đã có sẵn (đúng dữ liệu report tuần/tháng đang publish, không
 * tính lại gì khác), chỉ đổi CÁCH TRÌNH BÀY sang khung Meridian/BI 7-bucket.
 *
 * Kho dữ liệu hiện tại chỉ phủ được 2/7 bucket (COMPETITIVE_THEME từ insight đã duyệt,
 * COMPANY_EVENT từ tin tức hiện hành theo dõi) — 5 bucket còn lại (macro, lịch sự kiện, thị
 * phần/giải thưởng, tech/AI, so sánh chiến lược) cần dữ liệu mà pipeline whitelist hiện tại
 * không thu thập; khai báo rõ trong openGaps thay vì âm thầm để trống.
 */
@Component
public class PeriodicalBiAdapter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final String homeCompany;

    public PeriodicalBiAdapter(@Value("${marketradar.home-company:}") String homeCompany) {
        this.homeCompany = homeCompany;
    }

    public BiReportContent adapt(String title, String period, ProductReportAdapter.Snapshot snapshot, long docCount) {
        List<BiFinding> findings = new ArrayList<>();

        for (ProductBriefInsight insight : snapshot.executiveInsights()) {
            findings.add(toFinding(insight, snapshot, true));
        }
        for (ProductBriefInsight insight : snapshot.watchSignals()) {
            findings.add(toFinding(insight, snapshot, false));
        }
        for (CurrentProductNewsItem item : snapshot.currentNews()) {
            findings.add(new BiFinding(BiFinding.COMPANY_EVENT, item.getTopicLabelVi(),
                    item.title() + (item.getDisplaySummaryVi() != null && !item.getDisplaySummaryVi().isBlank()
                            ? " — " + item.getDisplaySummaryVi() : ""),
                    item.title() + (item.getDisplaySummaryEn() != null && !item.getDisplaySummaryEn().isBlank()
                            ? " — " + item.getDisplaySummaryEn() : ""),
                    false,
                    List.of(new BiCitation(item.sourceName(), "T" + item.sourceTier(),
                            item.hasExternalSourceLink() ? item.sourceUrl() : null))));
        }

        Set<String> sourceLines = new LinkedHashSet<>();
        for (EvidenceFact f : snapshot.references()) {
            sourceLines.add(f.getRawDoc().getSource().getName() + " (T" + f.getRawDoc().getSource().getTier() + ")");
        }

        List<String> openGaps = new ArrayList<>();
        openGaps.add("Vĩ mô ngành, Lịch sắp tới, Thị phần/Giải thưởng, Tín hiệu Tech/AI, So sánh chiến lược "
                + "— kho dữ liệu định kỳ (whitelist crawl) hiện chưa có nguồn cho các mục này; "
                + "dùng Deep Research để bổ sung theo yêu cầu cụ thể.");
        if (homeCompany == null || homeCompany.isBlank()) {
            openGaps.add("So sánh chiến lược cần cấu hình marketradar.home-company để xác định công ty gốc.");
        }

        return new BiReportContent(title, period, homeCompany,
                ZonedDateTime.now().format(TS_FMT), docCount,
                findings, List.copyOf(sourceLines), openGaps);
    }

    private BiFinding toFinding(ProductBriefInsight insight, ProductReportAdapter.Snapshot snapshot, boolean highlight) {
        List<BiCitation> citations = snapshot.evidenceByInsight()
                .getOrDefault(insight.getId(), List.of()).stream()
                .map(f -> new BiCitation(f.getRawDoc().getSource().getName(),
                        "T" + f.getRawDoc().getSource().getTier(), null))
                .distinct()
                .toList();
        String textVi = insight.getHeadlineVi()
                + (insight.getSoWhatVi() != null && !insight.getSoWhatVi().isBlank() ? " " + insight.getSoWhatVi() : "");
        String textEn = insight.getHeadlineEn()
                + (insight.getSoWhatEn() != null && !insight.getSoWhatEn().isBlank() ? " " + insight.getSoWhatEn() : "");
        return new BiFinding(BiFinding.COMPETITIVE_THEME, insight.getThemeCode(), textVi, textEn, highlight, citations);
    }
}
