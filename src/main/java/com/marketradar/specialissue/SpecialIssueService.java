package com.marketradar.specialissue;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Curated editorial layer, intentionally separate from the rolling 7/30/90-day Radar.
 * A real monthly issue starts with a selected research pack; this MVP seeds one fully cited,
 * publisher-approved learning issue and exposes the commissioning decision in Topic Lab.
 */
@Service
public class SpecialIssueService {

    private final Map<String, String> commissioned = new ConcurrentHashMap<>();

    public SpecialIssueService() {
        commissioned.put("wellness-linked-life", "PUBLISHED");
    }

    public List<TopicCandidate> candidates() {
        return List.of(
                new TopicCandidate("wellness-linked-life", "Wellness-linked life insurance",
                        "Protection + engagement + defined rewards", 91, 7, 3, 3, "READY",
                        "A cited Vietnam product example and a complete learning dossier are ready."),
                new TopicCandidate("cancer-prevention-benefits", "Cancer prevention and early detection",
                        "From reimbursement to prevention pathways", 77, 5, 2, 2, "RESEARCH_PACK_REQUIRED",
                        "Promising concept; add Vietnam product terms and one independent outcomes source."),
                new TopicCandidate("longevity-income", "Longevity and retirement income",
                        "Protection for a longer life", 74, 4, 2, 2, "RESEARCH_PACK_REQUIRED",
                        "Needs a primary Vietnam regulatory/product anchor before commissioning."),
                new TopicCandidate("embedded-life", "Embedded life protection",
                        "Protection inside partner journeys", 68, 3, 2, 1, "RESEARCH_PACK_REQUIRED",
                        "Needs a local distribution case and a curated source pack.")
        );
    }

    public TopicCandidate candidate(String slug) {
        return candidates().stream().filter(item -> item.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new UnknownIssueException(slug));
    }

    public void commission(String slug) {
        TopicCandidate candidate = candidate(slug);
        if (!"READY".equals(candidate.readiness())) {
            throw new IssueNotReadyException("This topic cannot be commissioned yet: " + candidate.nextStep());
        }
        commissioned.put(slug, "PUBLISHED");
    }

    public Issue issue(String slug, Locale locale) {
        if (!commissioned.containsKey(slug)) throw new UnknownIssueException(slug);
        if (!"wellness-linked-life".equals(slug)) throw new UnknownIssueException(slug);
        return wellnessIssue(locale);
    }

    public Issue wellnessIssue(Locale locale) {
        boolean vi = "vi".equals(locale.getLanguage());
        return new Issue(
                "wellness-linked-life",
                vi ? "Sản phẩm Chuyên đề" : "Product Academy",
                vi ? "Bảo hiểm nhân thọ gắn với chăm sóc sức khỏe" : "Wellness-linked life insurance",
                vi ? "Cách bảo vệ, gắn kết khách hàng và phần thưởng vận hành như một hệ thống sản phẩm"
                        : "How protection, engagement and rewards work as one product system",
                vi ? "Tháng 7 năm 2026" : "July 2026",
                vi ? "Bản học thuật có dẫn nguồn công khai" : "A cited learning issue built from public evidence",
                7, 3, 3,
                List.of(
                        new Section("01", vi ? "Khái niệm" : "The concept",
                                vi ? "Bảo hiểm gắn với chăm sóc sức khỏe kết hợp hợp đồng bảo hiểm với một chương trình liên tục khuyến khích hành vi lành mạnh và trao phần thưởng hoặc dịch vụ theo điều kiện đã công bố."
                                        : "Wellness-linked life insurance combines an insurance contract with an ongoing programme that encourages healthy behaviour and gives a defined reward or service when customers engage.",
                                List.of(
                                        vi ? "Đây không chỉ là ứng dụng sức khỏe. Giá trị bảo hiểm, chương trình chăm sóc sức khỏe và quy tắc liên kết giữa hai phần đều phải được thiết kế rõ ràng."
                                                : "It is not simply a wellness app. The insurance promise, programme and linkage rule must each be designed explicitly.",
                                        vi ? "AIA Việt Nam công bố ví dụ địa phương: AIA – Khỏe An Nhiên có quyền lợi thưởng sống khỏe Vitality, tối đa 60% trung bình phí đã đóng trong ba năm trước đó, theo điều kiện sản phẩm."
                                                : "AIA Vietnam provides a local public example: AIA – Khỏe An Nhiên describes a Vitality healthy-living reward of up to 60% of average premiums paid in the preceding three years, subject to product conditions."
                                ), List.of()),
                        new Section("02", vi ? "Cách vận hành cho khách hàng" : "How it works for a customer",
                                vi ? "Hành trình có bảy bước, từ chọn sản phẩm đến chi trả phần thưởng."
                                        : "The customer journey has seven steps, from product choice to the stated reward.",
                                List.of(), List.of(
                                        vi ? "Chọn sản phẩm đủ điều kiện và đăng ký chương trình" : "Choose a qualifying product and opt into the programme",
                                        vi ? "Đồng ý về dữ liệu, kích hoạt tư cách thành viên" : "Give informed consent and activate membership",
                                        vi ? "Hoàn thành hoạt động đủ điều kiện qua ứng dụng/đối tác" : "Complete eligible activities through an app or partner",
                                        vi ? "Xác thực hoạt động → tính điểm/hạng → hiển thị tiến độ" : "Validate activity → calculate points/status → show progress",
                                        vi ? "Đến ngày đánh giá, áp dụng điều kiện và thanh toán phần thưởng" : "At the assessment date, apply conditions and pay the stated reward"
                                )),
                        new Section("03", vi ? "Các thành phần của sản phẩm" : "The product components",
                                vi ? "Mỗi thành phần cần một chủ sở hữu và quy tắc rõ ràng." : "Every component needs a clear rule and accountable owner.",
                                List.of(), List.of(
                                        vi ? "Hợp đồng nền: quyền lợi, giới hạn, phí, thời hạn, loại trừ và quy trình bồi thường."
                                                : "Base policy: benefits, limits, premium, term, exclusions and claims process.",
                                        vi ? "Điều kiện tham gia: sản phẩm đủ điều kiện, opt-in/opt-out, địa lý và trạng thái thành viên."
                                                : "Eligibility: qualifying products, opt-in/out, geography and membership status.",
                                        vi ? "Hoạt động, điểm, hạng và phần thưởng: cách xác thực, ngưỡng, giới hạn và ngày đánh giá."
                                                : "Activities, points, status and reward: validation, thresholds, caps and assessment date.",
                                        vi ? "Kỹ thuật, dữ liệu và đồng ý: ứng dụng, đối tác, quyền truy cập, lưu giữ dữ liệu và hỗ trợ."
                                                : "Technology, data and consent: app, partners, access, retention and service support."
                                )),
                        new Section("04", vi ? "Ví dụ về quy tắc sản phẩm công khai" : "Worked public product mechanics",
                                vi ? "Điều khoản Bùng Gia Lực của AIA Việt Nam cho thấy phần thưởng có thể là một cơ chế theo quy tắc, không phải quà tặng tùy ý."
                                        : "AIA Vietnam’s Bùng Gia Lực terms show that a reward can be a rule-based mechanism, not a discretionary gift.",
                                List.of(
                                        vi ? "Phần thưởng được xét vào kỷ niệm hợp đồng thứ ba và mỗi ba năm sau đó; hợp đồng, tư cách Vitality và việc đóng phí phải còn hiệu lực."
                                                : "The reward is assessed at the third contract anniversary and every three years thereafter; cover, Vitality membership and premium payment must remain in force.",
                                        vi ? "Công thức dùng tỷ lệ thưởng nhân với trung bình phí đã đóng trong ba năm trước đó; tỷ lệ thay đổi theo hạng thành viên và có trần 60%."
                                                : "The formula uses a reward rate × average premiums paid in the preceding three years; the rate changes with membership status and is capped at 60%.",
                                        vi ? "Quy tắc này không tự động thay đổi quyền lợi bảo hiểm cốt lõi."
                                                : "The reward rule does not automatically change the core insurance benefits."
                                ), List.of()),
                        new Section("05", vi ? "Mô hình vận hành" : "Operating model",
                                vi ? "Đây là hệ thống sản phẩm liên phòng ban, không phải tính năng của Marketing."
                                        : "This is a cross-functional product system, not a Marketing feature.",
                                List.of(), List.of(
                                        vi ? "Sản phẩm/Định phí: thiết kế quyền lợi, trần, nguồn kinh phí, điều khoản và theo dõi."
                                                : "Product/Actuarial: benefit design, caps, funding, terms and monitoring.",
                                        vi ? "Công nghệ: hành trình ứng dụng, danh tính, tích hợp, chất lượng dữ liệu, sự cố."
                                                : "Technology: app journey, identity, integrations, data quality and incidents.",
                                        vi ? "Vận hành: đăng ký, tranh chấp hạng, hiệu chỉnh phần thưởng, khiếu nại."
                                                : "Operations: enrolment, status disputes, reward corrections and complaints.",
                                        vi ? "Tuân thủ/Quyền riêng tư: đồng ý, công bằng, nhà cung cấp, bảo mật và phê duyệt."
                                                : "Compliance/Privacy: consent, fairness, vendors, security and approval.",
                                        vi ? "Thẩm định/Bồi thường: giữ ranh giới dữ liệu đã được phê duyệt."
                                                : "Underwriting/Claims: maintain the approved data boundary."
                                )),
                        new Section("06", vi ? "Lợi ích, giới hạn và bằng chứng" : "Benefits, limits and evidence",
                                vi ? "Lợi ích là khả năng gắn kết thường xuyên và giá trị dịch vụ. Nhưng mức độ tham gia không tự chứng minh cải thiện tử vong, chi phí bồi thường hay lợi nhuận."
                                        : "The opportunity is frequent engagement and service value. But engagement alone does not prove improved mortality, claims cost or profitability.",
                                List.of(
                                        vi ? "Cần tách loại trừ bảo hiểm thông thường khỏi điều kiện nhận thưởng: thời gian chờ, giới hạn quyền lợi và bồi thường vẫn thuộc hợp đồng bảo hiểm."
                                                : "Separate normal insurance exclusions from reward eligibility: waiting periods, benefit limits and claims rules remain insurance-contract terms.",
                                        vi ? "Bất kỳ thay đổi về phí, quyền lợi hoặc thẩm định đều cần kiểm định định phí, pháp lý, tuân thủ và quyền riêng tư."
                                                : "Any linkage to premium, cover or underwriting requires actuarial, legal, compliance and privacy validation."
                                ), List.of()),
                        new Section("07", vi ? "Bước tiếp theo an toàn" : "A prudent next step",
                                vi ? "Nên bắt đầu bằng pilot dịch vụ và gắn kết, chưa phải giảm phí hay thay đổi thẩm định."
                                        : "Start with a service-and-engagement pilot—not a premium discount or underwriting change.",
                                List.of(), List.of(
                                        vi ? "Chọn một nhóm khách hàng và một hoạt động dễ tiếp cận." : "Choose one customer group and one accessible activity.",
                                        vi ? "Tham gia tự nguyện; giải thích dữ liệu bằng ngôn ngữ rõ ràng." : "Keep participation voluntary; explain data in plain language.",
                                        vi ? "Phần thưởng cố định, có trần; đo tham gia, khiếu nại, chi phí và mức hiểu của khách hàng." : "Use a fixed, capped reward; measure engagement, complaints, cost and customer understanding.",
                                        vi ? "Chỉ xem xét liên kết mạnh hơn sau khi có bằng chứng." : "Consider stronger linkage only after evidence is available."
                                ))
                ),
                sources(locale)
        );
    }

    private static List<SourceNote> sources(Locale locale) {
        return List.of(
                new SourceNote("AIA Vietnam", vi(locale, "AIA – Khỏe An Nhiên", "AIA – Khỏe An Nhiên"),
                        "https://www.aia.com.vn/vi/san-pham/bao-ve-cuoc-song/aia-khoe-an-nhien.html", "Primary product page"),
                new SourceNote("AIA Vietnam", vi(locale, "Quy tắc và điều khoản Bùng Gia Lực", "Bùng Gia Lực product terms"),
                        "https://www.aia.com.vn/content/dam/vn-wise/san-pham/suc-khoe/benh-hiem-ngheo/tron-tam-an/021025-Quy-tac-va-dieu-khoan-Bao-hiem-Suc-khoe-Bung-Gia-Luc.pdf", "Primary product terms"),
                new SourceNote("AIA Vietnam", vi(locale, "Câu hỏi thường gặp AIA Vitality", "AIA Vitality FAQ"),
                        "https://www.aia.com.vn/vi/song-khoe/song-khoe-cung-aia/aia-vitality/cau-hoi-thuong-gap.html", "Primary programme page"),
                new SourceNote("Swiss Re", vi(locale, "Tác động của chương trình sức khỏe số lên tử vong", "Digital health programme impact on mortality"),
                        "https://www.swissre.com/reinsurance/insights/digital-health-programme-impact-on-mortality.html", "Independent reinsurer analysis"),
                new SourceNote("Manulife / John Hancock", vi(locale, "John Hancock Aspire", "John Hancock Aspire"),
                        "https://www.manulife.com/en/news/john-hancock-verily-onduo-launch-aspire-life-insurance-for-americans-with-diabetes.html", "International product example")
        );
    }

    private static String vi(Locale locale, String vi, String en) { return "vi".equals(locale.getLanguage()) ? vi : en; }

    public record TopicCandidate(String slug, String title, String deck, int score, int evidenceDocuments,
                                 int independentPublishers, int primarySources, String readiness, String nextStep) {}

    public record Issue(String slug, String series, String title, String deck, String issueMonth,
                        String provenance, int evidenceDocuments, int independentPublishers,
                        int primarySources, List<Section> sections, List<SourceNote> sources) {}

    public record Section(String number, String title, String lead, List<String> paragraphs, List<String> bullets) {}
    public record SourceNote(String publisher, String title, String url, String type) {}

    public static final class UnknownIssueException extends IllegalArgumentException {
        public UnknownIssueException(String slug) { super("Special Issue not found: " + slug); }
    }
    public static final class IssueNotReadyException extends IllegalStateException {
        public IssueNotReadyException(String message) { super(message); }
    }
}
