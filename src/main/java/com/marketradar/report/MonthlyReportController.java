package com.marketradar.report;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Batch 9 — Monthly Highlight (tạp chí): GET /report/monthly.
 * Áp dụng design system "Meridian Review" (bàn giao từ phiên Claude Code riêng
 * của Hanh — xem hackathon/…/magazine-tpl): trang .om-page 1056×816 landscape,
 * Libre Caslon + Work Sans, pattern artwork SVG, exhibit chart pages.
 *
 * Cùng nguyên tắc dữ liệu với weekly report:
 *  - CHỈ fact thật (sampleData bị ẩn khi có fact thật) + claim đã *_APPROVED.
 *  - Không bịa số: mọi chart đếm từ DB, mọi câu chữ là claim đã qua gate/review.
 *  - PDF = in từ trình duyệt (@page landscape letter, mỗi .om-page một trang).
 */
@Controller
public class MonthlyReportController {

    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;
    private final SourceRepository sources;
    private final RawDocRepository rawDocs;

    public MonthlyReportController(EvidenceFactRepository facts, InterpretedClaimRepository claims,
                                   SourceRepository sources, RawDocRepository rawDocs) {
        this.facts = facts;
        this.claims = claims;
        this.sources = sources;
        this.rawDocs = rawDocs;
    }

    /** Bar cho SVG chart — geometry tính sẵn server-side, template chỉ vẽ. */
    public record Bar(String label, long count, int x, int y, int w, int h, String color) {}
    /** Một mục fact + các claim đã duyệt của doc đó (cho trang article). */
    public record Story(EvidenceFact fact, List<InterpretedClaim> docClaims) {}
    /** Một section của tạp chí: số thứ tự, tiêu đề, mô tả, danh sách story. */
    public record Section(String number, String title, String subtitle, List<Story> stories) {}

    @GetMapping("/report/monthly")
    public String monthly(Model model, Locale locale) {
        boolean vi = "vi".equals(locale.getLanguage());
        LocalDate today = LocalDate.now();
        String monthName = vi
                ? "Tháng " + today.getMonthValue()
                : today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        model.addAttribute("period", monthName + " · " + today.getYear());
        model.addAttribute("year", today.getYear());

        // ---- Fact thật, không trùng, TRONG THÁNG hiện tại (Batch 9) ----
        LocalDate monthStart = ReportWindow.monthlyStart(today);
        List<EvidenceFact> all = facts.findAllForReport().stream()
                .filter(f -> f.getRawDoc().getDuplicateOfId() == null)
                .filter(f -> ReportWindow.factInWindow(f, monthStart, today))
                .toList();
        boolean hasReal = all.stream().anyMatch(f -> !f.getRawDoc().isSampleData());
        List<EvidenceFact> visible = hasReal
                ? all.stream().filter(f -> !f.getRawDoc().isSampleData()).toList()
                : all;

        var passed = claims.findPublishable(List.of(
                InterpretedClaim.ReviewStatus.AUTO_APPROVED,
                InterpretedClaim.ReviewStatus.APPROVED,
                InterpretedClaim.ReviewStatus.EDITED_APPROVED,
                InterpretedClaim.ReviewStatus.FORCE_APPROVED)).stream()
                .filter(c -> c.getRawDoc() == null || !hasReal || !c.getRawDoc().isSampleData())
                .filter(c -> c.getRawDoc() == null
                        || ReportWindow.docInWindow(c.getRawDoc(), monthStart, today))
                .toList();
        Map<Long, List<InterpretedClaim>> claimsByDoc = passed.stream()
                .filter(c -> c.getRawDoc() != null)
                .collect(Collectors.groupingBy(c -> c.getRawDoc().getId()));

        // ---- Executive takeaways: IMPLICATION rủi ro cao nhất, tối đa 6 ----
        List<InterpretedClaim> takeaways = passed.stream()
                .filter(c -> c.getSlot() == InterpretedClaim.Slot.IMPLICATION)
                .sorted(Comparator.comparing(InterpretedClaim::getRiskTier).reversed()
                        .thenComparing(InterpretedClaim::getId))
                .limit(6).toList();
        model.addAttribute("takeaways", takeaways);
        model.addAttribute("pullQuote", takeaways.isEmpty() ? null : takeaways.get(0));

        // ---- Sections theo THỊ TRƯỜNG (feedback Hanh 2026-07-13): tin VN = động thái
        // đối thủ trực tiếp; tin khu vực KHÔNG đọc như tin đối thủ mà là bài học/
        // cảm hứng (ý tưởng sản phẩm, quy trình, mô hình vận hành). Cap 6 story/trang.
        List<EvidenceFact> vn = visible.stream()
                .filter(f -> "VN".equals(com.marketradar.extract.FactExtractionJob.market(f.getRawDoc()))).toList();
        List<EvidenceFact> regional = visible.stream()
                .filter(f -> !"VN".equals(com.marketradar.extract.FactExtractionJob.market(f.getRawDoc()))).toList();
        model.addAttribute("sections", List.of(
                section("01", vi ? "Việt Nam — Động thái đối thủ" : "Vietnam — Competitor Moves",
                        vi ? "Sản phẩm, phí, kênh phân phối của các công ty trên cùng thị trường"
                           : "Products, fees and distribution moves from companies in our market",
                        vn, claimsByDoc, EnumSet.of(EvidenceFact.FactType.PRODUCT_LAUNCH, EvidenceFact.FactType.FEE_CHANGE,
                                EvidenceFact.FactType.EVENT, EvidenceFact.FactType.METRIC)),
                section("02", vi ? "Quy định trong nước" : "Domestic Regulation",
                        vi ? "Diễn biến pháp lý ảnh hưởng thiết kế và phân phối sản phẩm tại Việt Nam"
                           : "Regulatory developments affecting product design and distribution in Vietnam",
                        vn, claimsByDoc, EnumSet.of(EvidenceFact.FactType.REGULATION)),
                section("03", vi ? "Khu vực — Bài học & Cảm hứng" : "Regional — Lessons & Inspiration",
                        vi ? "Không phải đối thủ trực tiếp: đọc để lấy ý tưởng sản phẩm, quy trình và mô hình vận hành"
                           : "Not direct competitors: read for product ideas, process and operating-model inspiration",
                        regional, claimsByDoc, EnumSet.allOf(EvidenceFact.FactType.class))));

        // ---- Exhibit 01: developments by category (column chart, geometry sẵn) ----
        Map<EvidenceFact.FactType, Long> byType = visible.stream()
                .collect(Collectors.groupingBy(EvidenceFact::getFactType, Collectors.counting()));
        model.addAttribute("catBars", columnBars(byType, vi));

        // ---- Exhibit 02: top nguồn theo số fact đã trích (horizontal bars) ----
        Map<String, Long> bySource = visible.stream()
                .collect(Collectors.groupingBy(f -> f.getRawDoc().getSource().getName(), Collectors.counting()));
        model.addAttribute("srcBars", sourceBars(bySource));

        // ---- Stat strip cho methodology ----
        model.addAttribute("statDocs", rawDocs.count());
        model.addAttribute("statSources", sources.count());
        model.addAttribute("statFacts", visible.size());
        model.addAttribute("statClaims", passed.size());
        long auto = passed.stream().filter(c -> c.getReviewStatus() == InterpretedClaim.ReviewStatus.AUTO_APPROVED).count();
        model.addAttribute("statAuto", auto);
        return "monthly-report";
    }

    private Section section(String number, String title, String subtitle,
                            List<EvidenceFact> visible, Map<Long, List<InterpretedClaim>> claimsByDoc,
                            EnumSet<EvidenceFact.FactType> types) {
        List<Story> stories = visible.stream()
                .filter(f -> types.contains(f.getFactType()))
                .sorted(Comparator.comparing(EvidenceFact::getEventDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .map(f -> new Story(f, claimsByDoc.getOrDefault(f.getRawDoc().getId(), List.of())))
                .toList();
        return new Section(number, title, subtitle, stories);
    }

    /** Column chart 620×300: cột đều nhau trên baseline y=260, cao theo count. */
    private List<Bar> columnBars(Map<EvidenceFact.FactType, Long> byType, boolean vi) {
        String[] colors = {"#2647E8", "#F5A623", "#00BFA6", "#FF5A36", "#8B5CF6"};
        var entries = new ArrayList<>(byType.entrySet());
        entries.sort(Map.Entry.<EvidenceFact.FactType, Long>comparingByValue().reversed());
        long max = entries.isEmpty() ? 1 : Math.max(1, entries.get(0).getValue());
        List<Bar> bars = new ArrayList<>();
        int n = entries.size(), slot = n == 0 ? 620 : 620 / n, w = Math.min(90, slot - 30);
        for (int i = 0; i < n; i++) {
            var e = entries.get(i);
            int h = (int) Math.round(220.0 * e.getValue() / max);
            String label = vi ? viType(e.getKey()) : enType(e.getKey());
            bars.add(new Bar(label, e.getValue(),
                    i * slot + (slot - w) / 2, 260 - h, w, h, colors[i % colors.length]));
        }
        return bars;
    }

    /** Horizontal bars 620×300: top 6 nguồn, thanh cao 26, cách 46. */
    private List<Bar> sourceBars(Map<String, Long> bySource) {
        String[] colors = {"#2647E8", "#00BFA6", "#F5A623", "#8B5CF6", "#FF5A36", "#2647E8"};
        var entries = bySource.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6).toList();
        long max = entries.isEmpty() ? 1 : Math.max(1, entries.get(0).getValue());
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            int w = (int) Math.round(380.0 * e.getValue() / max);
            bars.add(new Bar(e.getKey(), e.getValue(), 220, i * 46 + 10, Math.max(w, 6), 26,
                    colors[i % colors.length]));
        }
        return bars;
    }

    private static String viType(EvidenceFact.FactType t) {
        return switch (t) {
            case EVENT -> "Sự kiện"; case PRODUCT_LAUNCH -> "Ra mắt SP";
            case FEE_CHANGE -> "Phí/quyền lợi"; case REGULATION -> "Quy định"; case METRIC -> "Số liệu";
        };
    }

    private static String enType(EvidenceFact.FactType t) {
        return switch (t) {
            case EVENT -> "Events"; case PRODUCT_LAUNCH -> "Launches";
            case FEE_CHANGE -> "Fee changes"; case REGULATION -> "Regulation"; case METRIC -> "Metrics";
        };
    }
}
