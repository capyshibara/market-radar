package com.marketradar.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.product.ProductReportCadence;
import com.marketradar.product.ProductReportEditorialDraft;
import com.marketradar.product.ProductMarketScopeClassifier;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.ProductReportEditorialDraftRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Human editorial layer for rolling Product reports.
 *
 * <p>It never changes evidence, classifications, claims, or publication-gate state. The
 * narrative is stored separately, labelled as human synthesis, and keeps an explicit
 * fact-code register so every quantitative statement remains auditable.</p>
 */
@Service
public class ProductReportEditorialService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String DEFAULT_EDITOR = "Nguyễn Phương Đình · Product SME";
    private static final Set<String> EXHIBIT_TYPES = Set.of(
            "BAR", "KPI", "TIMELINE", "FLOW", "MATRIX", "ROADMAP");
    private static final Set<String> EXHIBIT_TONES = Set.of(
            "BLUE", "TEAL", "GOLD", "CORAL", "VIOLET");

    private final Map<String, EditorialBrief> transientDrafts = new ConcurrentHashMap<>();
    private ProductReportEditorialDraftRepository drafts;
    private EvidenceFactRepository facts;
    private ObjectMapper json;

    public ProductReportEditorialService() {}

    @Autowired
    public ProductReportEditorialService(ProductReportEditorialDraftRepository drafts,
                                         EvidenceFactRepository facts, ObjectMapper json) {
        this.drafts = drafts;
        this.facts = facts;
        this.json = json;
    }

    public EditorialBrief current(ProductReportCadence cadence, Locale locale) {
        boolean vi = isVi(locale);
        String language = vi ? "vi" : "en";
        EditorialBrief memory = transientDrafts.get(key(cadence, language));
        if (memory != null) return memory;
        if (drafts != null && json != null) {
            EditorialBrief stored = drafts.findByCadenceAndLanguage(cadence, language)
                    .map(draft -> read(draft.getContentJson()))
                    .orElse(null);
            if (stored != null) return completeLegacyDraft(cadence, vi, stored);
        }
        return defaults(cadence, vi);
    }

    @Transactional(readOnly = true)
    public List<EditorialReference> references(EditorialBrief brief) {
        return references(brief, Set.of());
    }

    @Transactional(readOnly = true)
    public List<EditorialReference> references(EditorialBrief brief, Set<String> excludedFactCodes) {
        if (facts == null || brief == null || brief.citedFactCodes().isEmpty()) return List.of();
        Map<String, EvidenceFact> byCode = facts.findAllByFactCodeInForAudit(
                        brief.citedFactCodes()).stream()
                .collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity(),
                        (first, ignored) -> first));
        Map<Long, EditorialReference> result = new LinkedHashMap<>();
        for (String code : brief.citedFactCodes()) {
            if (excludedFactCodes != null && excludedFactCodes.contains(code)) continue;
            EvidenceFact fact = byCode.get(code);
            if (fact == null) continue;
            Long documentId = fact.getRawDoc().getId();
            EditorialReference existing = result.get(documentId);
            ProductMarketScopeClassifier.MarketPosition position =
                    ProductMarketScopeClassifier.classify(fact);
            result.put(documentId, existing == null
                    ? new EditorialReference(code, fact.getRawDoc().getTitle(),
                    fact.getRawDoc().getUrl(), fact.getRawDoc().getSource().getName(),
                    position.scope().name(), position.geography())
                    : new EditorialReference(existing.factCode() + " · " + code,
                    existing.title(), existing.url(), existing.publisher(),
                    existing.marketScope(), existing.geography()));
        }
        return List.copyOf(result.values());
    }

    @Transactional
    public EditorialBrief save(ProductReportCadence cadence, Locale locale,
                               MultiValueMap<String, String> form) {
        EditorialBrief current = current(cadence, locale);
        List<EditorialTakeaway> takeaways = new ArrayList<>();
        for (int i = 0; i < current.takeaways().size(); i++) {
            EditorialTakeaway old = current.takeaways().get(i);
            takeaways.add(new EditorialTakeaway(old.number(),
                    value(form, "takeaway." + i + ".title", old.title()),
                    value(form, "takeaway." + i + ".body", old.body()),
                    value(form, "takeaway." + i + ".implication", old.implication()),
                    old.citationCodes()));
        }
        List<EditorialDecision> decisions = new ArrayList<>();
        for (int i = 0; i < current.decisions().size(); i++) {
            EditorialDecision old = current.decisions().get(i);
            decisions.add(new EditorialDecision(
                    value(form, "decision." + i + ".horizon", old.horizon()),
                    value(form, "decision." + i + ".action", old.action()),
                    value(form, "decision." + i + ".rationale", old.rationale())));
        }
        String editor = value(form, "editor", current.editor());
        MarketBridge oldBridge = current.marketBridge();
        MarketBridge revisedBridge = new MarketBridge(
                value(form, "bridge.domesticRead", oldBridge.domesticRead()),
                value(form, "bridge.internationalRead", oldBridge.internationalRead()),
                value(form, "bridge.vietnamImplication", oldBridge.vietnamImplication()),
                value(form, "bridge.decisionQuestion", oldBridge.decisionQuestion()));
        List<EditorialExhibit> exhibits = new ArrayList<>();
        for (int i = 0; i < current.exhibits().size(); i++) {
            EditorialExhibit old = current.exhibits().get(i);
            List<ExhibitDatum> data = new ArrayList<>();
            for (int j = 0; j < old.data().size(); j++) {
                ExhibitDatum datum = old.data().get(j);
                data.add(new ExhibitDatum(
                        value(form, "exhibit." + i + ".data." + j + ".label", datum.label()),
                        value(form, "exhibit." + i + ".data." + j + ".value", datum.value()),
                        value(form, "exhibit." + i + ".data." + j + ".context", datum.context()),
                        value(form, "exhibit." + i + ".data." + j + ".detail", datum.detail()),
                        width(form, "exhibit." + i + ".data." + j + ".width", datum.width()),
                        tone(value(form, "exhibit." + i + ".data." + j + ".tone", datum.tone()),
                                datum.tone())));
            }
            exhibits.add(new EditorialExhibit(old.number(),
                    exhibitType(value(form, "exhibit." + i + ".type", old.type()), old.type()),
                    Boolean.parseBoolean(value(form, "exhibit." + i + ".enabled",
                            Boolean.toString(old.enabled()))),
                    value(form, "exhibit." + i + ".title", old.title()),
                    value(form, "exhibit." + i + ".takeaway", old.takeaway()),
                    value(form, "exhibit." + i + ".note", old.note()),
                    safeCitations(value(form, "exhibit." + i + ".citationCodes",
                                    old.citationCodes()), current.citedFactCodes(), old.citationCodes()),
                    List.copyOf(data)));
        }
        EditorialBrief revised = new EditorialBrief(
                value(form, "title", current.title()),
                value(form, "deck", current.deck()),
                value(form, "leadLabel", current.leadLabel()),
                value(form, "leadHeadline", current.leadHeadline()),
                value(form, "leadNarrative", current.leadNarrative()),
                revisedBridge,
                List.copyOf(takeaways), current.chart(), List.copyOf(exhibits),
                value(form, "numbersHeadline", current.numbersHeadline()),
                List.copyOf(decisions),
                value(form, "watchlist", current.watchlist()),
                value(form, "editorialBoundary", current.editorialBoundary()),
                current.citedFactCodes(), editor,
                DateTimeFormatter.ofPattern(isVi(locale) ? "dd/MM/yyyy HH:mm" : "MMM d, yyyy HH:mm",
                        locale).withZone(REPORT_ZONE).format(Instant.now()),
                "HUMAN_CURATED");
        String language = isVi(locale) ? "vi" : "en";
        transientDrafts.put(key(cadence, language), revised);
        if (drafts != null && json != null) {
            try {
                String content = json.writeValueAsString(revised);
                ProductReportEditorialDraft draft = drafts.findByCadenceAndLanguage(cadence, language)
                        .orElseGet(() -> new ProductReportEditorialDraft(cadence, language, content, editor));
                draft.replace(content, editor);
                drafts.save(draft);
            } catch (Exception error) {
                throw new IllegalStateException("Could not save the Product report editorial draft", error);
            }
        }
        return revised;
    }

    private EditorialBrief read(String body) {
        try { return json.readValue(body, EditorialBrief.class); }
        catch (Exception ignored) { return null; }
    }

    private static EditorialBrief defaults(ProductReportCadence cadence, boolean vi) {
        return switch (cadence) {
            case WEEKLY -> weekly(vi);
            case MONTHLY -> monthly(vi);
            case QUARTERLY -> quarterly(vi);
        };
    }

    /** Older saved drafts predate the domestic/international bridge and Exhibit Studio. */
    private static EditorialBrief completeLegacyDraft(ProductReportCadence cadence, boolean vi,
                                                       EditorialBrief stored) {
        EditorialBrief fallback = defaults(cadence, vi);
        if (stored.marketBridge() != null && stored.exhibits() != null
                && !stored.exhibits().isEmpty()) return stored;
        return new EditorialBrief(stored.title(), stored.deck(), stored.leadLabel(),
                stored.leadHeadline(), stored.leadNarrative(),
                stored.marketBridge() == null ? fallback.marketBridge() : stored.marketBridge(),
                stored.takeaways(), stored.chart(),
                stored.exhibits() == null || stored.exhibits().isEmpty()
                        ? fallback.exhibits() : stored.exhibits(),
                stored.numbersHeadline(), stored.decisions(),
                stored.watchlist(), stored.editorialBoundary(), stored.citedFactCodes(),
                stored.editor(), stored.reviewedAt(), stored.status());
    }

    private static EditorialBrief weekly(boolean vi) {
        return new EditorialBrief(
                t(vi, "Hai tín hiệu đặt chương trình nghị sự tuần này",
                        "Two signals set this week’s Product agenda"),
                t(vi, "Tăng trưởng kinh doanh nhân thọ tại Đài Loan và giám sát phân phối tại Nhật Bản cho Product hai lăng kính khác nhau: nhu cầu có thể tăng nhanh, nhưng năng lực kiểm soát phải theo kịp.",
                        "Life growth in Taiwan and distribution oversight in Japan offer two different Product lenses: demand can accelerate, but control capability has to keep pace."),
                t(vi, "GÓC NHÌN BIÊN TẬP · 7 NGÀY", "EDITOR’S BRIEF · 7 DAYS"),
                t(vi, "Tăng trưởng thu hút sự chú ý; quản trị quyết định khả năng mở rộng.",
                        "Growth attracts attention; governance determines whether it can scale."),
                t(vi, "Fubon Life báo cáo phí năm đầu lũy kế sáu tháng tăng 35% và tổng phí tăng 19% so với cùng kỳ. Cùng tuần, cơ quan giám sát Nhật Bản nhấn mạnh khung quản trị và khảo sát thực trạng đại lý bảo hiểm trong ngành ô tô sau khi luật sửa đổi có hiệu lực. Hai diễn biến không chứng minh một xu hướng chung, nhưng chúng đặt ra một câu hỏi hữu ích: khi tăng trưởng đến từ kênh hoặc đề xuất mới, Product có thể chứng minh rằng quy trình tư vấn, giám sát và xử lý ngoại lệ cũng đã sẵn sàng hay chưa?",
                        "Fubon Life reported six-month first-year premium up 35% and total premium up 19% year on year. In the same week, Japan’s supervisor stressed governance frameworks and a fact-finding survey of auto-industry insurance agents after an amended law took effect. The two events do not prove one market trend, but they frame a useful question: when growth comes through a channel or proposition, can Product demonstrate that advice, oversight and exception handling are ready too?"),
                bridge(vi,
                        "Cửa sổ 7 ngày chưa có diễn biến sản phẩm Việt Nam đủ sâu để tạo một luận điểm trong nước. Đây là khoảng trống nguồn cần xử lý, không phải bằng chứng thị trường đứng yên.",
                        "The seven-day window has no Vietnam product development deep enough to support a domestic thesis. That is a source-coverage gap, not evidence that the market stood still.",
                        "Đài Loan cho thấy tốc độ tăng trưởng; Nhật Bản cho thấy yêu cầu quản trị kênh. Cả hai là điểm đối chiếu quốc tế, không phải chuẩn áp dụng trực tiếp.",
                        "Taiwan shows growth velocity; Japan shows channel-governance expectations. Both are international comparisons, not standards to import directly.",
                        "Dùng tín hiệu quốc tế để kiểm tra bản đồ kiểm soát của đề xuất đang triển khai, đồng thời ưu tiên bổ sung điều khoản và thông báo sản phẩm Việt Nam trong kỳ tới.",
                        "Use the international signals to test the control map for one live proposition, while prioritising Vietnam product terms and notices in the next cycle.",
                        "Năng lực kiểm soát nào phải được chứng minh trước khi một kênh hoặc đề xuất mới được mở rộng tại Việt Nam?",
                        "Which control capability must be proven before a new channel or proposition scales in Vietnam?"),
                List.of(
                        takeaway("01", vi, "Tách tăng trưởng khỏi chất lượng tăng trưởng", "Separate growth from growth quality",
                                "Con số của Fubon Life là tín hiệu mạnh ở một thị trường cụ thể; không nên chuyển thẳng thành chuẩn cho Việt Nam.",
                                "Fubon Life’s figures are a strong signal in one market; they should not be imported as a Vietnam benchmark.",
                                "So sánh cơ cấu sản phẩm và kênh đứng sau tăng trưởng trước khi rút ra bài học.",
                                "Compare the product and channel mix behind the growth before drawing a lesson.", "F-1836,F-1837"),
                        takeaway("02", vi, "Thiết kế kiểm soát cùng lúc với đề xuất", "Design controls with the proposition",
                                "Thông điệp từ Nhật Bản là năng lực quản trị đại lý phải phản ánh mức độ trọng yếu, không chỉ có một mẫu kiểm soát cho mọi kênh.",
                                "Japan’s message is that agent governance should reflect materiality, rather than use one control template for every channel.",
                                "Gắn mỗi thay đổi sản phẩm với bản đồ nghĩa vụ tư vấn, giám sát và bằng chứng vận hành.",
                                "Attach an advice, oversight and operating-evidence map to every product change.", "F-1711,F-1712"),
                        takeaway("03", vi, "Chấp nhận khoảng trống sản phẩm trong tuần", "Accept the week’s product gap",
                                "Cửa sổ bảy ngày không có đủ diễn biến sản phẩm Việt Nam để kể một câu chuyện phong phú mà không suy diễn.",
                                "The seven-day window contains too little Vietnam product activity for a rich story without over-interpreting it.",
                                "Dùng bản tuần để định hướng câu hỏi; dùng bản tháng và quý cho so sánh đề xuất sâu hơn.",
                                "Use the weekly brief to set questions; use monthly and quarterly editions for deeper proposition comparisons.", "F-1707,F-1833")),
                new EditorialChart(t(vi, "Fubon Life tăng trưởng nhanh hơn ở phí năm đầu", "Fubon Life grew faster in first-year premium"),
                        t(vi, "Phí năm đầu · 6T 2026", "First-year premium · H1 2026"), "+35%", 100,
                        t(vi, "Tổng phí · 6T 2026", "Total premium · H1 2026"), "+19%", 54,
                        t(vi, "Tăng trưởng so với cùng kỳ; dữ liệu một doanh nghiệp tại Đài Loan, không phải chuẩn thị trường Việt Nam.",
                                "Year-on-year growth; one Taiwan insurer, not a Vietnam market benchmark."), "F-1836"),
                weeklyExhibits(vi),
                t(vi, "Ba việc Product có thể làm ngay", "Three moves Product can make now"),
                List.of(
                        decision(vi, "48 GIỜ", "48 HOURS", "Lập bảng đối chiếu tăng trưởng", "Build the growth comparison",
                                "Tách sản phẩm, kênh, phí năm đầu, tổng phí và thay đổi vốn trước khi dùng số liệu Fubon trong thảo luận danh mục."),
                        decision(vi, "2 TUẦN", "2 WEEKS", "Kiểm tra bản đồ kiểm soát kênh", "Review channel controls",
                                "Chọn một đề xuất đang triển khai và kiểm tra ai sở hữu tư vấn, giám sát, ngoại lệ và bằng chứng hậu kiểm."),
                        decision(vi, "KỲ TIẾP THEO", "NEXT CYCLE", "Bổ sung nguồn sản phẩm Việt Nam", "Expand Vietnam product coverage",
                                "Ưu tiên điều khoản, thông báo sản phẩm và tài liệu phân phối sơ cấp thay vì kéo dài bản tuần bằng tin ít liên quan.")),
                t(vi, "Theo dõi: cơ cấu sản phẩm tạo tăng trưởng tại Fubon Life; kết quả khảo sát đại lý của FSA Nhật Bản; và bất kỳ thông báo sản phẩm Việt Nam mới nào đủ toàn văn.",
                        "Watch: the product mix behind Fubon Life’s growth; the Japanese FSA’s agent survey findings; and any new full-text Vietnam product notices."),
                boundary(vi), codes("F-1833,F-1836,F-1837,F-1707,F-1711,F-1712"),
                DEFAULT_EDITOR, t(vi, "17/07/2026", "Jul 17, 2026"), "HUMAN_CURATED");
    }

    private static EditorialBrief monthly(boolean vi) {
        return new EditorialBrief(
                t(vi, "Danh mục được tinh gọn trong khi hạ tầng sản phẩm mở rộng",
                        "Portfolios are pruning while product infrastructure expands"),
                t(vi, "AIA Việt Nam ngừng triển khai một nhóm sản phẩm, Income đưa HIVE ra hệ sinh thái embedded finance, còn Swiss Re dự báo tăng trưởng phí toàn cầu giảm tốc. Product cần quản trị đồng thời danh mục, nền tảng và sức ép kinh tế.",
                        "AIA Vietnam is discontinuing a product set, Income is moving HIVE into an embedded-finance ecosystem, and Swiss Re expects global premium growth to slow. Product has to manage portfolio, platform and economics at the same time."),
                t(vi, "GÓC NHÌN BIÊN TẬP · 30 NGÀY", "EDITOR’S BRIEF · 30 DAYS"),
                t(vi, "Thông điệp của tháng không phải “ra mắt thêm”, mà là chọn rõ sản phẩm nào giữ, nền tảng nào xây và năng lực nào phải đi kèm.",
                        "The month’s message is not ‘launch more’; it is to decide what to keep, what platform to build, and which capabilities must travel with it."),
                t(vi, "Ba tín hiệu tạo thành một câu chuyện có ích. Thứ nhất, AIA Việt Nam công bố ngừng triển khai một số sản phẩm qua kênh Đại lý và Đối tác, với việc ngừng nhận hồ sơ mới từ ngày 01/07 đối với danh sách liên quan. Thứ hai, Income chuyển nền tảng HIVE—được phát triển nội bộ trong năm năm—sang một hệ sinh thái có thể triển khai micro-insurance, thuê bao và sản phẩm theo mức sử dụng. Thứ ba, Swiss Re dự báo tăng trưởng phí bảo hiểm thực toàn cầu giảm từ 3,9% năm 2025 xuống 1,3% năm 2026. Kết hợp lại, Product nên ưu tiên kỷ luật danh mục và khả năng tái sử dụng hơn là chỉ tăng số lượng SKU.",
                        "Three signals form a useful story. First, AIA Vietnam announced the discontinuation of selected products through Agency and Partner channels, including a stop to new applications from 1 July for the relevant list. Second, Income is moving HIVE—a platform developed internally over five years—into an ecosystem able to launch micro-insurance, subscription and usage-based products. Third, Swiss Re forecasts real global premium growth slowing from 3.9% in 2025 to 1.3% in 2026. Together, they argue for portfolio discipline and reusable capability rather than simply adding SKUs."),
                bridge(vi,
                        "AIA Việt Nam ngừng triển khai một nhóm sản phẩm qua kênh Đại lý và Đối tác. Đây là diễn biến trong nước có tác động trực tiếp đến quản trị vòng đời, tài liệu bán hàng và chuyển tiếp kênh.",
                        "AIA Vietnam is discontinuing a set of products across Agency and Partner channels. This is a domestic development with direct implications for lifecycle governance, sales materials and channel transition.",
                        "HIVE cho thấy mô hình nền tảng API-first; Swiss Re cho thấy sức ép tăng trưởng toàn cầu. Đây là tín hiệu năng lực và kinh tế từ quốc tế, chưa phải bằng chứng nhu cầu Việt Nam.",
                        "HIVE illustrates an API-first platform model; Swiss Re signals global growth pressure. These are international capability and economics signals, not proof of Vietnam demand.",
                        "Lấy việc ngừng sản phẩm tại Việt Nam làm neo hành động ngay; dùng HIVE và dự báo Swiss Re để kiểm tra liệu danh mục tương lai có ít phức tạp hơn và tái sử dụng hạ tầng tốt hơn hay không.",
                        "Use the Vietnam product exit as the immediate action anchor; use HIVE and Swiss Re to test whether the future portfolio can reduce complexity and reuse infrastructure more effectively.",
                        "Danh mục nào nên giữ, đầu tư hoặc ngừng—và năng lực nền tảng nào phải được dùng lại cho sản phẩm tiếp theo?",
                        "Which products should we keep, invest in or retire—and which platform capability must the next product reuse?"),
                List.of(
                        takeaway("01", vi, "Tinh gọn danh mục là một quyết định sản phẩm", "Portfolio pruning is a Product decision",
                                "Thông báo của AIA Việt Nam bao trùm nhiều sản phẩm và hai kênh; đây là dịp quan sát cách chuyển tiếp danh mục được tổ chức.",
                                "AIA Vietnam’s notice spans multiple products and two channels; it is a live example of portfolio transition.",
                                "Kiểm tra quy trình ngừng bán của chính mình: hồ sơ mới, bán kèm, tài liệu số, đào tạo và truyền thông khách hàng.",
                                "Test our own exit playbook across new business, attachments, digital content, training and customer communication.", "F-1843,F-1844,F-1848,F-1849"),
                        takeaway("02", vi, "HIVE là năng lực, không chỉ là phần mềm", "HIVE is a capability, not just software",
                                "API-first, nhiều mô hình sản phẩm và việc chuyển cả nhân sự cho thấy tài sản thật gồm kiến trúc, tri thức và mô hình vận hành.",
                                "API-first infrastructure, multiple product models and staff transfer show that the asset includes architecture, knowledge and operating model.",
                                "Đánh giá một use case nhỏ theo bốn lớp: sản phẩm, API, đối tác phân phối và quyền sở hữu vận hành.",
                                "Evaluate one narrow use case across product, API, distribution partner and operating ownership.", "F-819,F-820,F-821,F-822,F-823"),
                        takeaway("03", vi, "Tăng trưởng chậm làm lộ chi phí phức tạp", "Slower growth exposes complexity cost",
                                "Khi tăng trưởng phí thực giảm tốc, sản phẩm trùng lặp và quy trình riêng lẻ cho từng SKU trở nên khó bảo vệ hơn.",
                                "When real premium growth slows, overlapping products and bespoke processes for every SKU become harder to defend.",
                                "Đưa chi phí phục vụ, thay đổi và tuân thủ vào quyết định giữ/đầu tư/loại bỏ sản phẩm.",
                                "Include service, change and compliance cost in keep/invest/retire decisions.", "F-717")),
                new EditorialChart(t(vi, "Swiss Re dự báo tăng trưởng phí thực giảm hai phần ba", "Swiss Re forecasts real premium growth falling by two thirds"),
                        "2025", "3.9%", 100, "2026", "1.3%", 33,
                        t(vi, "Tăng trưởng phí bảo hiểm toàn cầu theo giá thực; dự báo, không phải kết quả thực tế.",
                                "Global insurance premium growth in real terms; forecast, not actual result."), "F-717"),
                monthlyExhibits(vi),
                t(vi, "Ba quyết định cho cuộc họp Product tháng này", "Three decisions for this month’s Product meeting"),
                List.of(
                        decision(vi, "TUẦN NÀY", "THIS WEEK", "Mở rà soát danh mục", "Open a portfolio review",
                                "Chấm từng sản phẩm theo vai trò khách hàng, tăng trưởng, biên lợi nhuận, độ phức tạp và khả năng thay thế; xác định ứng viên giữ, đầu tư hoặc ngừng."),
                        decision(vi, "30 NGÀY", "30 DAYS", "Chọn một use case nền tảng", "Choose one platform use case",
                                "Không bắt đầu từ ‘xây HIVE’. Bắt đầu từ một hành trình đối tác cụ thể, sản phẩm hẹp, SLA, dữ liệu và kinh tế đơn vị có thể kiểm thử."),
                        decision(vi, "QUÝ TỚI", "NEXT QUARTER", "Nâng chuẩn năng lực tư vấn", "Raise the advice-capability bar",
                                "Liên kết thay đổi đề xuất với đào tạo, công cụ giải thích, kiểm tra hiểu biết và giám sát chất lượng tư vấn; dùng tín hiệu Prudential như một điểm đối chiếu.")),
                t(vi, "Theo dõi: lịch chuyển tiếp đầy đủ của AIA Việt Nam; use case đầu tiên của HIVE dưới chủ sở hữu mới; bằng chứng thực nghiệm về chi phí và tốc độ ra mắt; và tác động của tăng trưởng phí chậm lên mix sản phẩm nhân thọ.",
                        "Watch: AIA Vietnam’s full transition timetable; HIVE’s first use case under its new owner; empirical evidence on launch speed and cost; and the effect of slower premium growth on life product mix."),
                boundary(vi), codes("F-1843,F-1844,F-1848,F-1849,F-819,F-820,F-821,F-822,F-823,F-717,F-1728,F-1730,F-1820"),
                DEFAULT_EDITOR, t(vi, "17/07/2026", "Jul 17, 2026"), "HUMAN_CURATED");
    }

    private static EditorialBrief quarterly(boolean vi) {
        return new EditorialBrief(
                t(vi, "Sản phẩm đang dịch chuyển từ hợp đồng sang hệ thống dịch vụ",
                        "Products are moving from policies to service systems"),
                t(vi, "Ba mô hình trong 90 ngày—Wealth Flexi, chăm sóc dài hạn tại nhà và HIVE—cho thấy lợi thế không còn nằm ở quyền lợi đơn lẻ. Nó nằm ở cách quyền lợi, dịch vụ, dữ liệu, kênh và vận hành phối hợp.",
                        "Three models in the 90-day window—Wealth Flexi, home-based long-term care and HIVE—show that advantage no longer sits in a single benefit. It sits in how benefits, services, data, channels and operations work together."),
                t(vi, "GÓC NHÌN BIÊN TẬP · 90 NGÀY", "EDITOR’S BRIEF · 90 DAYS"),
                t(vi, "Cơ hội không phải sao chép một sản phẩm; đó là học cách cấu trúc một hệ thống đề xuất có thể vận hành.",
                        "The opportunity is not to copy one product; it is to learn how to structure an operable proposition system."),
                t(vi, "AIA Hong Kong kết hợp hợp đồng tiết kiệm trọn đời với chỉ dẫn quản lý tài sản tương lai, tách hợp đồng và rút tiền theo lịch—biến hoạch định di sản thành một tập hợp cơ chế phục vụ. Great Eastern đặt sản phẩm chăm sóc dài hạn cạnh dữ liệu về chi phí chăm sóc tại nhà, khoảng trống chi trả và gánh nặng người chăm sóc. Income mô tả HIVE như lớp hạ tầng API-first nối hệ thống lõi với kênh phân phối và cho phép nhiều hình thức sản phẩm. Ba ví dụ phục vụ các phân khúc khác nhau, nhưng cùng yêu cầu Product thiết kế đồng thời hợp đồng, dịch vụ, quy trình, dữ liệu và chủ sở hữu vận hành.",
                        "AIA Hong Kong combines a participating whole-life savings policy with future asset instructions, policy splitting and scheduled withdrawals—turning legacy planning into a set of service mechanics. Great Eastern places long-term-care products alongside evidence on home-care cost, payout gaps and caregiver burden. Income describes HIVE as an API-first layer connecting core systems to distribution and enabling multiple product forms. The examples serve different segments, but all require Product to design policy, service, process, data and operating ownership together."),
                bridge(vi,
                        "Neo trong nước là cách AIA Việt Nam thực thi ngừng sản phẩm qua nhiều kênh: một bài kiểm tra thực tế về dữ liệu, tài liệu, đào tạo và trách nhiệm vận hành trong vòng đời sản phẩm.",
                        "The domestic anchor is AIA Vietnam’s multi-channel product retirement: a live test of data, materials, training and operating accountability across the product lifecycle.",
                        "Wealth Flexi tại Hong Kong, khoảng trống chăm sóc tại Singapore và HIVE là ba mô hình quốc tế về cơ chế dịch vụ, nhu cầu và hạ tầng sản phẩm.",
                        "Wealth Flexi in Hong Kong, Singapore’s care gap and HIVE are three international models spanning service mechanics, customer need and product infrastructure.",
                        "Không sao chép quyền lợi. Chọn một vấn đề khách hàng Việt Nam, kiểm tra ranh giới pháp lý và kinh tế, rồi thiết kế thử cơ chế dịch vụ cùng mô hình vận hành có thể đo lường.",
                        "Do not copy the benefit. Select one Vietnam customer problem, test legal and economic boundaries, then prototype measurable service mechanics and an operating model.",
                        "Khái niệm quốc tế nào giải quyết một vấn đề đủ lớn tại Việt Nam và có thể vận hành bằng năng lực hiện có hoặc năng lực đáng đầu tư?",
                        "Which international concept solves a sufficiently important Vietnam problem and can operate through existing—or investable—capabilities?"),
                List.of(
                        takeaway("01", vi, "Thiết kế cơ chế, không chỉ quyền lợi", "Design mechanics, not only benefits",
                                "Wealth Flexi cho thấy giá trị HNW có thể đến từ quyền chỉ dẫn, chia tách và lịch rút tiền quanh hợp đồng chính.",
                                "Wealth Flexi shows that HNW value can come from instruction, splitting and withdrawal mechanics around the core policy.",
                                "Lập bản đồ ‘việc khách hàng cần làm’ trước khi viết thêm quyền lợi hoặc rider.",
                                "Map the customer job before adding another benefit or rider.", "F-1209,F-1210,F-1211,F-1212,F-1213"),
                        takeaway("02", vi, "Dịch vụ phải thu hẹp khoảng trống thực", "Services must close a real gap",
                                "Nghiên cứu của Great Eastern đặt một khoảng trống định lượng giữa ước tính, chi phí chăm sóc thực tế và mức hỗ trợ cơ bản.",
                                "Great Eastern’s study quantifies a gap between consumer estimates, projected home-care cost and the basic payout.",
                                "Thiết kế đề xuất chăm sóc từ ngân sách, điều phối dịch vụ và hỗ trợ người chăm sóc—không chỉ từ số tiền bảo hiểm.",
                                "Design care propositions around budget, service coordination and caregiver support—not only sum assured.", "F-919,F-920,F-921,F-922,F-924"),
                        takeaway("03", vi, "Nền tảng chỉ có giá trị khi use case lặp lại", "Platforms pay off through repeatable use cases",
                                "HIVE kết hợp API, mô hình sản phẩm và tri thức vận hành được tích lũy trong năm năm; đó là một năng lực tổ chức.",
                                "HIVE combines APIs, product models and operating knowledge accumulated over five years; it is an organisational capability.",
                                "Xác định họ sản phẩm và hành trình đối tác có thể dùng chung thành phần trước khi đầu tư nền tảng.",
                                "Define the product family and partner journeys that can reuse components before funding a platform.", "F-819,F-820,F-821,F-822,F-823")),
                new EditorialChart(t(vi, "Người tiêu dùng đánh giá thấp chi phí chăm sóc tại nhà", "Consumers underestimate the cost of home-based care"),
                        t(vi, "Ước tính của người khảo sát / tháng", "Respondent estimate / month"), "S$2.4k", 69,
                        t(vi, "Dự báo Care@Home / tháng", "Care@Home projection / month"), "S$3.5k", 100,
                        t(vi, "Chênh lệch xấp xỉ 32%; dữ liệu Singapore, cần nội địa hóa trước khi dùng cho Việt Nam.",
                                "Approximately 32% gap; Singapore data requiring localisation before Vietnam use."), "F-920"),
                quarterlyExhibits(vi),
                t(vi, "Ba quyết định chiến lược cho quý tới", "Three strategic decisions for the next quarter"),
                List.of(
                        decision(vi, "ĐỀ XUẤT", "PROPOSITION", "Chọn một vấn đề khách hàng để đào sâu", "Choose one customer problem to deepen",
                                "Ưu tiên di sản HNW hoặc khoảng trống chăm sóc dài hạn; xây bản đồ nhu cầu, cơ chế, bằng chứng và ranh giới pháp lý trước khi ideate sản phẩm."),
                        decision(vi, "NỀN TẢNG", "PLATFORM", "Thiết kế kiến trúc tái sử dụng", "Design for reuse",
                                "Tách thành phần dùng chung—định danh, consent, quote, policy service, payment, partner API—khỏi logic riêng của từng sản phẩm."),
                        decision(vi, "QUẢN TRỊ", "GOVERNANCE", "Nâng cấp playbook vòng đời", "Upgrade the lifecycle playbook",
                                "Kết nối ra mắt, thay đổi, đào tạo kênh và ngừng sản phẩm trong một quy trình có chủ sở hữu, mốc hiệu lực và bằng chứng hoàn tất.")),
                t(vi, "Theo dõi: mức sử dụng thực tế của các cơ chế Wealth Flexi; tính bền vững kinh tế của dịch vụ chăm sóc tại nhà; use case thương mại đầu tiên của HIVE; và cách AIA Việt Nam thực thi ngừng sản phẩm qua nhiều kênh.",
                        "Watch: actual use of Wealth Flexi’s service mechanics; the economics of home-care support; HIVE’s first commercial use case; and AIA Vietnam’s execution of a multi-channel product exit."),
                boundary(vi), codes("F-1209,F-1210,F-1211,F-1212,F-1213,F-919,F-920,F-921,F-922,F-924,F-819,F-820,F-821,F-822,F-823,F-1842,F-1843,F-1844,F-1848,F-1811,F-1812,F-1813,F-1805,F-1820"),
                DEFAULT_EDITOR, t(vi, "17/07/2026", "Jul 17, 2026"), "HUMAN_CURATED");
    }

    private static List<EditorialExhibit> weeklyExhibits(boolean vi) {
        return List.of(
                exhibit("01", "BAR", vi,
                        "Tăng trưởng phí năm đầu vượt tổng phí", "First-year premium outpaced total premium",
                        "Khoảng cách 16 điểm phần trăm đặt câu hỏi về cơ cấu sản phẩm và kênh tạo tăng trưởng.",
                        "The 16-point gap raises a product-and-channel mix question behind the growth.",
                        "Tăng trưởng so với cùng kỳ tại Fubon Life; tín hiệu doanh nghiệp Đài Loan, không phải chuẩn Việt Nam.",
                        "Year-on-year growth at Fubon Life; a Taiwan company signal, not a Vietnam benchmark.",
                        "F-1836,F-1837", List.of(
                                datum(vi, "Phí năm đầu · 6T 2026", "First-year premium · H1 2026", "+35%", "+35%", "", "", "", "", 100, "BLUE"),
                                datum(vi, "Tổng phí · 6T 2026", "Total premium · H1 2026", "+19%", "+19%", "", "", "", "", 54, "TEAL"))),
                exhibit("02", "TIMELINE", vi,
                        "Hai tín hiệu, một câu hỏi về khả năng mở rộng", "Two signals, one scalability question",
                        "Tuần này nối tăng trưởng tại Đài Loan với yêu cầu quản trị kênh tại Nhật Bản.",
                        "The week links Taiwan growth with Japan’s channel-governance expectations.",
                        "Trình tự thời gian giúp giữ hai thị trường tách biệt; đây là điểm đối chiếu, không phải một xu hướng chung.",
                        "The timeline keeps the markets distinct; these are comparisons, not one shared trend.",
                        "F-1707,F-1711,F-1712,F-1833,F-1836,F-1837", List.of(
                                datum(vi, "Nhật Bản đặt trọng tâm vào quản trị đại lý", "Japan foregrounds agent governance", "03/07", "3 Jul", "NHẬT BẢN · QUẢN TRỊ", "JAPAN · GOVERNANCE", "FSA nêu khung quản trị và khảo sát thực trạng đại lý bảo hiểm.", "The FSA points to governance frameworks and an agent fact-finding survey.", 50, "GOLD"),
                                datum(vi, "Fubon công bố kết quả tháng 6", "Fubon reports June performance", "15/07", "15 Jul", "ĐÀI LOAN · TĂNG TRƯỞNG", "TAIWAN · GROWTH", "Lợi nhuận và tăng trưởng phí tạo điểm đối chiếu về chất lượng tăng trưởng.", "Profit and premium growth create a comparison point for growth quality.", 100, "TEAL"))),
                exhibit("03", "KPI", vi,
                        "Khoảng trống độ phủ phải được nhìn thấy", "Make the coverage gap visible",
                        "Không có diễn biến Việt Nam đủ điều kiện trong cửa sổ 7 ngày; không dùng tin yếu để lấp chỗ trống.",
                        "No eligible Vietnam development appears in the seven-day window; weak news is not used as filler.",
                        "Các số đếm được suy ra từ chính lớp bằng chứng của kỳ báo cáo, không phải chỉ số thị trường.",
                        "Counts are derived from this edition’s evidence layer; they are not market metrics.",
                        "F-1707,F-1833", List.of(
                                datum(vi, "Diễn biến Việt Nam", "Vietnam developments", "0", "0", "ĐỘ PHỦ", "COVERAGE", "Khoảng trống nguồn cần xử lý", "Source gap to address", 0, "CORAL"),
                                datum(vi, "Tín hiệu quốc tế", "International signals", "2", "2", "ĐỐI CHIẾU", "COMPARISON", "Nhật Bản và Đài Loan", "Japan and Taiwan", 100, "BLUE"),
                                datum(vi, "Nguồn độc lập", "Independent sources", "2", "2", "TRUY XUẤT", "TRACEABILITY", "Mỗi tín hiệu giữ nguồn riêng", "Each signal keeps its own source", 100, "VIOLET"))));
    }

    private static List<EditorialExhibit> monthlyExhibits(boolean vi) {
        return List.of(
                exhibit("01", "BAR", vi,
                        "Tăng trưởng phí thực toàn cầu giảm mạnh", "Real global premium growth resets lower",
                        "Dự báo 2026 thấp hơn 2,6 điểm phần trăm so với 2025, làm chi phí phức tạp danh mục khó biện minh hơn.",
                        "The 2026 forecast is 2.6 points below 2025, making portfolio complexity harder to justify.",
                        "Dự báo tăng trưởng phí bảo hiểm toàn cầu theo giá thực; không phải kết quả thực tế hoặc dự báo riêng Việt Nam.",
                        "Forecast real global insurance premium growth; not an actual result or Vietnam-specific forecast.",
                        "F-717", List.of(
                                datum(vi, "Năm 2025", "2025", "3.9%", "3.9%", "THỰC TẾ/ƯỚC TÍNH", "BASE", "", "", 100, "BLUE"),
                                datum(vi, "Dự báo 2026", "2026 forecast", "1.3%", "1.3%", "DỰ BÁO", "FORECAST", "Giảm 2,6 điểm phần trăm", "Down 2.6 percentage points", 33, "TEAL"))),
                exhibit("02", "TIMELINE", vi,
                        "Danh mục và năng lực kênh cùng dịch chuyển", "Portfolio and channel capability move together",
                        "AIA Việt Nam tinh gọn danh mục trong khi Prudential đầu tư vào chuẩn tư vấn viên.",
                        "AIA Vietnam is pruning its portfolio while Prudential invests in advisor standards.",
                        "Hai diễn biến trong nước có phạm vi khác nhau nhưng cùng tác động đến thực thi vòng đời sản phẩm.",
                        "The two domestic developments differ in scope but both affect product-lifecycle execution.",
                        "F-1843,F-1844,F-1848,F-1728,F-1730", List.of(
                                datum(vi, "AIA Việt Nam thông báo ngừng một số sản phẩm", "AIA Vietnam announces selected product exits", "22/06", "22 Jun", "VIỆT NAM · DANH MỤC", "VIETNAM · PORTFOLIO", "Áp dụng qua kênh Đại lý và Đối tác; cần quản trị chuyển tiếp.", "Agency and Partner channels require a controlled transition.", 45, "CORAL"),
                                datum(vi, "Prudential triển khai chương trình phát triển tư vấn viên", "Prudential launches advisor-development programme", "02/07", "2 Jul", "VIỆT NAM · KÊNH", "VIETNAM · CHANNEL", "Tín hiệu về tiêu chuẩn tư vấn và trải nghiệm khách hàng.", "A signal on advice standards and customer experience.", 100, "TEAL"))),
                exhibit("03", "KPI", vi,
                        "Một lát cắt quy mô thị trường bảo hiểm Việt Nam", "A Vietnam insurance-market pulse",
                        "PVI dẫn đầu phi nhân thọ trong bốn tháng đầu năm; đây là bối cảnh quy mô, không phải bằng chứng nhu cầu sản phẩm nhân thọ.",
                        "PVI led non-life in the first four months; this is scale context, not evidence of life-product demand.",
                        "Số liệu phi nhân thọ lũy kế tháng 1-4/2026 từ cơ quan quản lý Việt Nam.",
                        "Vietnam regulator data for cumulative non-life performance, January-April 2026.",
                        "F-1820", List.of(
                                datum(vi, "Doanh thu phí gốc", "Gross written premium", "6.269 tỷ", "VND 6,269bn", "THÁNG 1-4", "JAN-APR", "PVI", "PVI", 100, "BLUE"),
                                datum(vi, "Thị phần", "Market share", "18,97%", "18.97%", "PHI NHÂN THỌ", "NON-LIFE", "Dẫn đầu thị trường", "Market leader", 95, "GOLD"),
                                datum(vi, "Tăng trưởng", "Year-on-year growth", "+14,54%", "+14.54%", "SO VỚI CÙNG KỲ", "YEAR ON YEAR", "Tín hiệu quy mô, không phải nhu cầu nhân thọ", "Scale signal, not life demand", 73, "TEAL"))),
                exhibit("04", "FLOW", vi,
                        "HIVE minh họa một hệ thống sản phẩm có thể tái sử dụng", "HIVE illustrates a reusable product system",
                        "Giá trị nằm ở luồng từ hệ thống lõi đến thành phần sản phẩm và kênh, không chỉ ở một phần mềm riêng lẻ.",
                        "Value sits in the flow from core systems to reusable product components and channels, not in standalone software.",
                        "Sơ đồ là tổng hợp biên tập từ mô tả công khai; tốc độ, chi phí và kết quả thương mại vẫn cần bằng chứng thực thi.",
                        "The diagram is editorial synthesis of public descriptions; speed, cost and commercial outcomes still need execution evidence.",
                        "F-819,F-820,F-821,F-822,F-823", List.of(
                                datum(vi, "Hệ thống lõi", "Core systems", "01", "01", "NỀN TẢNG", "FOUNDATION", "Dữ liệu hợp đồng và vận hành", "Policy and operating data", 25, "BLUE"),
                                datum(vi, "Lớp HIVE ưu tiên API", "API-first HIVE layer", "02", "02", "KẾT NỐI", "CONNECT", "Kết nối hệ thống lõi với phân phối", "Connects core systems to distribution", 50, "VIOLET"),
                                datum(vi, "Thành phần sản phẩm dùng lại", "Reusable product components", "03", "03", "TÁI SỬ DỤNG", "REUSE", "Mô hình sản phẩm và tri thức vận hành", "Product models and operating knowledge", 75, "TEAL"),
                                datum(vi, "Kênh và mô hình đề xuất", "Channels and proposition forms", "04", "04", "PHÂN PHỐI", "DISTRIBUTE", "Micro-insurance, thuê bao và theo mức sử dụng", "Micro, subscription and usage-based forms", 100, "GOLD"))));
    }

    private static List<EditorialExhibit> quarterlyExhibits(boolean vi) {
        return List.of(
                exhibit("01", "BAR", vi,
                        "Người tiêu dùng đánh giá thấp chi phí chăm sóc tại nhà", "Consumers underestimate home-care cost",
                        "Chênh lệch S$1.1k tương đương khoảng 32% chi phí dự báo, cho thấy quyền lợi tiền mặt có thể chưa đủ.",
                        "The S$1.1k shortfall is about 32% of projected cost, suggesting cash benefits alone may be insufficient.",
                        "Dữ liệu Singapore; phải nội địa hóa chi phí, hành vi và năng lực nhà cung cấp trước khi dùng tại Việt Nam.",
                        "Singapore data; costs, behaviour and provider capacity must be localised before Vietnam use.",
                        "F-920,F-921,F-922,F-924", List.of(
                                datum(vi, "Ước tính của người khảo sát / tháng", "Respondent estimate / month", "S$2.4k", "S$2.4k", "NHẬN THỨC", "PERCEPTION", "", "", 69, "BLUE"),
                                datum(vi, "Dự báo Care@Home / tháng", "Care@Home projection / month", "S$3.5k", "S$3.5k", "CHI PHÍ", "COST", "Khoảng trống S$1.1k", "S$1.1k gap", 100, "TEAL"))),
                exhibit("02", "MATRIX", vi,
                        "Nhịp tăng trưởng tăng trong khi thị phần gần như đi ngang", "Growth accelerated while share stayed broadly flat",
                        "Lát cắt PVI cho thấy doanh thu lũy kế tăng và tốc độ so cùng kỳ cao hơn, nhưng thị phần không mở rộng tương ứng.",
                        "The PVI snapshot shows higher cumulative premium and faster year-on-year growth without a matching share expansion.",
                        "Hai kỳ lũy kế khác độ dài; dùng để theo dõi hướng đi, không so sánh như hai quý độc lập.",
                        "The cumulative periods differ in length; use them directionally, not as two independent quarters.",
                        "F-1805,F-1820", List.of(
                                datum(vi, "Quý I/2026", "Q1 2026", "4.698 tỷ đồng", "VND 4,698bn", "19,18% thị phần", "19.18% share", "+5,99% so cùng kỳ", "+5.99% YoY", 75, "BLUE"),
                                datum(vi, "Tháng 1-4/2026", "Jan-Apr 2026", "6.269 tỷ đồng", "VND 6,269bn", "18,97% thị phần", "18.97% share", "+14,54% so cùng kỳ", "+14.54% YoY", 100, "TEAL"))),
                exhibit("03", "TIMELINE", vi,
                        "Ba mốc cho thấy vòng đời sản phẩm đang vận động", "Three milestones show the product lifecycle in motion",
                        "Quyền lợi thay đổi, thủ tục đại lý được tinh giản và một nhóm sản phẩm ngừng triển khai trong cùng cửa sổ 90 ngày.",
                        "Benefits changed, an agent procedure was removed and a product set exited within the same 90-day window.",
                        "Các mốc có chủ thể và phạm vi khác nhau; timeline thể hiện yêu cầu điều phối vòng đời, không khẳng định quan hệ nhân quả.",
                        "The milestones have different owners and scopes; the timeline shows lifecycle coordination needs, not causality.",
                        "F-1842,F-1811,F-1843,F-1844,F-1848", List.of(
                                datum(vi, "AIA điều chỉnh hạn mức và quyền lợi NCI", "AIA adjusts NCI limits and benefits", "06/05", "6 May", "SẢN PHẨM", "PRODUCT", "Thay đổi áp dụng cho hợp đồng có NCI theo phạm vi công bố.", "Change applies to NCI-linked contracts within the published scope.", 33, "BLUE"),
                                datum(vi, "Bãi bỏ thủ tục chuyển đổi chứng chỉ đại lý", "Agent-certificate conversion procedure abolished", "08/06", "8 Jun", "QUY ĐỊNH", "REGULATION", "Quyết định 1381/QĐ-BTC tinh giản một thủ tục hành chính.", "Decision 1381/QD-BTC removes one administrative procedure.", 66, "GOLD"),
                                datum(vi, "AIA thông báo ngừng một số sản phẩm", "AIA announces selected product exits", "22/06", "22 Jun", "DANH MỤC", "PORTFOLIO", "Hai kênh phân phối cần kế hoạch chuyển tiếp nhất quán.", "Two distribution channels require a consistent transition plan.", 100, "CORAL"))),
                exhibit("04", "MATRIX", vi,
                        "Ba khái niệm quốc tế, ba phép thử khác nhau cho Việt Nam", "Three international concepts, three different Vietnam tests",
                        "Không sao chép quyền lợi; tách vấn đề khách hàng, cơ chế sản phẩm và năng lực vận hành cần kiểm chứng.",
                        "Do not copy benefits; separate customer problem, product mechanics and the operating capability to validate.",
                        "Ma trận là tổng hợp của biên tập viên từ nguồn công khai và phải đi qua nghiên cứu khách hàng, pháp lý và định phí tại Việt Nam.",
                        "The matrix is human synthesis of public sources and still requires Vietnam customer, legal and actuarial validation.",
                        "F-1209,F-1210,F-1211,F-1212,F-1213,F-919,F-920,F-921,F-922,F-924,F-819,F-820,F-821,F-822,F-823", List.of(
                                datum(vi, "Wealth Flexi", "Wealth Flexi", "Chỉ dẫn · chia tách · rút tiền theo lịch", "Instructions · splitting · scheduled withdrawals", "Di sản và chuyển giao tài sản HNW", "HNW legacy and wealth transfer", "Kiểm tra nhu cầu HNW, dịch vụ và phù hợp pháp lý", "Test HNW need, servicing and legal fit", 80, "BLUE"),
                                datum(vi, "Chăm sóc tại nhà", "Home-based care", "Bảo vệ tiền mặt + điều phối dịch vụ", "Cash protection + service coordination", "Chi phí chăm sóc và gánh nặng người chăm sóc", "Care cost and caregiver burden", "Nội địa hóa chi phí, mạng lưới và khả năng chi trả", "Localise costs, network and affordability", 90, "TEAL"),
                                datum(vi, "HIVE", "HIVE", "API + thành phần sản phẩm tái sử dụng", "APIs + reusable product components", "Ra mắt chậm và hành trình đối tác phân mảnh", "Slow launches and fragmented partner journeys", "Chọn một use case có kinh tế đơn vị đo được", "Choose one use case with measurable unit economics", 100, "VIOLET"))),
                exhibit("05", "ROADMAP", vi,
                        "Từ tín hiệu quốc tế đến một thử nghiệm có trách nhiệm", "From international signal to responsible experiment",
                        "Ba cổng trong 90 ngày giữ ý tưởng gắn với vấn đề Việt Nam, kinh tế và khả năng vận hành.",
                        "Three gates over 90 days keep the idea tied to a Vietnam problem, economics and operability.",
                        "Roadmap là khuyến nghị biên tập, không phải cam kết triển khai hoặc phê duyệt sản phẩm.",
                        "The roadmap is an editorial recommendation, not a launch commitment or product approval.",
                        "F-1209,F-919,F-920,F-819,F-820,F-1843", List.of(
                                datum(vi, "Chọn một vấn đề khách hàng", "Choose one customer problem", "30 NGÀY", "30 DAYS", "CỔNG 1", "GATE 1", "Định nghĩa phân khúc, job-to-be-done và bằng chứng nhu cầu Việt Nam.", "Define segment, job-to-be-done and Vietnam demand evidence.", 33, "BLUE"),
                                datum(vi, "Kiểm tra ranh giới", "Test the boundaries", "60 NGÀY", "60 DAYS", "CỔNG 2", "GATE 2", "Đánh giá pháp lý, định phí, dịch vụ, dữ liệu và đối tác.", "Assess legal, actuarial, service, data and partner constraints.", 66, "GOLD"),
                                datum(vi, "Tạo prototype có thể đo", "Build a measurable prototype", "90 NGÀY", "90 DAYS", "CỔNG 3", "GATE 3", "Thử cơ chế sản phẩm và mô hình vận hành với chỉ số dừng/tiếp tục.", "Test product mechanics and operating model with stop/go measures.", 100, "TEAL"))));
    }

    private static EditorialExhibit exhibit(String number, String type, boolean vi,
                                             String viTitle, String enTitle,
                                             String viTakeaway, String enTakeaway,
                                             String viNote, String enNote,
                                             String citations, List<ExhibitDatum> data) {
        return new EditorialExhibit(number, type, true, t(vi, viTitle, enTitle),
                t(vi, viTakeaway, enTakeaway), t(vi, viNote, enNote), citations, data);
    }

    private static ExhibitDatum datum(boolean vi,
                                      String viLabel, String enLabel,
                                      String viValue, String enValue,
                                      String viContext, String enContext,
                                      String viDetail, String enDetail,
                                      int width, String tone) {
        return new ExhibitDatum(t(vi, viLabel, enLabel), t(vi, viValue, enValue),
                t(vi, viContext, enContext), t(vi, viDetail, enDetail), width, tone);
    }

    private static EditorialTakeaway takeaway(String number, boolean vi, String viTitle, String enTitle,
                                               String viBody, String enBody,
                                               String viImplication, String enImplication,
                                               String citations) {
        return new EditorialTakeaway(number, t(vi, viTitle, enTitle), t(vi, viBody, enBody),
                t(vi, viImplication, enImplication), citations);
    }

    private static MarketBridge bridge(boolean vi,
                                       String viDomestic, String enDomestic,
                                       String viInternational, String enInternational,
                                       String viImplication, String enImplication,
                                       String viQuestion, String enQuestion) {
        return new MarketBridge(t(vi, viDomestic, enDomestic),
                t(vi, viInternational, enInternational),
                t(vi, viImplication, enImplication), t(vi, viQuestion, enQuestion));
    }

    private static EditorialDecision decision(boolean vi, String viHorizon, String enHorizon,
                                               String viAction, String enAction, String viRationale) {
        String enRationale = switch (enAction) {
            case "Build the growth comparison" -> "Separate product, channel, first-year premium, total premium and capital movement before using Fubon figures in portfolio discussion.";
            case "Review channel controls" -> "Take one live proposition and identify who owns advice, supervision, exceptions and post-sale evidence.";
            case "Expand Vietnam product coverage" -> "Prioritise primary product terms, notices and distribution documents instead of padding the weekly edition with weakly related news.";
            case "Open a portfolio review" -> "Score each product on customer role, growth, margin, complexity and substitutability; identify keep, invest and retire candidates.";
            case "Choose one platform use case" -> "Do not start with ‘build HIVE’. Start with one partner journey, a narrow product, SLAs, data and testable unit economics.";
            case "Raise the advice-capability bar" -> "Tie proposition changes to training, explanation tools, comprehension checks and advice-quality monitoring; use Prudential’s signal as a comparison point.";
            case "Choose one customer problem to deepen" -> "Prioritise HNW legacy or the long-term-care gap; map needs, mechanics, evidence and legal boundaries before ideating a product.";
            case "Design for reuse" -> "Separate reusable identity, consent, quote, policy service, payment and partner-API components from product-specific logic.";
            case "Upgrade the lifecycle playbook" -> "Connect launch, change, channel training and retirement in one owned process with effective dates and completion evidence.";
            default -> viRationale;
        };
        return new EditorialDecision(t(vi, viHorizon, enHorizon), t(vi, viAction, enAction),
                t(vi, viRationale, enRationale));
    }

    private static String boundary(boolean vi) {
        return t(vi,
                "Phần này là tổng hợp và đánh giá của biên tập viên con người dựa trên các fact code được liệt kê. Nó không thay đổi trạng thái kiểm chứng của dữ kiện, không biến tín hiệu đơn nguồn thành xu hướng và không thay thế thẩm định pháp lý, định phí hoặc nghiên cứu khách hàng.",
                "This section is human editorial synthesis based on the listed fact codes. It does not change evidence-verification state, turn a single-source signal into a trend, or replace legal, actuarial or customer validation.");
    }

    private static List<String> codes(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    private static String key(ProductReportCadence cadence, String language) {
        return cadence.name() + ":" + language;
    }

    private static String value(MultiValueMap<String, String> form, String key, String fallback) {
        String value = form.getFirst(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int width(MultiValueMap<String, String> form, String key, int fallback) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(value(form, key,
                    Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String exhibitType(String requested, String fallback) {
        String normalized = requested == null ? "" : requested.strip().toUpperCase(Locale.ROOT);
        return EXHIBIT_TYPES.contains(normalized) ? normalized : fallback;
    }

    private static String tone(String requested, String fallback) {
        String normalized = requested == null ? "" : requested.strip().toUpperCase(Locale.ROOT);
        return EXHIBIT_TONES.contains(normalized) ? normalized : fallback;
    }

    /** An exhibit may cite only evidence already present in the locked editorial register. */
    private static String safeCitations(String requested, List<String> allowed, String fallback) {
        if (requested == null || requested.isBlank() || allowed == null) return fallback;
        Set<String> allowedSet = Set.copyOf(allowed);
        List<String> safe = Arrays.stream(requested.split("[,·]"))
                .map(String::strip).filter(allowedSet::contains).distinct().toList();
        return safe.isEmpty() ? fallback : String.join(",", safe);
    }

    private static boolean isVi(Locale locale) {
        return locale != null && "vi".equals(locale.getLanguage());
    }

    private static String t(boolean vi, String viText, String enText) { return vi ? viText : enText; }

    public record EditorialBrief(String title, String deck, String leadLabel, String leadHeadline,
                                 String leadNarrative, MarketBridge marketBridge,
                                 List<EditorialTakeaway> takeaways,
                                 EditorialChart chart, List<EditorialExhibit> exhibits,
                                 String numbersHeadline,
                                 List<EditorialDecision> decisions, String watchlist,
                                 String editorialBoundary, List<String> citedFactCodes,
                                 String editor, String reviewedAt, String status) {
        public EditorialBrief {
            exhibits = exhibits == null ? List.of() : List.copyOf(exhibits);
        }
    }

    public record MarketBridge(String domesticRead, String internationalRead,
                               String vietnamImplication, String decisionQuestion) {}

    public record EditorialTakeaway(String number, String title, String body,
                                    String implication, String citationCodes) {}

    public record EditorialChart(String title, String firstLabel, String firstValue, int firstWidth,
                                 String secondLabel, String secondValue, int secondWidth,
                                 String note, String citationCode) {}

    /** Structured, human-editable exhibit rendered consistently in web and PDF. */
    public record EditorialExhibit(String number, String type, boolean enabled,
                                   String title, String takeaway, String note,
                                   String citationCodes, List<ExhibitDatum> data) {
        public EditorialExhibit {
            type = exhibitType(type, "KPI");
            data = data == null ? List.of() : List.copyOf(data);
        }

        public String getTypeLabelEn() {
            return switch (type) {
                case "BAR" -> "Comparison";
                case "KPI" -> "Market pulse";
                case "TIMELINE" -> "Timeline";
                case "FLOW" -> "Capability flow";
                case "MATRIX" -> "Decision matrix";
                case "ROADMAP" -> "Roadmap";
                default -> "Exhibit";
            };
        }

        public String getTypeLabelVi() {
            return switch (type) {
                case "BAR" -> "So sánh";
                case "KPI" -> "Nhịp thị trường";
                case "TIMELINE" -> "Dòng thời gian";
                case "FLOW" -> "Luồng năng lực";
                case "MATRIX" -> "Ma trận quyết định";
                case "ROADMAP" -> "Lộ trình";
                default -> "Biểu đồ";
            };
        }
    }

    public record ExhibitDatum(String label, String value, String context, String detail,
                               int width, String tone) {
        public ExhibitDatum {
            width = Math.max(0, Math.min(100, width));
            tone = ProductReportEditorialService.tone(tone, "BLUE");
        }

        public String getToneClass() { return tone.toLowerCase(Locale.ROOT); }
    }

    public record EditorialDecision(String horizon, String action, String rationale) {}

    public record EditorialReference(String factCode, String title, String url, String publisher,
                                     String marketScope, String geography) {
        public boolean externalLink() {
            return url != null && (url.startsWith("https://") || url.startsWith("http://"));
        }

        public boolean isExternalLink() {
            return externalLink();
        }
    }
}
