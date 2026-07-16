package com.marketradar.report;

import com.marketradar.product.CurrentProductNewsItem;
import com.marketradar.product.CurrentProductNewsTopic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic editorial layer for the Product report.
 *
 * <p>This view never invents a market claim. It summarizes the report's own
 * publication state and groups already-admitted, source-backed news records by
 * Product review area. The strict insight and publication gates remain the only
 * path to a decision-ready conclusion.</p>
 */
public record ProductExecutiveBrief(
        String modeCode,
        String modeLabel,
        String headline,
        String posture,
        String decisionReadiness,
        int verifiedDevelopmentCount,
        int sourceCount,
        int tierOneSourceCount,
        int insightCount,
        List<PriorityArea> priorityAreas,
        List<Signal> signals,
        List<ActionHorizon> actionHorizons) {

    public record PriorityArea(String code, String label, String reviewQuestion, String validationStep,
                               int developmentCount, int sourceCount) {}

    /** Source title only: this is navigation into evidence, not generated analysis. */
    public record Signal(String themeLabel, String title, String sourceName,
                         String factCode, String sourceUrl) {}

    public record ActionHorizon(String horizon, String action, String guardrail) {}

    public static ProductExecutiveBrief from(ProductReportAdapter.Snapshot snapshot, boolean vi) {
        List<CurrentProductNewsItem> news = snapshot.currentNews();
        int insightCount = snapshot.executiveInsights().size() + snapshot.watchSignals().size();
        Set<String> sources = new LinkedHashSet<>();
        Set<String> tierOneSources = new LinkedHashSet<>();
        for (CurrentProductNewsItem item : news) {
            sources.add(item.sourceCode());
            if (item.sourceTier() == 1) tierOneSources.add(item.sourceCode());
        }

        Map<CurrentProductNewsTopic, ThemeCount> counts = new LinkedHashMap<>();
        for (CurrentProductNewsItem item : news) {
            CurrentProductNewsTopic theme = topic(item);
            counts.computeIfAbsent(theme, ignored -> new ThemeCount())
                    .add(item.sourceCode());
        }
        List<PriorityArea> priorities = counts.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<CurrentProductNewsTopic, ThemeCount>>comparingInt(e -> e.getValue().items).reversed()
                .thenComparingInt(e -> e.getKey().ordinal()))
                .map(e -> new PriorityArea(e.getKey().name(), e.getKey().label(vi),
                        e.getKey().reviewQuestion(vi), e.getKey().validationStep(vi),
                        e.getValue().items, e.getValue().sources.size()))
                .toList();

        List<Signal> signals = news.stream().limit(4)
                .map(item -> new Signal(topic(item).label(vi), item.title(),
                        item.sourceName(), item.factCode(), item.sourceUrl()))
                .toList();

        String modeCode;
        String modeLabel;
        String readiness;
        if (snapshot.decisionReady()) {
            modeCode = "DECISION_BRIEF";
            modeLabel = vi ? "Bản tin quyết định" : "Decision brief";
            readiness = vi
                    ? "Các insight hiển thị đã vượt qua cổng bằng chứng và xuất bản."
                    : "Displayed insights passed the evidence and publication gates.";
        } else if (snapshot.watchBrief()) {
            modeCode = "WATCH_BRIEF";
            modeLabel = vi ? "Bản tin theo dõi" : "Watch brief";
            readiness = vi
                    ? "Tín hiệu đã được kiểm tra, nhưng chưa đủ để kết luận xu hướng toàn thị trường."
                    : "Signals are validated, but are not sufficient for a market-wide conclusion.";
        } else {
            modeCode = "EVIDENCE_BRIEF";
            modeLabel = vi ? "Bản tin bằng chứng" : "Evidence brief";
            readiness = vi
                    ? "Cổng insight vẫn đóng; dữ kiện đã xác minh được giữ riêng để Product xem xét."
                    : "The insight gate remains closed; verified developments are separated for Product review.";
        }

        int themeCount = priorities.size();
        String headline = news.isEmpty()
                ? (vi ? "Chưa có dữ kiện Product hiện tại đủ điều kiện" : "No current Product evidence met the source gate")
                : vi
                ? news.size() + " diễn biến đã xác minh trong " + themeCount + " ưu tiên Product"
                : news.size() + " verified developments across " + themeCount + " Product priorities";
        String posture = news.isEmpty()
                ? (vi ? "Giữ cổng chất lượng đóng và bổ sung nguồn hiện tại."
                      : "Keep the quality gate closed and expand current source coverage.")
                : vi
                ? "Ưu tiên đọc các tín hiệu bên dưới, kiểm tra tác động với danh mục hiện tại và chỉ nâng cấp thành insight khi có đủ bằng chứng độc lập."
                : "Prioritise the signals below, test their relevance to the current portfolio, and promote them to insight only after independent evidence is sufficient.";

        return new ProductExecutiveBrief(modeCode, modeLabel, headline, posture, readiness,
                news.size(), sources.size(), tierOneSources.size(), insightCount,
                List.copyOf(priorities), List.copyOf(signals), actions(vi, priorities));
    }

    private static List<ActionHorizon> actions(boolean vi, List<PriorityArea> priorities) {
        if (priorities.isEmpty()) {
            return List.of(new ActionHorizon(vi ? "Bây giờ" : "Now",
                    vi ? "Bổ sung nguồn Product hiện tại và xử lý tài liệu thiếu toàn văn."
                       : "Expand current Product sources and resolve documents without full text.",
                    vi ? "Không thay thế bằng bản cũ." : "Do not substitute a stale edition."));
        }
        List<ActionHorizon> result = new ArrayList<>();
        PriorityArea lead = priorities.get(0);
        result.add(new ActionHorizon(vi ? "48 giờ" : "48 hours",
                vi ? "Ưu tiên " + lead.label() + ": " + lead.validationStep()
                   : "Prioritise " + lead.label() + ": " + lead.validationStep(),
                vi ? "Dùng trích dẫn nguyên văn; chưa gọi đây là xu hướng."
                   : "Use the verbatim citations; do not call it a trend yet."));
        if (priorities.size() > 1) {
            PriorityArea second = priorities.get(1);
            result.add(new ActionHorizon(vi ? "30 ngày" : "30 days",
                    vi ? "Mở vòng kiểm chứng cho " + second.label() + ": " + second.validationStep()
                       : "Open a validation track for " + second.label() + ": " + second.validationStep(),
                    vi ? "Ghi rõ giả thuyết cần kiểm tra và bằng chứng còn thiếu."
                       : "Record the hypothesis to test and the evidence still missing."));
        } else {
            result.add(new ActionHorizon(vi ? "30 ngày" : "30 days",
                    vi ? "Đối chiếu diễn biến ưu tiên với quyền lợi, định giá, quy trình và lộ trình Product hiện tại."
                       : "Compare the leading development with current benefits, pricing, process, and Product roadmap.",
                    vi ? "Chỉ đánh giá phần được bằng chứng hỗ trợ." : "Assess only what the evidence supports."));
        }
        result.add(new ActionHorizon(vi ? "Kỳ tiếp theo" : "Next cycle",
                vi ? "Tìm nguồn độc lập để xác nhận hoặc bác bỏ các tín hiệu quan trọng."
                   : "Seek independent sources to confirm or disconfirm the material signals.",
                vi ? "Chỉ đưa vào Bản tin quyết định khi cổng xuất bản đạt."
                   : "Promote to the Decision brief only after the publication gate passes."));
        return List.copyOf(result);
    }

    private static final class ThemeCount {
        int items;
        final Set<String> sources = new LinkedHashSet<>();
        void add(String source) { items++; sources.add(source); }
    }

    private static CurrentProductNewsTopic topic(CurrentProductNewsItem item) {
        if (item.topic() != null) return item.topic();
        return CurrentProductNewsTopic.from(Set.of(), item.factType());
    }
}
