package com.marketradar.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.product.ProductReportCadence;
import com.marketradar.product.ProductReportEditorialDraft;
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
            if (stored != null) return stored;
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
            result.put(documentId, existing == null
                    ? new EditorialReference(code, fact.getRawDoc().getTitle(),
                    fact.getRawDoc().getUrl(), fact.getRawDoc().getSource().getName())
                    : new EditorialReference(existing.factCode() + " · " + code,
                    existing.title(), existing.url(), existing.publisher()));
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
        EditorialBrief revised = new EditorialBrief(
                value(form, "title", current.title()),
                value(form, "deck", current.deck()),
                value(form, "leadLabel", current.leadLabel()),
                value(form, "leadHeadline", current.leadHeadline()),
                value(form, "leadNarrative", current.leadNarrative()),
                List.copyOf(takeaways), current.chart(),
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
                boundary(vi), codes("F-1843,F-1844,F-1848,F-1849,F-819,F-820,F-821,F-822,F-823,F-717,F-1728,F-1730"),
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
                boundary(vi), codes("F-1209,F-1210,F-1211,F-1212,F-1213,F-919,F-920,F-921,F-922,F-924,F-819,F-820,F-821,F-822,F-823,F-1843,F-1844,F-1848,F-1811,F-1812,F-1813"),
                DEFAULT_EDITOR, t(vi, "17/07/2026", "Jul 17, 2026"), "HUMAN_CURATED");
    }

    private static EditorialTakeaway takeaway(String number, boolean vi, String viTitle, String enTitle,
                                               String viBody, String enBody,
                                               String viImplication, String enImplication,
                                               String citations) {
        return new EditorialTakeaway(number, t(vi, viTitle, enTitle), t(vi, viBody, enBody),
                t(vi, viImplication, enImplication), citations);
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

    private static boolean isVi(Locale locale) {
        return locale != null && "vi".equals(locale.getLanguage());
    }

    private static String t(boolean vi, String viText, String enText) { return vi ? viText : enText; }

    public record EditorialBrief(String title, String deck, String leadLabel, String leadHeadline,
                                 String leadNarrative, List<EditorialTakeaway> takeaways,
                                 EditorialChart chart, String numbersHeadline,
                                 List<EditorialDecision> decisions, String watchlist,
                                 String editorialBoundary, List<String> citedFactCodes,
                                 String editor, String reviewedAt, String status) {}

    public record EditorialTakeaway(String number, String title, String body,
                                    String implication, String citationCodes) {}

    public record EditorialChart(String title, String firstLabel, String firstValue, int firstWidth,
                                 String secondLabel, String secondValue, int secondWidth,
                                 String note, String citationCode) {}

    public record EditorialDecision(String horizon, String action, String rationale) {}

    public record EditorialReference(String factCode, String title, String url, String publisher) {
        public boolean externalLink() {
            return url != null && (url.startsWith("https://") || url.startsWith("http://"));
        }

        public boolean isExternalLink() {
            return externalLink();
        }
    }
}
