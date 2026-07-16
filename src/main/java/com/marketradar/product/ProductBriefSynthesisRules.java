package com.marketradar.product;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure, deterministic clustering and editorial assembly for the first Product lens.
 * It deliberately says "single signal" when corroboration is absent and never turns
 * one article into a market trend.
 */
public final class ProductBriefSynthesisRules {

    public static final String ALGORITHM_VERSION = "product-cluster-v9";
    private static final int MAX_SIGNALS_PER_INSIGHT = 6;
    private static final int MAX_INSIGHTS = 5;

    private ProductBriefSynthesisRules() {}

    public enum Theme {
        VN_PRODUCT_OFFER,
        VN_REGULATORY_CHANGE,
        DISTRIBUTION_INNOVATION,
        REGIONAL_TRANSFER,
        MARKET_PATTERN
    }

    private enum Topic {
        WEALTH_LEGACY,
        HEALTH_PROTECTION,
        DIGITAL_EMBEDDED,
        AGENT_BANCA,
        PRICING_BENEFITS,
        CAPITAL_SOLVENCY,
        AGENT_REGULATION,
        PRODUCT_APPROVAL,
        SALES_GROWTH,
        CLAIMS,
        OTHER
    }

    private record Bucket(Theme theme, Topic topic, String fallbackKey) {}

    public record Signal(
            String factCode,
            long rawDocId,
            String sourceCode,
            int sourceTier,
            String company,
            String productName,
            String title,
            String evidenceSpan,
            String eventType,
            String marketScope,
            String modelVersion,
            String pipelineVersion,
            LocalDate publishedDate,
            String clusterKey,
            int clusterDocumentCount,
            int clusterIndependentSourceCount,
            String conflictState,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            String temporalStatus,
            boolean futureActionEligible,
            String summaryVi,
            String summaryEn,
            int materialityScore,
            Set<String> productKiqs) {
        public Signal {
            productKiqs = productKiqs == null ? Set.of() : Set.copyOf(productKiqs);
        }
    }

    public record Draft(
            Theme theme,
            String kiqCode,
            String headlineVi,
            String headlineEn,
            String whatVi,
            String whatEn,
            String patternVi,
            String patternEn,
            String soWhatVi,
            String soWhatEn,
            String nowWhatVi,
            String nowWhatEn,
            String caveatVi,
            String caveatEn,
            ProductBriefInsight.Confidence confidence,
            int materialityScore,
            List<String> factCodes,
            List<Signal> signals,
            int independentClusterCount,
            int independentDocumentCount,
            int independentSourceCount,
            boolean conflictFree,
            boolean futureActionEligible) {}

    public static List<Draft> synthesize(List<Signal> input) {
        if (input == null || input.isEmpty()) return List.of();

        // One fact per document within a theme prevents a long promotion/rule page from
        // manufacturing a false multi-signal pattern merely because it yielded many spans.
        Map<Bucket, List<Signal>> grouped = new LinkedHashMap<>();
        input.stream()
                .filter(ProductBriefSynthesisRules::usable)
                .sorted(Comparator.comparingInt(Signal::materialityScore).reversed()
                        .thenComparing(Signal::publishedDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Signal::factCode))
                .forEach(s -> {
                    Topic topic = topicOf(s);
                    Theme theme = themeOf(s, topic);
                    if (theme == null) return;
                    String fallback = topic == Topic.OTHER ? "DOC:" + s.rawDocId() : topic.name();
                    List<Signal> bucket = grouped.computeIfAbsent(
                            new Bucket(theme, topic, fallback), ignored -> new ArrayList<>());
                    // Keep at most two complementary facts from one article. Document
                    // counts—not fact counts—still drive pattern/confidence language.
                    long fromSameDoc = bucket.stream().filter(existing -> existing.rawDocId() == s.rawDocId()).count();
                    if (fromSameDoc < 2) bucket.add(s);
                });

        List<Draft> drafts = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<Signal> signals = entry.getValue().stream()
                    .limit(MAX_SIGNALS_PER_INSIGHT).toList();
            // A generic metric/event is not an insight. The fallback market-pattern
            // lane is allowed only when at least two documents share a real topic.
            if (entry.getKey().theme() == Theme.MARKET_PATTERN
                    && (entry.getKey().topic() == Topic.OTHER || signals.size() < 2)) continue;
            if (!signals.isEmpty()) drafts.add(toDraft(entry.getKey().theme(), entry.getKey().topic(), signals));
        }
        return drafts.stream()
                .sorted(Comparator.comparingInt(Draft::materialityScore).reversed()
                        .thenComparing(d -> d.theme().ordinal()))
                .limit(MAX_INSIGHTS)
                .toList();
    }

    private static boolean usable(Signal s) {
        return s != null && notBlank(s.factCode()) && notBlank(s.eventType())
                && notBlank(s.clusterKey())
                && "NONE".equals(s.conflictState())
                && s.futureActionEligible()
                && (notBlank(s.summaryVi()) || notBlank(s.summaryEn()));
    }

    private static Theme themeOf(Signal s, Topic topic) {
        boolean regional = "REGIONAL".equalsIgnoreCase(s.marketScope());
        if (regional && s.productKiqs().contains("KIQ_4_TRANSFERABLE_INNOVATION")) {
            return Theme.REGIONAL_TRANSFER;
        }
        // Connect regional customer/market evidence to product moves when it is
        // topically specific. This yields a decision story instead of two news cards.
        if (regional && s.productKiqs().contains("KIQ_2_MARKET_PATTERN")
                && EnumSet.of(Topic.WEALTH_LEGACY, Topic.HEALTH_PROTECTION, Topic.DIGITAL_EMBEDDED)
                .contains(topic)) {
            return Theme.REGIONAL_TRANSFER;
        }
        // Foreign regulation is context, not a Vietnam Product requirement. It needs
        // a separate regulatory-intelligence lens rather than being mixed into KIQ 3.
        if (regional && "REGULATORY_CHANGE".equalsIgnoreCase(s.eventType())) return null;
        if ("REGULATORY_CHANGE".equalsIgnoreCase(s.eventType())) return Theme.VN_REGULATORY_CHANGE;
        if (isProductOffer(s.eventType())) return regional ? Theme.REGIONAL_TRANSFER : Theme.VN_PRODUCT_OFFER;
        if ("DISTRIBUTION_CHANGE".equalsIgnoreCase(s.eventType())
                || "SERVICE_EXPERIENCE_CHANGE".equalsIgnoreCase(s.eventType())) {
            return regional ? Theme.REGIONAL_TRANSFER : Theme.DISTRIBUTION_INNOVATION;
        }
        if ("MARKETING_PROMOTION".equalsIgnoreCase(s.eventType())
                || "CORPORATE_NEWS".equalsIgnoreCase(s.eventType())) return null;
        if (s.productKiqs().contains("KIQ_4_TRANSFERABLE_INNOVATION")) {
            return Theme.DISTRIBUTION_INNOVATION;
        }
        return Theme.MARKET_PATTERN;
    }

    private static Topic topicOf(Signal s) {
        String text = normalize(String.join(" ", nullToEmpty(s.productName()),
                nullToEmpty(s.title()), nullToEmpty(s.evidenceSpan()),
                nullToEmpty(s.summaryEn()), nullToEmpty(s.summaryVi())));
        if (has(text, "wealth", "legacy", "estate", "inheritance", "multi-generational", "savings",
                "retirement", "hưu trí", "tích lũy", "tài sản", "thừa kế")) return Topic.WEALTH_LEGACY;
        if (has(text, "health", "medical", "critical illness", "cancer", "hospital",
                "longevity", "sức khỏe", "y tế", "bệnh hiểm nghèo", "ung thư", "nằm viện")) {
            return Topic.HEALTH_PROTECTION;
        }
        if (has(text, "embedded", "digital platform", "digital insurance platform", "e-kyc", "online underwriting", "api",
                "bảo hiểm nhúng", "nền tảng số", "thẩm định trực tuyến")) return Topic.DIGITAL_EMBEDDED;
        if (has(text, "agent", "advisor", "bancassurance", "distribution", "đại lý",
                "tư vấn viên", "phân phối", "chứng chỉ")) {
            return "REGULATORY_CHANGE".equalsIgnoreCase(s.eventType()) ? Topic.AGENT_REGULATION : Topic.AGENT_BANCA;
        }
        if (has(text, "capital", "solvency", "risk-based capital", "rbc", "vốn", "khả năng thanh toán")) {
            return Topic.CAPITAL_SOLVENCY;
        }
        if (has(text, "approval", "product filing", "policy wording", "phê duyệt", "điều khoản")) {
            return Topic.PRODUCT_APPROVAL;
        }
        if (has(text, "benefit", "premium", "fee", "coverage", "quyền lợi", "phí", "phạm vi bảo hiểm")) {
            return Topic.PRICING_BENEFITS;
        }
        if (has(text, "claim", "payout", "paid out", "bồi thường", "chi trả")) return Topic.CLAIMS;
        if (has(text, "sales", "revenue", "growth", "new business", "doanh thu", "tăng trưởng")) {
            return Topic.SALES_GROWTH;
        }
        return Topic.OTHER;
    }

    private static Draft toDraft(Theme theme, Topic topic, List<Signal> signals) {
        int docs = (int) signals.stream().map(Signal::rawDocId).distinct().count();
        int sources = (int) signals.stream().map(Signal::sourceCode).filter(ProductBriefSynthesisRules::notBlank).distinct().count();
        int independentClusters = (int) signals.stream().map(Signal::clusterKey)
                .filter(ProductBriefSynthesisRules::notBlank).distinct().count();
        int companies = (int) signals.stream().map(Signal::company).filter(ProductBriefSynthesisRules::notBlank).distinct().count();
        boolean official = signals.stream().anyMatch(s -> s.sourceTier() == 1);
        boolean mixedLegacy = signals.stream().map(Signal::modelVersion)
                .anyMatch(m -> m == null || m.contains("gpt-4o-mini") || m.contains("UNKNOWN_LEGACY"));

        String whatVi = joinSummaries(signals, true);
        String whatEn = joinSummaries(signals, false);
        String patternVi;
        String patternEn;
        if (independentClusters >= 2 && sources >= 2) {
            patternVi = "So sánh gồm " + independentClusters + " sự kiện độc lập từ " + sources + " nguồn"
                    + (companies > 0 ? " và " + companies + " doanh nghiệp được nêu tên" : "")
                    + ". Đây là mẫu cần kiểm tra thêm, chưa tự động được coi là xu hướng toàn thị trường.";
            patternEn = "The comparison contains " + independentClusters + " independent events from " + sources
                    + (sources == 1 ? " source" : " sources")
                    + (companies > 0 ? " and " + companies + " named company/companies" : "")
                    + ". It is a pattern to test, not automatically a market-wide trend.";
        } else if (sources < 2) {
            patternVi = "Đây là một tín hiệu đơn nguồn; chưa đủ bằng chứng để gọi là xu hướng.";
            patternEn = "This is a single-source signal; evidence is insufficient to call it a trend.";
        } else {
            patternVi = "Đây là một sự kiện đã được nhiều nguồn xác nhận, nhưng chưa có sự kiện độc lập thứ hai để gọi là xu hướng.";
            patternEn = "This event is corroborated by multiple sources, but there is no second independent event to call it a trend.";
        }

        TextContract contract = contract(theme, topic, signals);
        ProductBriefInsight.Confidence confidence = confidence(independentClusters, sources, official);
        String caveatVi = caveat(true, docs, sources, official, mixedLegacy);
        String caveatEn = caveat(false, docs, sources, official, mixedLegacy);
        int averageScore = (int) Math.round(signals.stream().mapToInt(Signal::materialityScore).average().orElse(0));
        List<String> codes = signals.stream().map(Signal::factCode).distinct().toList();

        return new Draft(theme, contract.kiqCode(), contract.headlineVi(), contract.headlineEn(),
                whatVi, whatEn, patternVi, patternEn,
                contract.soWhatVi(), contract.soWhatEn(), contract.nowWhatVi(), contract.nowWhatEn(),
                caveatVi, caveatEn, confidence, averageScore, codes, List.copyOf(signals),
                independentClusters, docs, sources,
                signals.stream().allMatch(s -> "NONE".equals(s.conflictState())),
                signals.stream().allMatch(Signal::futureActionEligible));
    }

    private static String joinSummaries(List<Signal> signals, boolean vi) {
        return signals.stream().limit(3)
                .map(s -> summaryFor(s, vi))
                .filter(ProductBriefSynthesisRules::notBlank)
                .collect(Collectors.joining(" "));
    }

    private static String summaryFor(Signal signal, boolean vi) {
        // Do not substitute an original-language summary into the opposite language
        // field. A missing translation must remain missing rather than contaminating
        // a bilingual Product insight. Current-news cards still retain the original
        // title and exact evidence separately for audit.
        return vi
                ? BilingualTextPolicy.safeDisplaySummary(signal.summaryVi(), true)
                : BilingualTextPolicy.safeDisplaySummary(signal.summaryEn(), false);
    }

    private static ProductBriefInsight.Confidence confidence(int clusters, int sources, boolean official) {
        if (sources >= 3 && clusters >= 3) return ProductBriefInsight.Confidence.HIGH;
        if (sources >= 2 && clusters >= 2) return ProductBriefInsight.Confidence.MEDIUM;
        return ProductBriefInsight.Confidence.LOW;
    }

    private static String caveat(boolean vi, int docs, int sources, boolean official, boolean mixedLegacy) {
        List<String> notes = new ArrayList<>();
        if (docs < 2) notes.add(vi ? "chỉ có một tài liệu" : "only one document");
        if (sources < 2) notes.add(vi ? "chưa có nguồn độc lập thứ hai" : "no second independent source");
        if (!official) notes.add(vi ? "chưa có nguồn cấp 1" : "no tier-1 source");
        if (mixedLegacy) notes.add(vi ? "có bằng chứng từ quy trình/mô hình cũ" : "includes legacy pipeline/model evidence");
        if (notes.isEmpty()) return vi
                ? "Đã có nhiều tài liệu/nguồn; vẫn cần người phụ trách Sản phẩm xác nhận tính áp dụng nội bộ."
                : "Multiple documents/sources are present; a Product owner must still confirm internal applicability.";
        return (vi ? "Giới hạn: " : "Limitations: ") + String.join(vi ? "; " : "; ", notes) + ".";
    }

    private record TextContract(String kiqCode, String headlineVi, String headlineEn,
                                String soWhatVi, String soWhatEn,
                                String nowWhatVi, String nowWhatEn) {}

    private static TextContract contract(Theme theme, Topic topic, List<Signal> signals) {
        return switch (theme) {
            case VN_PRODUCT_OFFER -> new TextContract(
                    ProductKiqContract.leadCodes(ProductKiqContract.Kiq.OFFER_CHANGE),
                    offerHeadline(topic, true), offerHeadline(topic, false),
                    "Các tín hiệu này có thể thay đổi chuẩn so sánh về quyền lợi, phí hoặc phân khúc; chúng chưa chứng minh nhu cầu khách hàng hay yêu cầu sao chép đối thủ.",
                    "These signals may change the benchmark for benefits, fees or segments; they do not prove customer demand or justify copying a competitor.",
                    "Chủ trì: Bộ phận Sản phẩm – Danh mục sản phẩm. Trong 30 ngày, lập bảng so sánh quyền lợi–phí–đối tượng với danh mục hiện tại; tiêu chí quyết định là có khoảng trống đủ lớn để nghiên cứu khách hàng hoặc tạo mẫu thử.",
                    "Owner: Product Portfolio. Within 30 days, compare benefits, fees and target segments with the current portfolio; the decision criterion is a gap large enough to merit customer research or a prototype.");
            case VN_REGULATORY_CHANGE -> regulationContract(topic);
            case DISTRIBUTION_INNOVATION -> new TextContract(
                    ProductKiqContract.leadCodes(ProductKiqContract.Kiq.TRANSFERABLE_INNOVATION),
                    "Đổi mới phân phối có thể kéo theo thay đổi thiết kế sản phẩm",
                    "Distribution innovation may require product-design changes",
                    "Cơ chế kênh mới có thể thay đổi thời điểm mua, mức đơn giản của quyền lợi và yêu cầu tích hợp; tính phù hợp với khách hàng Việt Nam chưa được chứng minh.",
                    "A new channel can change purchase moments, benefit simplicity and integration needs; fit for Vietnamese customers is not yet proven.",
                    "Chủ trì: Bộ phận Sản phẩm – Đổi mới sản phẩm. Trong 45 ngày, phối hợp Phân phối để xác định tình huống sử dụng và ràng buộc sản phẩm; tiêu chí tiếp tục là một thử nghiệm nhỏ có chỉ số thành công đo được.",
                    "Owner: Product Innovation. Within 45 days, work with Distribution to define the use case and product constraints; the continuation criterion is a small experiment with a measurable success metric.");
            case REGIONAL_TRANSFER -> regionalContract(topic, signals);
            case MARKET_PATTERN -> new TextContract(
                    ProductKiqContract.leadCodes(ProductKiqContract.Kiq.MARKET_PATTERN),
                    "Tín hiệu thị trường cần thêm bằng chứng trước khi ảnh hưởng lộ trình",
                    "Market signals need more evidence before affecting the roadmap",
                    "Các số liệu hoặc sự kiện này cung cấp bối cảnh nhưng chưa tự thân chỉ ra thay đổi cần thiết trong thiết kế sản phẩm.",
                    "These metrics or events provide context but do not by themselves establish a required product-design change.",
                    "Chủ trì: Bộ phận Sản phẩm – Phân tích thị trường. Trong 30 ngày, kiểm tra thêm nguồn độc lập và liên kết với quyết định danh mục; tiêu chí nâng cấp là có bằng chứng sản phẩm, quy định hoặc khách hàng bổ sung.",
                    "Owner: Product Insights. Within 30 days, seek another independent source and connect the signal to a portfolio decision; the promotion criterion is additional product, regulatory or customer evidence.");
        };
    }

    private static TextContract regulationContract(Topic topic) {
        if (topic == Topic.AGENT_REGULATION) {
            return new TextContract(
                    ProductKiqContract.leadCodes(ProductKiqContract.Kiq.REGULATORY_RESPONSE),
                    regulationHeadline(topic, true), regulationHeadline(topic, false),
                    "Việc chứng chỉ hết hiệu lực có thể làm giảm năng lực tư vấn hoặc ảnh hưởng mức độ sẵn sàng của đợt ra mắt; đây không phải thay đổi đề xuất sản phẩm.",
                    "Certificate expiry may constrain adviser capacity or launch readiness; it is not itself a change to the product proposition.",
                    "Chủ trì: Bộ phận Sản phẩm – Quản trị sản phẩm. Trong 30 ngày, phối hợp Phân phối xác định năng lực tư vấn bị ảnh hưởng và đối chiếu các đợt ra mắt phụ thuộc kênh; tiêu chí hoàn tất là có phương án trước mốc hiệu lực.",
                    "Owner: Product Governance. Within 30 days, work with Distribution to quantify affected adviser capacity and map channel-dependent launches; the completion criterion is a mitigation before the effective date.");
        }
        return new TextContract(
                ProductKiqContract.leadCodes(ProductKiqContract.Kiq.REGULATORY_RESPONSE),
                regulationHeadline(topic, true), regulationHeadline(topic, false),
                "Quy định có thể ảnh hưởng điều khoản, quyền lợi, định phí, phê duyệt hoặc quy trình phân phối; phạm vi pháp lý phải được xác nhận trước khi đổi sản phẩm.",
                "The rule may affect wording, benefits, pricing, approval or distribution; Legal must confirm scope before Product changes an offer.",
                "Chủ trì: Bộ phận Sản phẩm – Quản trị sản phẩm. Trong 30 ngày, phối hợp Pháp chế lập bản đồ tác động theo sản phẩm, điều khoản và mốc hiệu lực; tiêu chí hoàn tất là mỗi tác động có quyết định và người phụ trách Sản phẩm.",
                "Owner: Product Governance. Within 30 days, work with Legal on an impact map by product, clause and effective date; the completion criterion is a decision and Product owner for every impact.");
    }

    private static TextContract regionalContract(Topic topic, List<Signal> signals) {
        if (topic == Topic.WEALTH_LEGACY) {
            boolean hasDemandEvidence = signals.stream().anyMatch(s -> "METRIC".equalsIgnoreCase(s.eventType())
                    || s.productKiqs().contains("KIQ_2_MARKET_PATTERN"));
            return new TextContract(
                    ProductKiqContract.leadCodes(ProductKiqContract.Kiq.TRANSFERABLE_INNOVATION),
                    regionalHeadline(topic, true), regionalHeadline(topic, false),
                    hasDemandEvidence
                            ? "Sự kết hợp giữa đề xuất di sản HNW và chỉ báo nhu cầu tạo ra một không gian cơ hội đáng kiểm tra, chưa chứng minh nhu cầu tại Việt Nam. Ẩn số chính là quy mô phân khúc, tính khả thi pháp lý của chỉ dẫn sau biến cố và mức sẵn sàng chi trả."
                            : "Đề xuất này mở rộng chuẩn so sánh từ quyền lợi tài chính sang quản trị di sản và chỉ dẫn sau biến cố; một động thái đối thủ chưa chứng minh nhu cầu tại Việt Nam hay mức sẵn sàng chi trả.",
                    hasDemandEvidence
                            ? "The combination of an HNW legacy proposition and demand indicators creates a testable opportunity space, not proof of Vietnam demand. Key unknowns are segment size, legal feasibility of post-event instructions and willingness to pay."
                            : "The proposition expands the benchmark from financial benefits to legacy governance and post-event instructions; one competitor move does not prove Vietnam demand or willingness to pay.",
                    "Chủ trì: Bộ phận Sản phẩm – Đổi mới sản phẩm. Trong 60 ngày, đối chiếu danh mục, rà soát pháp lý cơ chế và phỏng vấn khách hàng mục tiêu; tiêu chí tiếp tục/dừng là tính khả thi pháp lý và bằng chứng về mức sẵn sàng chi trả.",
                    "Owner: Product Innovation. Within 60 days, benchmark the portfolio, legally screen the mechanism and interview target customers; the go/no-go criterion is legal feasibility plus willingness-to-pay evidence.");
        }
        return new TextContract(
                ProductKiqContract.leadCodes(ProductKiqContract.Kiq.TRANSFERABLE_INNOVATION),
                regionalHeadline(topic, true), regionalHeadline(topic, false),
                "Các mô hình khu vực có thể mở rộng không gian ý tưởng, nhưng khác biệt về quy định, hành vi và kinh tế sản phẩm có thể làm kết luận không chuyển giao được.",
                "Regional models can widen the idea space, but regulatory, behavioral and product-economics differences may prevent transfer.",
                "Chủ trì: Bộ phận Sản phẩm – Đổi mới sản phẩm. Trong 60 ngày, chọn tối đa một ý tưởng để rà soát quy định và kiểm tra bằng chứng khách hàng cùng hiệu quả kinh tế sản phẩm; tiêu chí vào lộ trình là vượt cả ba cổng.",
                "Owner: Product Innovation. Within 60 days, select at most one idea for regulatory screening, customer evidence and unit-economics testing; the roadmap criterion is passing all three gates.");
    }

    private static String offerHeadline(Topic topic, boolean vi) {
        return switch (topic) {
            case HEALTH_PROTECTION -> vi ? "Đề xuất sức khỏe và bảo vệ cần đối chiếu khoảng trống quyền lợi"
                    : "Health and protection propositions need benefit-gap benchmarking";
            case WEALTH_LEGACY -> vi ? "Đề xuất tích lũy và di sản cần đối chiếu giá trị khách hàng"
                    : "Wealth and legacy propositions need customer-value benchmarking";
            case PRICING_BENEFITS -> vi ? "Thay đổi quyền lợi hoặc phí cần đối chiếu trực tiếp"
                    : "Benefit or fee changes need direct benchmarking";
            default -> vi ? "Thay đổi đề xuất giá trị sản phẩm cần được đối chiếu"
                    : "Product value-proposition changes need benchmarking";
        };
    }

    private static String regulationHeadline(Topic topic, boolean vi) {
        return switch (topic) {
            case AGENT_REGULATION -> vi ? "Thay đổi quy định đại lý cần lập bản đồ tác động Sản phẩm–Phân phối"
                    : "Agent-rule change needs a Product–Distribution impact map";
            case PRODUCT_APPROVAL -> vi ? "Thay đổi phê duyệt sản phẩm cần Sản phẩm và Pháp chế đánh giá phạm vi"
                    : "Product-approval change needs Product and Legal scope assessment";
            default -> vi ? "Thay đổi quy định cần Sản phẩm và Pháp chế đánh giá phạm vi"
                    : "Regulatory change needs Product and Legal scope assessment";
        };
    }

    private static String regionalHeadline(Topic topic, boolean vi) {
        return switch (topic) {
            case WEALTH_LEGACY -> vi ? "Đề xuất tài sản và di sản khu vực đáng để kiểm tra khả năng chuyển giao"
                    : "Regional wealth and legacy propositions merit a Vietnam transfer test";
            case HEALTH_PROTECTION -> vi ? "Đổi mới sức khỏe khu vực cần kiểm tra khả năng áp dụng tại Việt Nam"
                    : "Regional health innovation needs a Vietnam applicability test";
            case DIGITAL_EMBEDDED -> vi ? "Mô hình số khu vực cần kiểm tra tình huống sử dụng trước khi vào lộ trình"
                    : "Regional digital models need a use-case test before entering the roadmap";
            case AGENT_BANCA -> vi ? "Mô hình phân phối khu vực cần kiểm tra ràng buộc thiết kế sản phẩm"
                    : "Regional distribution models need a product-design constraint test";
            default -> vi ? "Tín hiệu khu vực là giả thuyết chuyển giao, không phải khuyến nghị sao chép"
                    : "Regional signals are transfer hypotheses, not copy recommendations";
        };
    }

    private static boolean has(String text, String... terms) {
        for (String term : terms) {
            String regex = "(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(term)
                    + "(?![\\p{L}\\p{N}])";
            if (java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE
                    | java.util.regex.Pattern.UNICODE_CASE).matcher(text).find()) return true;
        }
        return false;
    }
    private static boolean isProductOffer(String eventType) {
        return "PRODUCT_LAUNCH".equalsIgnoreCase(eventType)
                || "PRODUCT_CHANGE".equalsIgnoreCase(eventType)
                || "BENEFIT_CHANGE".equalsIgnoreCase(eventType)
                || "PRICING_CHANGE".equalsIgnoreCase(eventType)
                || "PRODUCT_WITHDRAWAL".equalsIgnoreCase(eventType);
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
