package com.marketradar.report.bi;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.domain.ClaimVerification;
import com.marketradar.domain.Department;
import com.marketradar.domain.IntelligenceTopic;
import com.marketradar.intelligence.CompetitorRegistry;
import com.marketradar.intelligence.CurationPriorityRules;
import com.marketradar.intelligence.ReportTimeWindowRules;
import com.marketradar.product.ProductMarketScopeClassifier;
import com.marketradar.product.ProductMarketScope;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.ClaimVerificationRepository;
import com.marketradar.review.PublicationEligibilityRules;
import com.marketradar.report.ProductReportAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bản BI report của MỘT kỳ report định kỳ (tuần/tháng/quý).
 *
 * Nguồn nội dung DUY NHẤT: CLAIM ĐÃ DUYỆT TAY ở /review (APPROVED/EDITED_APPROVED/
 * FORCE_APPROVED, cộng AUTO_APPROVED vốn đòi ENTAILED) — công sức của người duyệt
 * chảy thẳng vào báo cáo.
 *
 * 2026-08-03 (feedback: "bỏ bớt agent dư thừa" + Router mới): trước đây có thêm 2 kênh
 * máy — Insight Product brief (executiveInsights/watchSignals) và Tin hiện hành
 * (currentNews) — cả 2 thuộc nhánh Product/Sales/Compliance đã inactivate
 * (marketradar.legacy-desks.enabled=false, xem LegacyDeskAccessGuard). Bỏ hẳn 2 kênh
 * đó khỏi luồng Strategy: báo cáo giờ chỉ còn 1 nguồn duy nhất, sạch hơn và không phụ
 * thuộc dữ liệu từ 1 nhánh đã tắt.
 *
 * Nhãn bucket/subjectKey/severity/kpi... giờ đọc TRỰC TIẾP từ EvidenceFact đã qua
 * Router (xem FactExtractionJob#route) — không còn suy từ "claim đến từ bước nào của
 * pipeline" (rule cũ reportLevel?COMPETITIVE_THEME:COMPANY_EVENT). Claim/fact CHƯA qua
 * Router (crawl trước khi có Router, hoặc chạy STUB) vẫn rơi về đúng rule cũ đó —
 * không mất dữ liệu, chỉ là chưa được phân loại chính xác bằng nội dung.
 */
@Component
public class PeriodicalBiAdapter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** 2026-08-03: 5 bucket mà Interpreter có thể tự gán cho claim WHY_MATTERS (xem
     *  Interpreter#VALID_BI_BUCKETS) — claim với bucket khác/null rơi về COMPETITIVE_THEME/
     *  COMPANY_EVENT như trước. Dùng chung danh sách này để biết mục nào THẬT SỰ trống trong
     *  kỳ này, thay vì luôn báo cả 5 mục là "chưa có nguồn" bất kể có claim hay không. */
    private static final Set<String> SPECIAL_BUCKETS = Set.of(
            BiFinding.MACRO_ECONOMIC, BiFinding.SCHEDULED_EVENT, BiFinding.MARKET_SHARE_OR_AWARD,
            BiFinding.TECH_AI_SIGNAL, BiFinding.STRATEGIC_COMPARISON);

    private static final Map<String, String> BUCKET_LABEL_VI = Map.of(
            BiFinding.MACRO_ECONOMIC, "Vĩ mô ngành",
            BiFinding.SCHEDULED_EVENT, "Lịch công bố sắp tới",
            BiFinding.MARKET_SHARE_OR_AWARD, "Thị phần/Giải thưởng",
            BiFinding.TECH_AI_SIGNAL, "Tín hiệu Tech/AI",
            BiFinding.STRATEGIC_COMPARISON, "So sánh chiến lược");

    private final String homeCompany;
    private final InterpretedClaimRepository claims;
    private final EvidenceFactRepository facts;
    private final CompetitorRegistry registry;
    private final ClaimVerificationRepository verifications;

    public PeriodicalBiAdapter(@Value("${marketradar.home-company:}") String homeCompany,
                               InterpretedClaimRepository claims,
                               EvidenceFactRepository facts,
                               CompetitorRegistry registry,
                               ClaimVerificationRepository verifications) {
        this.homeCompany = homeCompany;
        this.claims = claims;
        this.facts = facts;
        this.registry = registry;
        this.verifications = verifications;
    }

    /** 1 finding kèm factCode nó thật sự trích — companion cho DeepDiveSynthesis (Connector chỉ
     *  biết BiFinding, nhưng gate/synthesis cần trỏ lại đúng EvidenceFact gốc). adapt() chỉ dùng
     *  finding(), bỏ qua factCodes(). */
    public record RoutedFinding(BiFinding finding, List<String> factCodes,
                                int curationScore, List<String> curationRationale) {
        public RoutedFinding {
            factCodes = factCodes == null ? List.of() : List.copyOf(factCodes);
            curationRationale = curationRationale == null ? List.of() : List.copyOf(curationRationale);
        }
        public RoutedFinding(BiFinding finding, List<String> factCodes) {
            this(finding, factCodes, 0, List.of());
        }
    }

    /** Kênh 1 (claim đã duyệt) — dùng chung bởi adapt() và InterpretationJob#runDeepDiveSynthesis. */
    public List<RoutedFinding> approvedFindings(LocalDate windowStart, LocalDate windowEnd) {
        List<RoutedFinding> out = new ArrayList<>();
        List<InterpretedClaim> approved = claims.findForBiReport();
        for (InterpretedClaim claim : approved) {
            if (claim.getRawDoc() != null && (claim.getRawDoc().isSampleData()
                    || claim.getRawDoc().getDuplicateOfId() != null)) continue;
            List<EvidenceFact> citedFacts = resolveFacts(claim);
            if (!inWindow(claim, citedFacts, windowStart, windowEnd)) continue;
            String latestVerdict = verifications.findFirstByClaimOrderByCreatedAtDescIdDesc(claim)
                    .map(ClaimVerification::getVerdict).map(Enum::name).orElse(null);
            boolean entitySafe = !citedFacts.isEmpty()
                    && citedFacts.stream().allMatch(com.marketradar.review.EntityAttributionGuard::isFactAttributionSafe);
            PublicationEligibilityRules.Disposition disposition = PublicationEligibilityRules.disposition(
                    claim.getGateStatus().name(), claim.getReviewStatus().name(), latestVerdict,
                    claim.isSuperseded(), entitySafe, isAnalyticalContent(claim.getSlot()));
            if (disposition == PublicationEligibilityRules.Disposition.EXCLUDE) continue;
            boolean decisionEligibleSources = citedFacts.stream().allMatch(f ->
                    f.getRawDoc().getSource().getUsePolicy().allowsDecisionPublication());
            int independentSources = (int) citedFacts.stream()
                    .map(f -> f.getRawDoc().getSource().getCode()).distinct().count();
            int highestAuthority = citedFacts.stream()
                    .mapToInt(f -> f.getRawDoc().getSource().getAuthority().credibilityScore())
                    .max().orElse(0);
            // A social/blog-only claim may remain visible after human approval, but it
            // cannot become decision-grade until corroborated by another publisher.
            if (disposition == PublicationEligibilityRules.Disposition.DECISION_GRADE
                    && (!decisionEligibleSources
                    || (highestAuthority < com.marketradar.domain.SourceAuthority.OTHER_PUBLISHER.credibilityScore()
                    && independentSources < 2))) {
                disposition = PublicationEligibilityRules.Disposition.EDITORIAL_WATCH;
            }
            List<BiCitation> citations = citationsFor(claim, citedFacts);
            boolean reportLevel = claim.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY
                    || claim.getSlot() == InterpretedClaim.Slot.NARRATIVE
                    || claim.getSlot() == InterpretedClaim.Slot.DEEP_DIVE;
            RoutedLabels routed = resolveRouting(claim, citedFacts, reportLevel);
            // "company" KHÔNG được truyền = subject (tên đối thủ ĐÃ CHUẨN HOÁ theo registry, vd
            // "Prudential Việt Nam" cho mọi claim nhắc "Prudential"): làm vậy sẽ khiến MỌI claim
            // về một đối thủ đã đăng ký bị gắn "Việt Nam" bất kể bằng chứng thực nói về công ty
            // nào — tái tạo đúng lỗi CFO nêu (Prudential plc bị lẫn với Prudential Financial Inc.)
            // ngay trong chính tính năng được xây để hiển thị rủi ro đó minh bạch hơn. Chỉ dùng
            // tín hiệu khách quan: ngôn ngữ/host của NGUỒN đăng ký + host của URL tài liệu.
            ProductMarketScopeClassifier.MarketPosition market = resolveMarket(claim, citedFacts);
            // subjectKey: ưu tiên Router (gán riêng cho ĐÚNG fact này, từ nguyên văn span) —
            // chỉ rơi về CompetitorRegistry (chuẩn hoá tên nhưng KHÔNG gắn theo fact cụ thể)
            // khi fact chưa qua Router.
            String subject = routed.subjectKey() != null ? routed.subjectKey()
                    : registry.detectCompetitor(claim.getTextVi() + "\n"
                                    + (claim.getRawDoc() != null && claim.getRawDoc().getTitle() != null
                                            ? claim.getRawDoc().getTitle() : ""))
                            .orElse(null);
            BiFinding finding = new BiFinding(
                    routed.bucket(), subject,
                    claim.getTextVi(), claim.getTextEn(),
                    routed.highlight(),
                    citations, routed.severity(), metricPercent(routed.bucket(), citedFacts),
                    market.scope(), market.geography(), null,
                    routed.highlightCardLabel(), routed.severityTrend(),
                    routed.kpiLabel(), routed.kpiValue(),
                    routed.eventDateRangeStart(), routed.eventDateRangeEnd(),
                    disposition.name());
            CurationPriorityRules.Score priority = curationPriority(
                    citedFacts, routed.highlight(), entitySafe, independentSources, windowEnd);
            out.add(new RoutedFinding(finding,
                    citedFacts.stream().map(EvidenceFact::getFactCode).toList(),
                    priority.total(), priority.rationale()));
        }
        out.sort(java.util.Comparator.comparingInt(RoutedFinding::curationScore).reversed()
                .thenComparing(rf -> rf.finding().bucket())
                .thenComparing(rf -> java.util.Objects.toString(rf.finding().subjectKey(), "")));
        return List.copyOf(out);
    }

    private static CurationPriorityRules.Score curationPriority(
            List<EvidenceFact> citedFacts, boolean highlighted, boolean entitySafe,
            int independentSources, LocalDate asOf) {
        Set<IntelligenceTopic> topics = citedFacts.stream().map(EvidenceFact::getIntelligenceTopic)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (topics.isEmpty()) topics = Set.of(IntelligenceTopic.OTHER);
        Set<String> markets = citedFacts.stream().map(EvidenceFact::getMarketCode)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int authority = citedFacts.stream()
                .mapToInt(f -> f.getRawDoc().getSource().getAuthority().credibilityScore())
                .max().orElse(0);
        LocalDate newestDisclosure = citedFacts.stream()
                .map(f -> f.getRawDoc().getPublishedAt() == null ? null
                        : f.getRawDoc().getPublishedAt().atZone(REPORT_ZONE).toLocalDate())
                .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(asOf);
        long age = asOf == null || newestDisclosure == null ? 0
                : Math.max(0, ChronoUnit.DAYS.between(newestDisclosure, asOf));
        return CurationPriorityRules.score(new CurationPriorityRules.Input(
                Department.STRATEGY, topics, "VN", markets, authority,
                independentSources, age, highlighted, entitySafe));
    }

    private static boolean isAnalyticalContent(InterpretedClaim.Slot slot) {
        return slot == InterpretedClaim.Slot.IMPLICATION
                || slot == InterpretedClaim.Slot.NARRATIVE
                || slot == InterpretedClaim.Slot.DEEP_DIVE;
    }

    /** Draw a market-share bar only when one unambiguous percentage is verbatim near
     * a market-share phrase. Awards and unrelated growth percentages remain tables. */
    private static Integer metricPercent(String bucket, List<EvidenceFact> facts) {
        if (!BiFinding.MARKET_SHARE_OR_AWARD.equals(bucket) || facts == null) return null;
        java.util.regex.Pattern shareThenNumber = java.util.regex.Pattern.compile(
                "(?iu)(?:market\\s+share|thị\\s+phần|APE\\s+share).{0,100}?(\\d{1,3}(?:[.,]\\d+)?)\\s*%");
        java.util.regex.Pattern numberThenShare = java.util.regex.Pattern.compile(
                "(?iu)(\\d{1,3}(?:[.,]\\d+)?)\\s*%.{0,100}?(?:market\\s+share|thị\\s+phần|APE\\s+share)");
        Set<Integer> values = new LinkedHashSet<>();
        for (EvidenceFact fact : facts) {
            String text = fact == null || fact.getSpanText() == null ? "" : fact.getSpanText();
            collectSharePercent(text, shareThenNumber, values);
            collectSharePercent(text, numberThenShare, values);
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static void collectSharePercent(String text, java.util.regex.Pattern pattern,
                                            Set<Integer> values) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                double number = Double.parseDouble(matcher.group(1).replace(',', '.'));
                if (number >= 0 && number <= 100) values.add((int) Math.round(number));
            } catch (NumberFormatException ignored) { }
        }
    }

    public BiReportContent adapt(String title, String period, ProductReportAdapter.Snapshot snapshot, long docCount) {
        List<RoutedFinding> routedFindings = approvedFindings(snapshot.windowStart(), snapshot.windowEnd());
        List<BiFinding> findings = new ArrayList<>(routedFindings.stream().map(RoutedFinding::finding).toList());
        Set<String> sourceLines = new LinkedHashSet<>();
        int approvedCount = findings.size();
        findings.forEach(f -> f.citations().forEach(cit -> sourceLines.add(
                cit.label() + (cit.authority() != null ? " (" + authorityLabel(cit.authority()) + ")" : ""))));

        for (EvidenceFact f : snapshot.references()) {
            sourceLines.add(f.getRawDoc().getSource().getName() + " ("
                    + authorityLabel(f.getRawDoc().getSource().getAuthority().name()) + ")");
        }

        List<String> openGaps = new ArrayList<>();
        if (approvedCount == 0) {
            openGaps.add("Chưa có nhận định nào được duyệt ở Reviewer Console (/review) trong kỳ này — "
                    + "duyệt claim ở đó là cách trực tiếp nhất để làm dày báo cáo (mỗi claim duyệt "
                    + "xong xuất hiện ngay tại đây).");
        }
        // 2026-08-03: chỉ báo "chưa có nguồn" cho ĐÚNG mục nào thật sự không có finding nào
        // trong kỳ này — trước đây báo cả 5 mục vô điều kiện dù claim đã duyệt CÓ THỂ đã thuộc
        // đúng mục đó (Interpreter tự gán bi_bucket từ 2026-08-03, xem InterpretedClaim#biBucket).
        Set<String> presentBuckets = findings.stream().map(BiFinding::bucket).collect(java.util.stream.Collectors.toSet());
        List<String> missingBuckets = SPECIAL_BUCKETS.stream()
                .filter(b -> !presentBuckets.contains(b))
                .map(BUCKET_LABEL_VI::get)
                .toList();
        if (!missingBuckets.isEmpty()) {
            openGaps.add(String.join(", ", missingBuckets) + " — chưa có claim đã duyệt nào thuộc "
                    + (missingBuckets.size() == 1 ? "mục này" : "các mục này") + " trong kỳ; "
                    + "dùng Deep Research để bổ sung theo yêu cầu cụ thể, hoặc duyệt thêm claim liên quan ở /review.");
        }
        if (homeCompany == null || homeCompany.isBlank()) {
            openGaps.add("So sánh chiến lược cần cấu hình marketradar.home-company để xác định công ty gốc.");
        }

        return new BiReportContent(title, period, homeCompany,
                ZonedDateTime.now().format(TS_FMT), docCount,
                findings, List.copyOf(sourceLines), openGaps);
    }

    /**
     * Giữ báo cáo trung thực với KỲ của nó: claim vào kỳ theo ngày đăng của tài liệu gốc; claim
     * cấp report (rawDoc null, vd tóm tắt điều hành) thì theo ngày claim được tạo — đây là ngày
     * DUY NHẤT có ý nghĩa cho loại claim này nên không phải "đoán".
     *
     * 2026-08-03 (feedback: Deep Research tìm rộng khắp web, gặp nhiều trang không có metadata
     * ngày rõ ràng hơn hẳn so với crawl whitelist đã chọn lọc từ trước): TRƯỚC ĐÂY, khi RawDoc CÓ
     * nhưng không xác định được publishedAt, code từng fallback về ngày TẠO CLAIM — vô tình biến
     * 1 sự kiện cũ (vd hợp tác ký từ 2015) thành như thể mới xảy ra tuần này chỉ vì trang nguồn
     * thiếu metadata ngày. Giờ trường hợp đó bị LOẠI khỏi báo cáo theo kỳ thẳng — "không xác định
     * được ngày thật" phải nghĩa là "không đưa vào kỳ nào", không phải "coi như hôm nay".
     */
    private static boolean inWindow(InterpretedClaim claim, List<EvidenceFact> citedFacts,
                                    LocalDate start, LocalDate end) {
        if (start == null || end == null) return true;
        LocalDate published = null;
        if (claim.getRawDoc() == null) {
            if (claim.getCreatedAt() == null) return false;
            published = claim.getCreatedAt().atZone(REPORT_ZONE).toLocalDate();
        } else {
            if (claim.getRawDoc().getPublishedAt() != null) {
                published = claim.getRawDoc().getPublishedAt().atZone(REPORT_ZONE).toLocalDate();
            }
        }
        if (citedFacts == null || citedFacts.isEmpty()) {
            return published != null && !published.isBefore(start) && !published.isAfter(end);
        }
        LocalDate finalPublished = published;
        return citedFacts.stream().anyMatch(fact -> ReportTimeWindowRules.classify(
                new ReportTimeWindowRules.Dates(
                        finalPublished,
                        first(fact.getOccurredDate(), fact.getEventDate()),
                        fact.getEffectiveDate(), fact.getExpiryDate(),
                        first(fact.getForecastHorizon(), fact.getEventDateRangeStart())),
                start, end, 90) != ReportTimeWindowRules.Inclusion.EXCLUDE);
    }

    private static LocalDate first(LocalDate first, LocalDate second) {
        return first != null ? first : second;
    }

    private static ProductMarketScopeClassifier.MarketPosition resolveMarket(
            InterpretedClaim claim, List<EvidenceFact> citedFacts) {
        Set<String> marketCodes = citedFacts.stream().map(EvidenceFact::getMarketCode)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (marketCodes.size() > 1) {
            return new ProductMarketScopeClassifier.MarketPosition(
                    ProductMarketScope.INTERNATIONAL, "Multi-market");
        }
        if (marketCodes.size() == 1) {
            String code = marketCodes.iterator().next();
            if ("VN".equals(code)) {
                return new ProductMarketScopeClassifier.MarketPosition(ProductMarketScope.VIETNAM, "Vietnam");
            }
            return new ProductMarketScopeClassifier.MarketPosition(
                    ProductMarketScope.INTERNATIONAL, geographyLabel(code));
        }
        Source source = claim.getRawDoc() == null ? null : claim.getRawDoc().getSource();
        if (source != null && source.getDefaultMarketScope() == com.marketradar.domain.GeographyScope.VIETNAM) {
            return new ProductMarketScopeClassifier.MarketPosition(ProductMarketScope.VIETNAM, "Vietnam");
        }
        return new ProductMarketScopeClassifier.MarketPosition(ProductMarketScope.INTERNATIONAL,
                source == null ? "Global / regional" : geographyLabel(source.getDefaultMarketCode()));
    }

    private static String geographyLabel(String code) {
        if (code == null) return "Global / regional";
        return switch (code) {
            case "VN" -> "Vietnam";
            case "HK" -> "Hong Kong";
            case "SG" -> "Singapore";
            case "TW" -> "Taiwan";
            case "KR" -> "South Korea";
            case "JP" -> "Japan";
            case "CN" -> "China";
            case "ID" -> "Indonesia";
            case "MY" -> "Malaysia";
            case "PH" -> "Philippines";
            case "TH" -> "Thailand";
            case "US" -> "United States";
            case "GB" -> "United Kingdom";
            case "GLOBAL" -> "Global";
            default -> code;
        };
    }

    /** Resolve MỘT LẦN các EvidenceFact 1 claim cite — dùng chung cho cả citationsFor lẫn
     *  resolveRouting, tránh query facts 2 lần/claim. */
    private List<EvidenceFact> resolveFacts(InterpretedClaim claim) {
        Set<String> codes = claim.getFactCodesCsv() == null ? Set.of()
                : Arrays.stream(claim.getFactCodesCsv().split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) return List.of();
        List<EvidenceFact> resolved = facts.findAllByFactCodeInForAudit(codes.stream().toList());
        // A partially resolved citation set is not "mostly grounded". Nor may an old
        // claim survive a re-extraction edition by continuing to cite inactive facts.
        if (resolved.size() != codes.size() || resolved.stream().anyMatch(fact -> !fact.isActive()
                || fact.getRawDoc().isSampleData()
                || fact.getRawDoc().getDuplicateOfId() != null
                || !fact.getRawDoc().getSource().getUsePolicy().allowsAnalysis())) return List.of();
        return resolved;
    }

    /** Nhãn Router đã gán (bucket/subjectKey/...) cho claim này, suy từ fact ĐẦU TIÊN trong
     *  danh sách cite đã qua Router (biBucket != null) — hoặc rơi về rule cũ khi chưa fact nào
     *  qua Router (xem javadoc lớp: crawl trước khi có Router, hoặc chạy STUB). */
    private record RoutedLabels(String bucket, String subjectKey, String highlightCardLabel,
                                String severity, String severityTrend,
                                String kpiLabel, String kpiValue,
                                LocalDate eventDateRangeStart, LocalDate eventDateRangeEnd,
                                boolean highlight) {}

    private RoutedLabels resolveRouting(InterpretedClaim claim, List<EvidenceFact> citedFacts, boolean reportLevel) {
        // DEEP_DIVE tự khai báo bucket của chính nó (DeepDiveSynthesis đặt claim.biBucket=
        // DEEP_DIVE khi lưu) — KHÔNG được để rơi về bucket gốc của từng fact nó trích (1 bài
        // Deep Dive cố tình trích fact từ NHIỀU bucket khác nhau, xem Connector#DeepDiveCandidate).
        if (claim.getSlot() == InterpretedClaim.Slot.DEEP_DIVE) {
            return new RoutedLabels(BiFinding.DEEP_DIVE, null, null, null, null, null, null, null, null, false);
        }
        List<EvidenceFact> routedFacts = citedFacts.stream().filter(f -> f.getBiBucket() != null).toList();
        if (!routedFacts.isEmpty()) {
            Set<String> buckets = routedFacts.stream().map(EvidenceFact::getBiBucket)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> entityNames = routedFacts.stream().map(EvidenceFact::getSubjectEntityName)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            String bucket;
            if (buckets.size() == 1) bucket = buckets.iterator().next();
            else if (entityNames.size() > 1) bucket = BiFinding.STRATEGIC_COMPARISON;
            else bucket = BiFinding.COMPETITIVE_THEME;

            EvidenceFact representative = routedFacts.get(0);
            Set<String> legacySubjects = routedFacts.stream().map(EvidenceFact::getSubjectKey)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            String subject = entityNames.size() == 1 ? entityNames.iterator().next()
                    : (legacySubjects.size() == 1 ? legacySubjects.iterator().next() : null);
            boolean highlight = routedFacts.stream().anyMatch(EvidenceFact::isHighlight);
            return new RoutedLabels(bucket, subject,
                    buckets.size() == 1 ? representative.getHighlightCardLabel() : null,
                    buckets.size() == 1 ? representative.getSeverity() : null,
                    buckets.size() == 1 ? representative.getSeverityTrend() : null,
                    buckets.size() == 1 ? representative.getKpiLabel() : null,
                    buckets.size() == 1 ? representative.getKpiValue() : null,
                    routedFacts.stream().map(EvidenceFact::getEventDateRangeStart)
                            .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null),
                    routedFacts.stream().map(EvidenceFact::getEventDateRangeEnd)
                            .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null),
                    highlight);
        }
        // claim.getBiBucket() null cho tuyệt đại đa số claim (tin công ty thông thường) —
        // SPECIAL_BUCKETS là Set.of(...) nên contains(null) tự ném NPE, phải chặn trước.
        String legacyBucket = claim.getBiBucket() != null && SPECIAL_BUCKETS.contains(claim.getBiBucket())
                ? claim.getBiBucket()
                : (reportLevel ? BiFinding.COMPETITIVE_THEME : BiFinding.COMPANY_EVENT);
        return new RoutedLabels(legacyBucket, null, null, null, null, null, null, null, null,
                claim.getSlot() == InterpretedClaim.Slot.EXEC_SUMMARY);
    }

    /** Trích dẫn của claim = các fact nó cite (Invariant #1: luôn có factCodes khi L1 PASS);
     *  fallback về nguồn của tài liệu gốc nếu fact không resolve được (fact bị deactivate
     *  sau khi duyệt) — vẫn truy vết được, không bao giờ trích dẫn rỗng lặng lẽ. */
    private List<BiCitation> citationsFor(InterpretedClaim claim, List<EvidenceFact> citedFacts) {
        Map<String, BiCitation> unique = new LinkedHashMap<>();
        for (EvidenceFact f : citedFacts) {
            String label = f.getRawDoc().getPublisherName() != null
                    && !f.getRawDoc().getPublisherName().isBlank()
                    ? f.getRawDoc().getPublisherName()
                    : f.getRawDoc().getSource().getName();
            unique.putIfAbsent(label, citation(label, f.getRawDoc()));
        }
        if (unique.isEmpty() && claim.getRawDoc() != null) {
            String label = claim.getRawDoc().getPublisherName() != null
                    && !claim.getRawDoc().getPublisherName().isBlank()
                    ? claim.getRawDoc().getPublisherName()
                    : claim.getRawDoc().getSource().getName();
            unique.put(label, citation(label, claim.getRawDoc()));
        }
        return List.copyOf(unique.values());
    }

    /** Human-readable evidence authority plus acquisition lineage. The legacy record component
     *  remains named tierNote for JSON compatibility, but no publication decision uses tiers. */
    private static String tierLabel(RawDoc rawDoc) {
        String authority = authorityLabel(rawDoc.getSource().getAuthority().name());
        boolean fromDeepResearch = rawDoc.getIntakeMethod() == RawDoc.IntakeMethod.OPEN_SEARCH
                || rawDoc.getIntakeMethod() == RawDoc.IntakeMethod.BROWSER_RENDER;
        return fromDeepResearch ? authority + " · Deep Research" : authority;
    }

    private static String authorityLabel(String authority) {
        return authority == null ? "Unknown authority" : authority.replace('_', ' ');
    }

    private static BiCitation citation(String label, RawDoc rawDoc) {
        return new BiCitation(label, tierLabel(rawDoc), rawDoc.getUrl(),
                rawDoc.getSource().getAuthority().name(),
                rawDoc.getSource().getDefaultMarketCode(),
                rawDoc.getIntakeMethod() == null ? null : rawDoc.getIntakeMethod().name());
    }

}
