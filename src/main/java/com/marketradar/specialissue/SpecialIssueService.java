package com.marketradar.specialissue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.repo.SpecialIssueDraftRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Curated, evidence-first Product Academy issues, separate from the rolling Radar. */
@Service
public class SpecialIssueService {

    private final Map<String, String> commissioned = new ConcurrentHashMap<>();
    private final Map<String, Issue> transientDrafts = new ConcurrentHashMap<>();
    private SpecialIssueDraftRepository drafts;
    private ObjectMapper json;

    public SpecialIssueService() { commissioned.put("wellness-linked-life", "PUBLISHED"); }

    @Autowired
    public SpecialIssueService(SpecialIssueDraftRepository drafts, ObjectMapper json) {
        this();
        this.drafts = drafts;
        this.json = json;
    }

    public List<TopicCandidate> candidates() { return candidates(Locale.ENGLISH); }

    public List<TopicCandidate> candidates(Locale locale) {
        boolean vi = isVi(locale);
        return List.of(
                new TopicCandidate("wellness-linked-life",
                        t(vi, "Bảo hiểm nhân thọ gắn với chăm sóc sức khỏe", "Wellness-linked life insurance"),
                        t(vi, "Bảo vệ + gắn kết + phần thưởng theo quy tắc", "Protection + engagement + rule-based rewards"),
                        91, 7, 4, 3, "READY",
                        t(vi, "Bộ hồ sơ có điều khoản sản phẩm Việt Nam, ví dụ quốc tế và nguồn tái bảo hiểm độc lập.",
                                "The pack includes Vietnam product terms, international examples and independent reinsurance analysis.")),
                new TopicCandidate("cancer-prevention-benefits",
                        t(vi, "Phòng ngừa và phát hiện sớm ung thư", "Cancer prevention and early detection"),
                        t(vi, "Từ chi trả điều trị đến hành trình phòng ngừa", "From reimbursement to prevention pathways"),
                        77, 5, 2, 2, "RESEARCH_PACK_REQUIRED",
                        t(vi, "Cần bổ sung điều khoản sản phẩm tại Việt Nam và một nghiên cứu kết quả độc lập.",
                                "Add Vietnam product terms and one independent outcomes study.")),
                new TopicCandidate("longevity-income",
                        t(vi, "Thu nhập hưu trí và rủi ro sống thọ", "Longevity and retirement income"),
                        t(vi, "Bảo vệ tài chính cho một cuộc đời dài hơn", "Financial protection for a longer life"),
                        74, 4, 2, 2, "RESEARCH_PACK_REQUIRED",
                        t(vi, "Cần một căn cứ pháp lý hoặc sản phẩm sơ cấp tại Việt Nam.",
                                "Add a primary Vietnam regulatory or product anchor.")),
                new TopicCandidate("embedded-life",
                        t(vi, "Bảo hiểm nhân thọ tích hợp", "Embedded life protection"),
                        t(vi, "Bảo vệ ngay trong hành trình của đối tác", "Protection inside partner journeys"),
                        68, 3, 2, 1, "RESEARCH_PACK_REQUIRED",
                        t(vi, "Cần tình huống phân phối trong nước và bộ nguồn được tuyển chọn.",
                                "Add a local distribution case and a curated source pack."))
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
        if (!commissioned.containsKey(slug) || !"wellness-linked-life".equals(slug)) {
            throw new UnknownIssueException(slug);
        }
        return wellnessIssue(locale);
    }

    public Issue wellnessIssue(Locale locale) {
        Issue persisted = edited("wellness-linked-life", locale);
        if (persisted != null) return persisted;
        return defaultWellnessIssue(locale);
    }

    private Issue defaultWellnessIssue(Locale locale) {
        boolean vi = isVi(locale);
        Labels labels = new Labels(
                t(vi, "Đọc chuyên đề", "Read issue"), t(vi, "Tải PDF", "Download PDF"),
                t(vi, "Mục lục", "Contents"), t(vi, "Tóm tắt điều hành", "Executive summary"),
                t(vi, "Tài liệu được trích dẫn", "Sources cited"),
                t(vi, "nhà xuất bản độc lập", "independent publishers"),
                t(vi, "nguồn sơ cấp", "primary sources"), t(vi, "CHƯƠNG", "CHAPTER"),
                t(vi, "Nguồn", "Source"), t(vi, "Hàm ý cho Product", "Implication for Product"),
                t(vi, "Bằng chứng công khai; không phải tư vấn y tế, pháp lý hoặc quyết định ra mắt sản phẩm.",
                        "Public evidence; not medical, legal or product-launch advice."));

        List<Section> sections = List.of(
                new Section("01", t(vi, "Định nghĩa đúng khái niệm", "Define the concept correctly"),
                        t(vi, "Đây là một hệ thống sản phẩm ba lớp - không phải hợp đồng truyền thống được gắn thêm ứng dụng sức khỏe.",
                                "This is a three-layer product system - not a traditional policy with a wellness app attached."),
                        List.of(
                                t(vi, "Lớp hợp đồng xác lập lời hứa bảo hiểm: sự kiện được bảo hiểm, số tiền chi trả, phí, thời hạn, thời gian chờ và loại trừ. Lớp chương trình tạo trải nghiệm thường xuyên: hoạt động đủ điều kiện, điểm, hạng, nội dung, dịch vụ và đối tác. Lớp liên kết chuyển một trạng thái chương trình thành giá trị kinh tế bằng ngày đánh giá, công thức, điều kiện và trần thưởng.",
                                        "The policy layer defines the insurance promise: covered events, payment, premium, term, waiting periods and exclusions. The programme layer creates the recurring experience: eligible activities, points, status, content, services and partners. The linkage layer converts a programme state into economic value through an assessment date, formula, conditions and cap."),
                                t(vi, "Khách hàng cần biết mình đang tương tác với lớp nào. Quyền lợi bảo hiểm không nên biến động theo số bước chân nếu hợp đồng không nói như vậy; ngược lại, phần thưởng chương trình không nên được trình bày như quyền lợi bảo đảm. Kiến trúc thông tin này phải nhất quán trong điều khoản, minh họa bán hàng, ứng dụng và kịch bản chăm sóc khách hàng.",
                                        "Customers must know which layer they are interacting with. An insured benefit should not vary with step count unless the policy says so; a programme reward should not be presented as a guaranteed benefit. This information architecture must remain consistent across terms, sales illustration, app and service scripts."),
                                t(vi, "Cách kiểm tra nhanh một ý tưởng là viết ba bản mô tả riêng: hợp đồng cam kết điều gì, chương trình ghi nhận điều gì, và chính xác quy tắc nào nối hai phần. Nếu một thay đổi không thể được đặt rõ vào một lớp, thiết kế chưa đủ chín để triển khai.",
                                        "A useful test is to write three separate descriptions: what the policy promises, what the programme records, and the exact rule connecting them. If a change cannot be placed cleanly in one layer, the design is not mature enough to implement.")),
                        List.of(t(vi, "Hợp đồng = lời hứa chi trả có tính pháp lý.", "Policy = legally defined payment promise."),
                                t(vi, "Chương trình = trải nghiệm, dịch vụ và trạng thái thành viên.", "Programme = experience, services and membership state."),
                                t(vi, "Quy tắc liên kết = công thức chuyển trạng thái thành giá trị.", "Linkage rule = formula converting state into value."))),
                new Section("02", t(vi, "Cơ chế vận hành đối với khách hàng", "How the customer mechanics work"),
                        t(vi, "Giá trị được tạo ra qua một vòng lặp có thể quan sát, giải thích và khiếu nại.",
                                "Value is created through a loop that can be observed, explained and challenged."),
                        List.of(
                                t(vi, "Hành trình chuẩn gồm năm bước: xác định khách hàng và sản phẩm đủ điều kiện; ghi nhận đồng ý theo từng mục đích; tiếp nhận hoạt động từ thiết bị, khai báo hoặc đối tác; xác thực và chuyển hoạt động thành điểm/hạng; cuối cùng đánh giá phần thưởng tại ngày quy định. Mỗi bước cần trạng thái, bằng chứng đầu vào, chủ sở hữu và đường xử lý ngoại lệ.",
                                        "The canonical journey has five steps: establish customer and product eligibility; capture purpose-specific consent; receive activities from devices, declarations or partners; validate and convert activity into points/status; then assess the reward on a defined date. Every step needs a status, input evidence, owner and exception path."),
                                t(vi, "Trải nghiệm không kết thúc ở màn hình ghi điểm. Khách hàng cần thấy hoạt động nào đã được nhận, hoạt động nào bị từ chối, hạng hiện tại, điều kiện còn thiếu và ước tính tác động tới phần thưởng. Khi dữ liệu đến muộn hoặc thiết bị lỗi, hệ thống phải có thời hạn điều chỉnh và hồ sơ quyết định.",
                                        "The experience does not end at a points screen. Customers need to see which activity was received, which was rejected, current status, missing conditions and an estimate of reward impact. When data arrives late or a device fails, the system needs a correction window and decision record.")),
                        List.of(t(vi, "Đăng ký chương trình và đồng ý sử dụng dữ liệu là hai quyết định riêng, có thể rút lại.", "Programme enrolment and data consent are separate, revocable decisions."),
                                t(vi, "Quy tắc xác thực phải nêu rõ nguồn dữ liệu, độ trễ, trùng lặp và cách sửa sai.", "Validation rules must define data source, latency, duplicates and correction."),
                                t(vi, "Điểm/hạng phải có sổ cái giao dịch để Operations giải thích từng thay đổi.", "Points/status need a transaction ledger so Operations can explain every change."),
                                t(vi, "Ngày đánh giá cần thông báo trước và không phụ thuộc vào thao tác thủ công không kiểm soát.", "Assessment dates need advance notice and must not depend on uncontrolled manual action."))),
                new Section("03", t(vi, "Kinh tế học của phần thưởng", "The economics of the reward"),
                        t(vi, "Phần thưởng là nghĩa vụ theo công thức, cần được mô hình hóa như một cấu phần sản phẩm.",
                                "The reward is a formula-governed obligation that must be modelled as a product component."),
                        List.of(
                                t(vi, "Điều khoản Bùng Gia Lực công khai của AIA Việt Nam mô tả việc xét thưởng tại kỷ niệm hợp đồng thứ ba và mỗi ba năm sau đó. Công thức sử dụng tỷ lệ thưởng nhân với phí bình quân đã đóng trong ba năm trước, với tỷ lệ tối đa được nêu là 60%, tùy điều kiện sản phẩm và hạng thành viên. Đây là ví dụ cụ thể cho thấy hành vi, trạng thái hợp đồng và dòng phí hội tụ tại một ngày đánh giá.",
                                        "AIA Vietnam's public Bùng Gia Lực terms describe assessment at the third policy anniversary and every three years thereafter. The formula applies a reward rate to average premiums paid over the preceding three years, with a stated maximum rate of 60%, subject to product and membership conditions. It is a concrete example of behaviour, policy status and premium flow converging on one assessment date."),
                                t(vi, "Mô hình kinh tế cần tách bốn biến: chi phí phần thưởng dự kiến, chi phí vận hành chương trình, giá trị duy trì hợp đồng và mọi thay đổi rủi ro bảo hiểm. Chỉ số tương tác có thể tăng trong khi kinh tế tổng thể xấu đi nếu phần thưởng quá rộng, đối tác đắt hoặc quy trình khiếu nại phức tạp.",
                                        "The economic model should separate four variables: expected reward cost, programme operating cost, persistence value and any change in insured risk. Engagement can rise while total economics deteriorate if rewards are too broad, partners are expensive or disputes are costly."),
                                t(vi, "Product nên chạy kịch bản cơ sở, tốt và xấu theo phân khúc, với tỷ lệ tham gia, mức đạt hạng, trần thưởng, tỷ lệ duy trì và chi phí phục vụ. Không sử dụng tương quan hành vi-sức khỏe như quan hệ nhân quả cho định phí khi chưa có kiểm định định phí và pháp lý riêng.",
                                        "Product should run base, upside and downside scenarios by segment using participation, status attainment, reward cap, persistence and cost-to-serve. Behaviour-health correlations must not be treated as causal pricing evidence without separate actuarial and legal validation.")),
                        List.of(t(vi, "Tách chi phí phần thưởng khỏi chi phí nền tảng và đối tác.", "Separate reward cost from platform and partner cost."),
                                t(vi, "Mô hình hóa nghĩa vụ tại từng ngày đánh giá, không chỉ mức trung bình năm.", "Model liability at each assessment date, not only annual averages."),
                                t(vi, "Đặt trần và ngưỡng dừng trước khi pilot.", "Set caps and stop thresholds before the pilot."))),
                new Section("04", t(vi, "Mô hình vận hành liên phòng ban", "The cross-functional operating model"),
                        t(vi, "Quyền sở hữu phải đi theo quyết định, dữ liệu và sự cố - không chỉ theo sơ đồ tổ chức.",
                                "Ownership must follow decisions, data and incidents - not merely the organisation chart."),
                        List.of(
                                t(vi, "Sản phẩm và Định phí sở hữu kiến trúc ba lớp, công thức, trần và giả định kinh tế. Công nghệ và Dữ liệu sở hữu danh tính, tích hợp, sổ cái điểm và khả năng truy vết. Vận hành sở hữu đăng ký, điều chỉnh, chi thưởng và khiếu nại. Tuân thủ và Quyền riêng tư đặt ranh giới đồng ý, nhà cung cấp và mục đích sử dụng. Thẩm định và Bồi thường chỉ tiếp nhận dữ liệu chương trình trong phạm vi được phê duyệt rõ ràng.",
                                        "Product and Actuarial own the three-layer architecture, formula, cap and economics. Technology and Data own identity, integrations, the points ledger and traceability. Operations owns enrolment, correction, fulfilment and complaints. Compliance and Privacy set consent, vendor and permitted-use boundaries. Underwriting and Claims receive programme data only within an explicitly approved scope."),
                                t(vi, "Một mô hình vận hành tốt cần ba diễn đàn: hội đồng thiết kế quyết định thay đổi cơ chế; phòng điều hành theo dõi lỗi, đối soát và SLA; hội đồng rủi ro dữ liệu xem xét mục đích mới, thiên lệch và sự cố. Mọi thay đổi ảnh hưởng công thức hoặc điều kiện phải đi qua kiểm soát phiên bản và thông báo khách hàng.",
                                        "A workable operating model needs three forums: a design authority for mechanics changes; an operations room for errors, reconciliation and SLAs; and a data-risk forum for new purposes, bias and incidents. Any change affecting formula or eligibility requires version control and customer communication.")),
                        List.of(t(vi, "Một chủ sở hữu cho mỗi quyết định, một người thay thế khi có sự cố.", "One owner per decision, one delegate for incidents."),
                                t(vi, "Đối soát hàng ngày giữa dữ liệu hoạt động, điểm và phần thưởng.", "Daily reconciliation across activity, points and rewards."),
                                t(vi, "Nhật ký thay đổi bất biến cho quy tắc, dữ liệu và thao tác thủ công.", "Immutable change log for rules, data and manual actions."),
                                t(vi, "SLA và quyền phê duyệt riêng cho điều chỉnh điểm và khiếu nại tài chính.", "Separate SLAs and approval rights for point corrections and financial complaints."))),
                new Section("05", t(vi, "Lợi ích, bằng chứng và giới hạn", "Benefits, evidence and limits"),
                        t(vi, "Tần suất tương tác cao hơn tạo ra lựa chọn chiến lược - chưa chứng minh kết quả bảo hiểm.",
                                "Higher engagement creates strategic options - it does not prove insurance outcomes."),
                        List.of(
                                t(vi, "Giá trị gần nhất nằm ở trải nghiệm: thêm điểm chạm hữu ích giữa các kỳ đóng phí, đưa dịch vụ phòng ngừa vào hành trình, làm rõ tiến độ và tạo lý do quay lại ứng dụng. Với phân phối, chương trình có thể mở ra cuộc trò chuyện sau bán hàng dựa trên dịch vụ thay vì chỉ nhắc phí.",
                                        "The nearest-term value is experiential: useful touchpoints between premium dates, preventive services in the journey, visible progress and a reason to return to the app. For distribution, the programme can create post-sale conversations based on service rather than premium reminders alone."),
                                t(vi, "Giá trị kinh doanh cần được chứng minh riêng: duy trì hợp đồng, giảm chi phí phục vụ, mức sử dụng dịch vụ và kinh tế đối tác. Phân tích của Swiss Re cung cấp bằng chứng định hướng về mối liên hệ giữa chương trình sức khỏe số và tử vong, nhưng không thay thế kiểm định định phí trên dân số, sản phẩm và thị trường Việt Nam.",
                                        "Business value must be evidenced separately through persistence, cost-to-serve, service utilisation and partner economics. Swiss Re provides directional analysis on digital health programmes and mortality, but it does not replace actuarial validation for the Vietnam population, product and market.")),
                        List.of(t(vi, "Cơ hội: quan hệ dịch vụ thường xuyên và tăng khả năng hiểu sản phẩm.", "Opportunity: an ongoing service relationship and better product comprehension."),
                                t(vi, "Giới hạn: tự lựa chọn, người dùng thiết bị và nhóm tích cực có thể làm sai lệch kết quả.", "Limit: self-selection, device ownership and active-user bias can distort results."),
                                t(vi, "Yêu cầu bằng chứng: so sánh theo phân khúc và theo thời gian, không chỉ báo cáo tổng.", "Evidence requirement: compare by segment and over time, not only aggregate totals."))),
                new Section("06", t(vi, "Loại trừ, công bằng và ranh giới dữ liệu", "Exclusions, fairness and the data boundary"),
                        t(vi, "Thiết kế tốt không biến khả năng vận động, sở hữu thiết bị hay chia sẻ dữ liệu thành điều kiện ngầm của bảo vệ.",
                                "A sound design does not turn mobility, device ownership or data sharing into a hidden condition of protection."),
                        List.of(
                                t(vi, "Cần tách loại trừ của hợp đồng khỏi điều kiện nhận thưởng. Quyền lợi, thời gian chờ và loại trừ thuộc hợp đồng; điểm, hạng, ngày đánh giá và trạng thái thành viên thuộc chương trình. Khi phần thưởng không đạt, thông báo phải chỉ ra quy tắc chương trình nào chưa đáp ứng chứ không dùng ngôn ngữ từ chối quyền lợi bảo hiểm.",
                                        "Insurance exclusions must remain separate from reward eligibility. Benefits, waiting periods and exclusions belong to the policy; points, status, assessment date and membership state belong to the programme. When a reward is not earned, the notice should identify the unmet programme rule rather than use claims-denial language."),
                                t(vi, "Khung công bằng cần xem xét khả năng tiếp cận điện thoại, thiết bị, địa điểm dịch vụ, sức khỏe, tuổi, khuyết tật và công việc. Hoạt động thay thế phải tạo cơ hội đạt giá trị tương đương. Cơ chế khiếu nại cần cho phép bổ sung bằng chứng khi thiết bị, đối tác hoặc kết nối gây lỗi.",
                                        "The fairness framework should examine access to phones, devices, service locations, health, age, disability and occupation. Alternative activities should offer a comparable opportunity to earn value. Appeals must permit additional evidence when devices, partners or connectivity fail."),
                                t(vi, "Ranh giới dữ liệu cần được mô tả theo mục đích: vận hành chương trình, cá nhân hóa dịch vụ, nghiên cứu tổng hợp và quyết định bảo hiểm là bốn mục đích khác nhau. Việc đồng ý cho chương trình không tự động cho phép dùng dữ liệu để định phí, thẩm định hay bồi thường.",
                                        "The data boundary should be purpose-based: programme operation, service personalisation, aggregate research and insurance decisions are four different purposes. Consent to the programme does not automatically permit use for pricing, underwriting or claims.")),
                        List.of(t(vi, "Có hoạt động thay thế cho khách hàng không dùng thiết bị đeo.", "Offer alternatives for customers who do not use wearables."),
                                t(vi, "Không làm giảm quyền lợi hợp đồng vì không tham gia chương trình.", "Do not reduce policy benefits for non-participation."),
                                t(vi, "Đặt thời hạn lưu trữ và xóa cho từng loại dữ liệu.", "Set retention and deletion periods by data type."),
                                t(vi, "Theo dõi tỷ lệ tham gia, đạt thưởng và khiếu nại theo phân khúc.", "Monitor participation, reward attainment and complaints by segment."))),
                new Section("07", t(vi, "Khuyến nghị pilot và tiêu chí quyết định", "Pilot recommendation and decision criteria"),
                        t(vi, "Bắt đầu bằng dịch vụ và gắn kết; chỉ tăng liên kết tài chính sau khi bằng chứng vượt qua ngưỡng định trước.",
                                "Start with service and engagement; deepen the financial linkage only after evidence clears predefined thresholds."),
                        List.of(
                                t(vi, "Pilot nên trả lời một câu hỏi hẹp: một nhóm khách hàng xác định có hiểu và sử dụng một hành vi dễ tiếp cận hay không, với phần thưởng cố định có trần và không ảnh hưởng định phí, thẩm định hoặc bồi thường. Thiết kế hẹp giúp phân biệt lỗi giá trị, lỗi trải nghiệm và lỗi vận hành.",
                                        "The pilot should answer one narrow question: whether a defined customer group understands and uses one accessible behaviour with a fixed capped reward that does not affect pricing, underwriting or claims. A narrow design separates value failure, experience failure and operating failure."),
                                t(vi, "Quyết định mở rộng cần xem đồng thời bốn mặt: giá trị cho khách hàng, kinh tế đơn vị, độ tin cậy vận hành và công bằng. Một chỉ số kích hoạt đẹp không đủ nếu khiếu nại cao, chi phí phục vụ vượt trần hoặc một phân khúc liên tục không đạt phần thưởng.",
                                        "A scale decision should examine four dimensions together: customer value, unit economics, operational reliability and fairness. Strong activation is insufficient if complaints are high, cost-to-serve exceeds the cap or one segment persistently fails to earn rewards.")),
                        List.of(
                                t(vi, "Phạm vi: một nhóm khách hàng, một hành vi, một phần thưởng cố định có trần.", "Scope: one customer group, one behaviour, one fixed capped reward."),
                                t(vi, "Đo lường: kích hoạt, duy trì, mức hiểu, chi phí phục vụ, lỗi, khiếu nại và sai lệch theo nhóm.", "Measure: activation, retention, comprehension, cost-to-serve, errors, complaints and segment disparity."),
                                t(vi, "Cổng mở rộng: đạt ngưỡng giá trị, kinh tế, vận hành và công bằng cùng lúc.", "Scale gate: clear value, economics, operations and fairness thresholds together."),
                                t(vi, "Ngưỡng dừng: sự cố dữ liệu nghiêm trọng, sai lệch kéo dài, khiếu nại hoặc chi phí vượt trần.", "Stop threshold: material data incident, persistent disparity, complaints or cost above cap."),
                                t(vi, "Không dùng kết quả tương tác làm bằng chứng định phí khi chưa có kiểm định riêng.", "Do not use engagement results as pricing evidence without separate validation.")))
        );

        return new Issue("wellness-linked-life", labels,
                t(vi, "PRODUCT ACADEMY - CHUYÊN ĐỀ THÁNG", "PRODUCT ACADEMY - MONTHLY SPECIAL ISSUE"),
                t(vi, "Bảo hiểm nhân thọ gắn với chăm sóc sức khỏe", "Wellness-linked life insurance"),
                t(vi, "Cách bảo vệ, gắn kết và phần thưởng vận hành như một hệ thống sản phẩm",
                        "How protection, engagement and rewards work as one product system"),
                t(vi, "THÁNG 7 - 2026", "JULY - 2026"),
                t(vi, "Một chuyên đề học thuật có dẫn nguồn công khai", "A cited learning issue built from public evidence"),
                t(vi, "Bảo hiểm nhân thọ gắn với chăm sóc sức khỏe có thể biến một hợp đồng ít tương tác thành mối quan hệ dịch vụ thường xuyên, nhưng đây không phải một tính năng ứng dụng. Nó là hệ thống gồm hợp đồng, chương trình và quy tắc liên kết. Giá trị chỉ bền vững khi công thức phần thưởng, kinh tế, dữ liệu, công bằng và mô hình vận hành được thiết kế cùng nhau. Khuyến nghị là pilot hẹp ở lớp dịch vụ, với phần thưởng có trần và cổng mở rộng định trước - chưa dùng dữ liệu chương trình cho định phí, thẩm định hoặc bồi thường.",
                        "Wellness-linked life insurance can turn a low-frequency policy into an ongoing service relationship, but it is not an app feature. It is a system comprising the policy, programme and linkage rule. Sustainable value requires reward formula, economics, data, fairness and operating model to be designed together. The recommended starting point is a narrow service-layer pilot with a capped reward and predefined scale gates - without using programme data for pricing, underwriting or claims."),
                7, 4, 3,
                List.of(
                        new KeyFinding("01", t(vi, "Thiết kế ba lớp", "Three-layer design"), t(vi, "Tách lời hứa bảo hiểm, trải nghiệm chương trình và công thức tạo giá trị để khách hàng, hệ thống và đội vận hành hiểu cùng một cách.", "Separate the insurance promise, programme experience and value formula so customers, systems and operators share one interpretation.")),
                        new KeyFinding("02", t(vi, "Kinh tế trước tương tác", "Economics before engagement"), t(vi, "Ngày đánh giá, tỷ lệ, phí bình quân, trần và điều kiện tạo nghĩa vụ thực; chỉ số tương tác không thay thế mô hình kinh tế đơn vị.", "Assessment date, rate, premium base, cap and conditions create a real obligation; engagement metrics do not replace unit economics.")),
                        new KeyFinding("03", t(vi, "Mở rộng bằng cổng bằng chứng", "Scale through evidence gates"), t(vi, "Pilot lớp dịch vụ trước; chỉ tăng liên kết tài chính khi giá trị, kinh tế, vận hành và công bằng cùng vượt ngưỡng.", "Pilot the service layer first; deepen financial linkage only when value, economics, operations and fairness clear the gate together."))
                ), sections, sources(locale));
    }

    @Transactional
    public Issue saveEditorial(String slug, Locale locale, MultiValueMap<String, String> form,
                               String editor) {
        Issue current = issue(slug, locale);
        List<KeyFinding> findings = new java.util.ArrayList<>();
        for (int i = 0; i < current.keyFindings().size(); i++) {
            KeyFinding old = current.keyFindings().get(i);
            findings.add(new KeyFinding(old.number(), value(form, "finding." + i + ".title", old.title()),
                    value(form, "finding." + i + ".detail", old.detail())));
        }
        List<Section> sections = new java.util.ArrayList<>();
        for (int i = 0; i < current.sections().size(); i++) {
            Section old = current.sections().get(i);
            sections.add(new Section(old.number(), value(form, "section." + i + ".title", old.title()),
                    value(form, "section." + i + ".lead", old.lead()),
                    paragraphs(value(form, "section." + i + ".paragraphs", String.join("\n\n", old.paragraphs()))),
                    bullets(value(form, "section." + i + ".bullets", String.join("\n", old.bullets())))));
        }
        List<SourceNote> sources = new java.util.ArrayList<>();
        for (int i = 0; i < current.sources().size(); i++) {
            SourceNote old = current.sources().get(i);
            sources.add(new SourceNote(old.code(), value(form, "source." + i + ".publisher", old.publisher()),
                    value(form, "source." + i + ".title", old.title()),
                    value(form, "source." + i + ".url", old.url()),
                    value(form, "source." + i + ".type", old.type())));
        }
        Issue revised = new Issue(slug, current.labels(), value(form, "series", current.series()),
                value(form, "title", current.title()), value(form, "deck", current.deck()),
                value(form, "issueMonth", current.issueMonth()),
                value(form, "provenance", current.provenance()),
                value(form, "executiveSummary", current.executiveSummary()),
                current.evidenceDocuments(), current.independentPublishers(), current.primarySources(),
                List.copyOf(findings), List.copyOf(sections), List.copyOf(sources));
        String language = isVi(locale) ? "vi" : "en";
        transientDrafts.put(slug + ":" + language, revised);
        if (drafts != null && json != null) {
            try {
                String body = json.writeValueAsString(revised);
                SpecialIssueDraft draft = drafts.findBySlugAndLanguage(slug, language)
                        .orElseGet(() -> new SpecialIssueDraft(slug, language, body, editor));
                draft.replace(body, editor);
                drafts.save(draft);
            } catch (Exception error) {
                throw new IllegalStateException("Could not save the editorial draft", error);
            }
        }
        return revised;
    }

    private Issue edited(String slug, Locale locale) {
        String language = isVi(locale) ? "vi" : "en";
        Issue memory = transientDrafts.get(slug + ":" + language);
        if (memory != null) return memory;
        if (drafts == null || json == null) return null;
        return drafts.findBySlugAndLanguage(slug, language).map(draft -> {
            try { return json.readValue(draft.getContentJson(), Issue.class); }
            catch (Exception ignored) { return null; }
        }).orElse(null);
    }

    private static String value(MultiValueMap<String, String> form, String key, String fallback) {
        String value = form.getFirst(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static List<String> paragraphs(String value) {
        return java.util.Arrays.stream(value.split("(?:\\r?\\n){2,}"))
                .map(String::strip).filter(line -> !line.isBlank()).toList();
    }

    private static List<String> bullets(String value) {
        return java.util.Arrays.stream(value.split("\\r?\\n"))
                .map(line -> line.replaceFirst("^[•*-]\\s*", "").strip())
                .filter(line -> !line.isBlank()).toList();
    }

    private static List<SourceNote> sources(Locale locale) {
        boolean vi = isVi(locale);
        return List.of(
                new SourceNote("01", "AIA Vietnam", "AIA - Khỏe An Nhiên",
                        "https://www.aia.com.vn/vi/san-pham/bao-ve-cuoc-song/aia-khoe-an-nhien.html", t(vi, "Trang sản phẩm sơ cấp", "Primary product page")),
                new SourceNote("02", "AIA Vietnam", t(vi, "Quy tắc và điều khoản Bùng Gia Lực", "Bùng Gia Lực product terms"),
                        "https://www.aia.com.vn/content/dam/vn-wise/san-pham/suc-khoe/benh-hiem-ngheo/tron-tam-an/021025-Quy-tac-va-dieu-khoan-Bao-hiem-Suc-khoe-Bung-Gia-Luc.pdf", t(vi, "Điều khoản sản phẩm sơ cấp", "Primary product terms")),
                new SourceNote("03", "AIA Vietnam", t(vi, "Câu hỏi thường gặp AIA Vitality", "AIA Vitality FAQ"),
                        "https://www.aia.com.vn/vi/song-khoe/song-khoe-cung-aia/aia-vitality/cau-hoi-thuong-gap.html", t(vi, "Quy tắc chương trình sơ cấp", "Primary programme rules")),
                new SourceNote("04", "Swiss Re", t(vi, "Tác động của chương trình sức khỏe số lên tử vong", "Digital health programme impact on mortality"),
                        "https://www.swissre.com/reinsurance/insights/digital-health-programme-impact-on-mortality.html", t(vi, "Phân tích tái bảo hiểm độc lập", "Independent reinsurer analysis")),
                new SourceNote("05", "Manulife / John Hancock", "John Hancock Aspire",
                        "https://www.manulife.com/en/news/john-hancock-verily-onduo-launch-aspire-life-insurance-for-americans-with-diabetes.html", t(vi, "Ví dụ sản phẩm quốc tế", "International product example")),
                new SourceNote("06", "AIA Group", "Live Well",
                        "https://www.aia.com/en/about-aia/our-commitments/health-and-wellness", t(vi, "Mô hình chương trình khu vực", "Regional programme model")),
                new SourceNote("07", "AIA Vietnam / FPT", t(vi, "Hợp tác hệ sinh thái sức khỏe số", "Digital health ecosystem partnership"),
                        "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/thong-cao-bao-chi", t(vi, "Bối cảnh vận hành địa phương", "Local operating context"))
        );
    }

    private static boolean isVi(Locale locale) { return locale != null && "vi".equals(locale.getLanguage()); }
    private static String t(boolean vi, String viText, String enText) { return vi ? viText : enText; }

    public record TopicCandidate(String slug, String title, String deck, int score, int evidenceDocuments,
                                 int independentPublishers, int primarySources, String readiness, String nextStep) {}
    public record Labels(String readIssue, String downloadPdf, String contents, String executiveSummary,
                         String sourcesCited, String publishers, String primarySources, String chapter,
                         String source, String productImplication, String disclaimer) {}
    public record KeyFinding(String number, String title, String detail) {}
    public record Issue(String slug, Labels labels, String series, String title, String deck, String issueMonth,
                        String provenance, String executiveSummary, int evidenceDocuments, int independentPublishers,
                        int primarySources, List<KeyFinding> keyFindings, List<Section> sections, List<SourceNote> sources) {}
    public record Section(String number, String title, String lead, List<String> paragraphs, List<String> bullets) {
        public String paragraphsText() { return String.join("\n\n", paragraphs); }
        public String bulletsText() { return String.join("\n", bullets); }
    }
    public record SourceNote(String code, String publisher, String title, String url, String type) {}

    public static final class UnknownIssueException extends IllegalArgumentException {
        public UnknownIssueException(String slug) { super("Special Issue not found: " + slug); }
    }
    public static final class IssueNotReadyException extends IllegalStateException {
        public IssueNotReadyException(String message) { super(message); }
    }
}
