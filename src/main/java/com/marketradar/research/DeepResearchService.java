package com.marketradar.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.domain.RawDoc;
import com.marketradar.extract.FactExtractionJob;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.intake.ManualDocumentIntakeService;
import com.marketradar.intake.ManualDocumentRules;
import com.marketradar.interpret.InterpretationJob;
import com.marketradar.llm.JsonRepair;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.parse.ContentParsers;
import com.marketradar.pipeline.ClassificationJob;
import com.marketradar.product.ProductMarketScopeClassifier;
import com.marketradar.report.bi.BiCitation;
import com.marketradar.report.bi.BiFinding;
import com.marketradar.report.bi.BiReportContent;
import com.marketradar.verify.VerificationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Deep Research — agent tự lặp: nhận 1 prompt tự do (không cố định entry point), LLM tự quyết
 * định từng bước (tìm kiếm mở qua HTTP hay đọc 1 URL cụ thể qua trình duyệt thật), tự dừng khi
 * đủ thông tin, rồi tổng hợp thành BiReportContent (render bằng đúng template Meridian dùng
 * chung với bản BI report định kỳ — xem BiReportPageBuilder/bi-report.html).
 *
 * KHÔNG ghi DB — giống tinh thần AdhocDocxService: câu hỏi rời rạc, không nên làm loãng kho
 * evidence chính thức; muốn đưa vào kho thật thì dùng /research/run (ResearchController).
 *
 * An toàn/chi phí: tối đa MAX_ITERATIONS vòng lặp VÀ MAX_SOURCES nguồn — LLM STUB (không có
 * API key) vẫn chạy được nhờ StubLlmClient có nhánh MODE:DEEP_RESEARCH_PLAN/SYNTHESIS riêng.
 * KHÔNG bịa trích dẫn: synthesis chỉ được chọn nguồn theo số thứ tự (source_refs) trỏ về đúng
 * nguồn ĐÃ THẬT SỰ fetch được trong vòng lặp — không cho LLM tự viết URL/tên nguồn.
 */
@Service
public class DeepResearchService {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchService.class);
    // 2026-08-02 (feedback Hanh): 5/8/3 → quá ít cho yêu cầu nhiều khía cạnh (vd so sánh
    // 4-5 đối thủ). Nâng lên để agent có đủ vòng lặp + nguồn cho câu hỏi rộng hơn — vẫn có
    // trần cứng (không chạy vô hạn), chỉ dời trần lên.
    private static final int MAX_ITERATIONS = 12;
    private static final int MAX_SOURCES = 25;
    private static final int MAX_NEW_SOURCES_PER_SEARCH = 5;
    private static final int EXCERPT_CHARS_FOR_SYNTHESIS = 1500;
    private static final int EXCERPT_CHARS_FOR_PLANNER = 200;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Set<String> VALID_BUCKETS = Set.of(
            BiFinding.MACRO_ECONOMIC, BiFinding.COMPETITIVE_THEME, BiFinding.SCHEDULED_EVENT,
            BiFinding.COMPANY_EVENT, BiFinding.MARKET_SHARE_OR_AWARD, BiFinding.TECH_AI_SIGNAL,
            BiFinding.STRATEGIC_COMPARISON);

    private final LlmClient llm;
    private final NewsDiscoveryService discovery;
    private final BrowserRenderService browserRender;
    private final SafeFetcher fetcher;
    private final ContentParsers parsers;
    private final ManualDocumentIntakeService intake;
    private final ClassificationJob classify;
    private final FactExtractionJob extract;
    private final InterpretationJob interpret;
    private final VerificationJob verify;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepResearchService(LlmClient llm, NewsDiscoveryService discovery,
                               BrowserRenderService browserRender, SafeFetcher fetcher,
                               ContentParsers parsers, ManualDocumentIntakeService intake,
                               ClassificationJob classify, FactExtractionJob extract,
                               InterpretationJob interpret, VerificationJob verify) {
        this.llm = llm;
        this.discovery = discovery;
        this.browserRender = browserRender;
        this.fetcher = fetcher;
        this.parsers = parsers;
        this.intake = intake;
        this.classify = classify;
        this.extract = extract;
        this.interpret = interpret;
        this.verify = verify;
    }

    /** renderedHtml chỉ khác null với nguồn lấy qua BROWSE — giữ lại đúng bytes trình duyệt đã
     *  render để nạp qua ManualDocumentIntakeService.importRenderedHtml() không phải render lại
     *  (tốn Chromium 2 lần) và không thử GET trơn một trang vốn cần JS mới có nội dung. */
    private record GatheredSource(String label, String url, String acquisition, String excerpt,
                                  byte[] renderedHtml) {
        GatheredSource(String label, String url, String acquisition, String excerpt) {
            this(label, url, acquisition, excerpt, null);
        }
    }

    /** Kết quả đầy đủ 1 lần chạy — DeepResearchQueueService dùng sourceCount/newDocIds để điền
     *  vào đúng DeepResearchRun đã tạo lúc enqueue (xem DeepResearchRun#markDone). */
    public record ResearchResult(BiReportContent content, int sourceCount, List<Long> newDocIds) {}

    public ResearchResult research(String prompt) {
        return research(prompt, step -> {});
    }

    /** @param onStep nhận 1 dòng trạng thái mỗi bước — DeepResearchQueueService dùng để ghi
     *  progressLog vào DB theo thời gian thực; no-op an toàn khi gọi không cần theo dõi. */
    public ResearchResult research(String prompt, Consumer<String> onStep) {
        List<GatheredSource> gathered = new ArrayList<>();
        List<String> triedQueries = new ArrayList<>();
        onStep.accept("Bắt đầu Deep Research cho: \"" + prompt + "\"");

        for (int iteration = 0; iteration < MAX_ITERATIONS && gathered.size() < MAX_SOURCES; iteration++) {
            PlanAction action = plan(prompt, gathered, triedQueries, iteration);
            if (action == null || "STOP".equalsIgnoreCase(action.action())) {
                onStep.accept("Vòng " + (iteration + 1) + "/" + MAX_ITERATIONS + " — AI quyết định: DỪNG"
                        + (action != null && !action.reason().isBlank() ? " (" + action.reason() + ")" : ""));
                break;
            }
            if ("SEARCH".equalsIgnoreCase(action.action()) && action.target() != null && !action.target().isBlank()) {
                onStep.accept("Vòng " + (iteration + 1) + "/" + MAX_ITERATIONS + " — Tìm kiếm mở: \"" + action.target() + "\"");
                int before = gathered.size();
                runSearch(action.target(), gathered);
                int found = gathered.size() - before;
                onStep.accept("→ Đọc được " + found + " nguồn mới (tổng " + gathered.size() + ")");
                triedQueries.add(action.target() + " → " + found + " nguồn mới");
            } else if ("BROWSE".equalsIgnoreCase(action.action()) && action.target() != null && !action.target().isBlank()) {
                onStep.accept("Vòng " + (iteration + 1) + "/" + MAX_ITERATIONS + " — Render trình duyệt: " + action.target());
                int before = gathered.size();
                runBrowse(action.target(), gathered);
                onStep.accept(gathered.size() > before ? "→ Đọc thành công" : "→ Render lỗi, bỏ qua nguồn này");
            }
        }

        List<Long> newDocIds = ingestIntoPipeline(prompt, gathered, onStep);
        runVerificationPipeline(newDocIds, onStep);

        onStep.accept("Đang tổng hợp báo cáo từ " + gathered.size() + " nguồn…");
        BiReportContent content = appendPipelineNote(synthesize(prompt, gathered), newDocIds.size());
        onStep.accept("Hoàn tất — " + content.findings().size() + " nhận định qua " + gathered.size() + " nguồn.");
        return new ResearchResult(content, gathered.size(), newDocIds);
    }

    /**
     * Nạp mỗi nguồn đã đọc được thành 1 RawDoc thật (đúng đường ManualDocumentIntakeService dùng
     * chung với Quick Search /research/run — validate/metadata/dedup sẵn có, intake=OPEN_SEARCH
     * cho nguồn tìm mở, BROWSER_RENDER cho nguồn phải render JS). Đây là điểm khác Deep Research
     * TRƯỚC ĐÂY (chỉ giữ excerpt trong RAM rồi 1 lần gọi LLM tự do viết narrative — không ai gác
     * cổng): giờ nguồn thật sự đi vào kho evidence, sẵn sàng cho pipeline verify thật ở dưới.
     * Một nguồn bị từ chối (quá ngắn, thiếu tiêu đề...) không được phép làm hỏng cả yêu cầu —
     * ghi log, đếm, đi tiếp.
     */
    private List<Long> ingestIntoPipeline(String prompt, List<GatheredSource> gathered, Consumer<String> onStep) {
        List<Long> newDocIds = new ArrayList<>();
        int duplicates = 0, rejected = 0;
        for (GatheredSource g : gathered) {
            try {
                ManualDocumentIntakeService.Result result = g.renderedHtml() != null
                        ? intake.importRenderedHtml(g.url(), g.renderedHtml())
                        : intake.importUrl(g.url(), RawDoc.IntakeMethod.OPEN_SEARCH,
                                "DEEP_RESEARCH | prompt=\"" + prompt + "\"");
                if (result.duplicate()) duplicates++;
                else if (result.rawDocId() != null) newDocIds.add(result.rawDocId());
            } catch (ManualDocumentRules.ValidationException invalid) {
                rejected++;
                log.warn("Deep Research: intake từ chối {} ({})", g.url(), invalid.getMessage());
            } catch (Exception unexpected) {
                rejected++;
                log.warn("Deep Research: intake lỗi không lường trước cho {}: {}", g.url(), unexpected.toString());
            }
        }
        onStep.accept("Nạp vào kho evidence: +" + newDocIds.size() + " tài liệu mới"
                + (duplicates > 0 ? ", " + duplicates + " trùng (đã có)" : "")
                + (rejected > 0 ? ", " + rejected + " bị loại (không đạt chuẩn nạp — vd quá ngắn)" : "") + ".");
        return newDocIds;
    }

    /**
     * Chạy ĐÚNG pipeline thật (Router → Researcher → Analyst/Fact-checker → Independent Verifier)
     * nhưng CHỈ trên các RawDoc vừa nạp ở trên — không quét lại toàn kho mỗi lần Deep Research
     * chạy. Classify dùng retryOne (đường "single-doc retry" sẵn có, không đụng dedup toàn cục);
     * Extract dùng runTargeted (đường "bounded reprocessing" sẵn có). Interpret/Verify chưa có
     * biến thể theo ID cụ thể nên gọi nguyên bản runOnce() — an toàn vì cả hai đã tự bỏ qua mọi
     * thứ chưa CONFIRMED/chưa có fact mới, chỉ xử lý phần việc thật sự mới sinh ra ở trên.
     *
     * Claim sinh ra ở đây đi qua ĐÚNG 2 lớp gác (Gate L1 exact-match + Gate L2 verifier khác họ
     * model) như mọi claim từ crawl — không có đường tắt nào bỏ qua fact-check cho riêng Deep
     * Research. Claim PASS + ENTAILED được tự duyệt; còn lại chờ người ở /review — đúng yêu cầu
     * "nguồn linh hoạt nhưng vẫn phải qua pipeline xác thực".
     */
    private void runVerificationPipeline(List<Long> newDocIds, Consumer<String> onStep) {
        if (newDocIds.isEmpty()) return;
        onStep.accept("Đưa " + newDocIds.size() + " tài liệu vào pipeline xác thực (Router → Researcher → Analyst → Fact-checker → Verifier)…");
        int classified = 0, classifyErrors = 0;
        for (Long id : newDocIds) {
            try {
                classify.retryOne(id);
                classified++;
            } catch (Exception e) {
                classifyErrors++;
                log.warn("Deep Research: classify lỗi doc#{}: {}", id, e.getMessage());
                if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().contains("STUB")) {
                    onStep.accept("→ Classifier đang STUB — bỏ qua bước xác thực pipeline (cấu hình LLM Settings để bật).");
                    return;
                }
            }
        }
        onStep.accept("→ Đã phân loại " + classified + "/" + newDocIds.size() + " tài liệu"
                + (classifyErrors > 0 ? " (" + classifyErrors + " lỗi)" : "") + ".");

        String extractSummary = safeStage(() -> extract.runTargeted(newDocIds));
        onStep.accept("→ Extract: " + firstLine(extractSummary));

        String interpretSummary = safeStage(interpret::runOnce);
        onStep.accept("→ Interpret (Analyst + Fact-checker Gate L1): " + firstLine(interpretSummary));

        String verifySummary = safeStage(verify::runOnce);
        onStep.accept("→ Verify (Gate L2, model độc lập): " + firstLine(verifySummary));

        onStep.accept("Nhận định đã qua đủ 2 lớp gác đang chờ ở /review — sau khi duyệt sẽ tự vào báo cáo BI kỳ tiếp theo.");
    }

    private interface StageCall { String run(); }

    private static String safeStage(StageCall call) {
        try {
            return call.run();
        } catch (Exception e) {
            return "lỗi — " + e.getMessage();
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) return "(không có output)";
        int nl = text.indexOf('\n');
        return nl < 0 ? text.strip() : text.substring(0, nl).strip();
    }

    /** Gắn rõ trạng thái "đã nạp pipeline xác thực" vào bản xem nhanh, để người đọc không nhầm
     *  narrative tự do bên dưới (chưa qua Gate L1/L2) với các claim thật đang chờ duyệt. */
    private static BiReportContent appendPipelineNote(BiReportContent content, int newDocCount) {
        if (newDocCount == 0) return content;
        List<String> gaps = new ArrayList<>(content.openGaps());
        gaps.add(0, "Bản xem nhanh này do AI tổng hợp trực tiếp từ nguồn thu thập được — CHƯA qua "
                + "Fact-checker/Verifier. " + newDocCount + " tài liệu nguồn thật đã được nạp riêng vào pipeline "
                + "xác thực (Router/Researcher/Analyst/Fact-checker/Verifier) và đang chờ duyệt ở /review; sau khi "
                + "duyệt, nhận định đã kiểm chứng sẽ tự động vào báo cáo BI định kỳ chính thức.");
        return new BiReportContent(content.title(), content.period(), content.homeCompany(),
                content.generatedAt(), content.docCount(), content.findings(), content.sourceLines(), gaps);
    }

    private void runSearch(String query, List<GatheredSource> gathered) {
        List<NewsDiscoveryService.Candidate> candidates;
        try {
            candidates = discovery.discover(query);
        } catch (NewsDiscoveryService.DiscoveryFailedException e) {
            log.warn("Deep Research: tìm kiếm mở lỗi cho '{}': {}", query, e.getMessage());
            return;
        }
        int added = 0;
        for (var c : candidates) {
            if (added >= MAX_NEW_SOURCES_PER_SEARCH || gathered.size() >= MAX_SOURCES) break;
            if (c.publisherUrl() == null || alreadyGathered(gathered, c.publisherUrl())) continue;
            try {
                var fetched = fetcher.fetchDocument(c.publisherUrl());
                boolean pdf = "application/pdf".equalsIgnoreCase(fetched.contentType());
                var parsed = pdf ? parsers.parsePdf(fetched.body()) : parsers.parseArticleHtml(fetched.body());
                gathered.add(new GatheredSource(
                        c.title() != null && !c.title().isBlank() ? c.title() : c.publisherUrl(),
                        c.publisherUrl(), "Tìm kiếm mở", truncate(parsed.text(), EXCERPT_CHARS_FOR_SYNTHESIS)));
                added++;
            } catch (Exception e) {
                log.warn("Deep Research: bỏ qua nguồn {} ({})", c.publisherUrl(), e.getMessage());
            }
        }
    }

    private void runBrowse(String url, List<GatheredSource> gathered) {
        if (alreadyGathered(gathered, url)) return;
        try {
            String html = browserRender.renderHtml(url);
            byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
            var parsed = parsers.parseArticleHtml(htmlBytes);
            gathered.add(new GatheredSource(
                    parsed.title() != null && !parsed.title().isBlank() ? parsed.title() : url,
                    url, "Render trình duyệt", truncate(parsed.text(), EXCERPT_CHARS_FOR_SYNTHESIS),
                    htmlBytes));
        } catch (Exception e) {
            log.warn("Deep Research: render lỗi cho {}: {}", url, e.getMessage());
        }
    }

    private static boolean alreadyGathered(List<GatheredSource> gathered, String url) {
        return gathered.stream().anyMatch(g -> g.url().equals(url));
    }

    private record PlanAction(String action, String target, String reason) {}

    private PlanAction plan(String prompt, List<GatheredSource> gathered, List<String> triedQueries, int iteration) {
        StringBuilder user = new StringBuilder();
        user.append("YÊU CẦU GỐC: ").append(prompt).append("\n---\n");
        user.append("Số nguồn đã thu thập: ").append(gathered.size()).append('\n');
        for (int i = 0; i < gathered.size(); i++) {
            GatheredSource g = gathered.get(i);
            user.append(i + 1).append(". [").append(g.label()).append("] (").append(g.url())
                    .append(") — ").append(g.acquisition()).append('\n')
                    .append("   ").append(truncate(g.excerpt(), EXCERPT_CHARS_FOR_PLANNER)).append('\n');
        }
        if (!triedQueries.isEmpty()) {
            user.append("---\nCÁC CÂU TÌM ĐÃ THỬ (đừng lặp lại/biến tấu nhẹ những câu đã cho 0 nguồn mới — đổi HẲN hướng khác):\n");
            for (String q : triedQueries) user.append("- ").append(q).append('\n');
        }
        user.append("---\n");
        user.append("Hãy quyết định bước tiếp theo. Trả về ĐÚNG 1 JSON object, không thêm chữ nào khác:\n");
        user.append("{\"action\":\"SEARCH|BROWSE|STOP\",\"target\":\"...\",\"reason\":\"...\"}\n");
        user.append("- SEARCH: tìm kiếm mở (Google/Bing News RSS) theo 1 câu hỏi/từ khoá cụ thể (target = câu tìm). ")
                .append("QUAN TRỌNG — đây là tìm kiếm tin tức đơn giản, KHÔNG phải Google Search nâng cao: ")
                .append("target chỉ nên 3-6 từ khoá cốt lõi, KHÔNG xếp chồng nhiều 'site:' (dùng TỐI ĐA 1 site: mỗi câu, ")
                .append("hoặc bỏ hẳn để tìm rộng), KHÔNG bọc quá nhiều cụm trong dấu ngoặc kép cùng lúc. ")
                .append("Nếu 1-2 câu tìm trước đã cho 0 nguồn mới, đừng thêm từ khoá/site: để 'cụ thể hơn' — ")
                .append("càng nhiều điều kiện càng dễ ra 0 kết quả. Thay vào đó ĐƠN GIẢN HOÁ triệt để (chỉ 2-3 từ khoá) ")
                .append("hoặc đổi hẳn góc tiếp cận (đổi ngôn ngữ VI/EN, đổi tên công ty cụ thể thay vì liệt kê nhiều công ty, ")
                .append("bỏ hẳn giới hạn site/nguồn).\n");
        user.append("- BROWSE: có 1 URL cụ thể cần đọc bằng trình duyệt thật (target = URL đầy đủ).\n");
        user.append("- STOP: đã đủ thông tin để tổng hợp, hoặc đã thử ≥3 câu tìm khác hướng đều cho 0 nguồn mới ")
                .append("(không còn hướng tìm khả thi — dừng lại còn hơn lặp vô ích, target để trống).\n");
        user.append("Đang ở vòng ").append(iteration + 1).append('/').append(MAX_ITERATIONS).append('.');

        String raw = safeComplete("MODE:DEEP_RESEARCH_PLAN\nBạn là agent nghiên cứu thị trường, tự quyết định bước tìm tiếp theo.",
                user.toString());
        if (raw == null) return null;
        try {
            JsonNode root = parseJson(raw);
            return new PlanAction(
                    root.path("action").asText("STOP"),
                    root.path("target").isNull() ? null : root.path("target").asText(null),
                    root.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("Deep Research: plan step trả JSON không đọc được, dừng vòng lặp: {}", e.getMessage());
            return null;
        }
    }

    private BiReportContent synthesize(String prompt, List<GatheredSource> gathered) {
        String generatedAt = ZonedDateTime.now().format(TS_FMT);
        String period = "Ad-hoc · " + generatedAt;

        if (gathered.isEmpty()) {
            return new BiReportContent("Deep Research — " + shortLabel(prompt), period, null, generatedAt, 0,
                    List.of(), List.of(),
                    List.of("Không tìm/đọc được nguồn nào cho yêu cầu này trong " + MAX_ITERATIONS + " vòng lặp."));
        }

        StringBuilder user = new StringBuilder();
        user.append("YÊU CẦU GỐC: ").append(prompt).append("\n---\n");
        user.append("Tài liệu đã thu thập được (").append(gathered.size()).append(" nguồn):\n");
        for (int i = 0; i < gathered.size(); i++) {
            GatheredSource g = gathered.get(i);
            user.append(i + 1).append(". [").append(g.label()).append("] (").append(g.url())
                    .append(") — ").append(g.acquisition()).append('\n')
                    .append("   ").append(g.excerpt()).append('\n');
        }
        user.append("---\n");
        user.append("Tổng hợp thành các nhận định (finding) theo đúng 7 bucket sau (bỏ qua bucket không có dữ liệu):\n");
        user.append("MACRO_ECONOMIC, COMPETITIVE_THEME, SCHEDULED_EVENT, COMPANY_EVENT, MARKET_SHARE_OR_AWARD, ")
                .append("TECH_AI_SIGNAL, STRATEGIC_COMPARISON.\n");
        user.append("Trả về ĐÚNG 1 JSON object, không thêm chữ nào khác:\n");
        user.append("{\"title\":\"...\",\"findings\":[{\"bucket\":\"...\",\"subject_key\":\"...\",")
                .append("\"text_vi\":\"...\",\"text_en\":\"...\",\"highlight\":true,\"severity\":null,")
                .append("\"metric_percent\":null,\"source_refs\":[1,2]}]}\n");
        user.append("- text_vi và text_en PHẢI cùng mức độ cụ thể/hedging (số liệu ước tính phải hedge ở CẢ hai bản, không chỉ 1 bản).\n");
        user.append("- source_refs: BẮT BUỘC ít nhất 1 số thứ tự nguồn ở trên làm căn cứ cho finding này.\n");
        user.append("- highlight=true cho tối đa 3 finding quan trọng nhất (lên trang Tóm tắt điều hành).\n");
        user.append("- severity: CHỈ set khi bucket=TECH_AI_SIGNAL và finding là đánh giá rủi ro AI theo 1 công ty cụ thể ")
                .append("(subject_key=tên công ty) — HIGH nếu công ty đã triển khai AI tại thị trường này VÀ có bằng chứng ")
                .append("tác động vận hành đo được VÀ đã công bố đầu tư/tuyển dụng thêm; MEDIUM nếu có năng lực AI toàn cầu/")
                .append("khu vực nhưng chưa tập trung vào thị trường này, hoặc đang triển khai dở kết quả chưa rõ; LOW nếu ")
                .append("có năng lực AI ở nơi khác trong tổ chức nhưng đang bị phân tán ưu tiên (tái cấu trúc, thoái vốn) hoặc ")
                .append("chưa có tín hiệu ý định cho thị trường này. Nếu finding chỉ là số liệu định cỡ thị trường AI/insurtech ")
                .append("(market size, CAGR) — không phải đánh giá theo công ty — để severity=null.\n");
        user.append("- Mọi số liệu/ngày/nhận định phải phản ánh đúng độ tin cậy của nguồn qua CÁCH VIẾT: nguồn chính thức/")
                .append("báo chí uy tín → nêu thẳng; ước tính/nghiên cứu bên thứ 3 → hedge (\"theo ước tính của X\"); ")
                .append("ghi chú/chưa xác thực → nêu rõ \"chưa xác nhận\"/\"cần kiểm chứng\" — KHÔNG được nâng cấp một ước ")
                .append("tính thành như thể là số liệu chính thức.\n");
        user.append("- Với STRATEGIC_COMPARISON: nếu bằng chứng đủ rõ để có một nhận định, hãy nêu rõ và gắn nhãn \"Our read:\"/")
                .append("\"Góc nhìn của chúng tôi:\" ở đầu câu để tách biệt phân tích khỏi sự thật quan sát được — đừng trung lập hoá một cách vô ích.\n");
        user.append("- metric_percent: CHỈ set khi bucket=MARKET_SHARE_OR_AWARD và nguồn nêu RÕ một con số 0-100 dùng để vẽ ")
                .append("thanh bar (vd thị phần APE %); để null nếu không có số liệu thật — trang đó sẽ tự hiển thị dạng bảng thay vì bar.\n");
        user.append("- Không bịa: nếu không đủ dữ liệu cho bucket nào thì bỏ qua bucket đó hoàn toàn. Một câu ngắn nêu rõ ")
                .append("khoảng trống còn hơn là làm dày nội dung bằng dữ kiện yếu.");

        String raw = safeComplete("MODE:DEEP_RESEARCH_SYNTHESIS\nBạn tổng hợp tài liệu nghiên cứu thành nhận định BI có căn cứ.",
                user.toString());

        List<BiFinding> findings = new ArrayList<>();
        String title = "Deep Research — " + shortLabel(prompt);
        List<String> openGaps = new ArrayList<>();
        if (raw != null) {
            try {
                JsonNode root = parseJson(raw);
                if (root.hasNonNull("title") && !root.get("title").asText().isBlank()) {
                    title = root.get("title").asText();
                }
                for (JsonNode f : root.path("findings")) {
                    String bucket = f.path("bucket").asText("");
                    if (!VALID_BUCKETS.contains(bucket)) continue; // schema đóng — bucket lạ bị loại, không đoán
                    String textVi = f.path("text_vi").asText("");
                    if (textVi.isBlank()) continue;
                    String textEn = f.path("text_en").isNull() ? null : f.path("text_en").asText(null);
                    String subjectKey = f.path("subject_key").isNull() ? null : f.path("subject_key").asText(null);
                    List<BiCitation> citations = new ArrayList<>();
                    GatheredSource primarySource = null;
                    for (JsonNode refNode : f.path("source_refs")) {
                        int idx = refNode.asInt(-1) - 1;
                        if (idx >= 0 && idx < gathered.size()) {
                            GatheredSource g = gathered.get(idx);
                            citations.add(new BiCitation(g.label(), g.acquisition(), g.url()));
                            if (primarySource == null) primarySource = g;
                        }
                    }
                    // company=null có chủ đích: subjectKey ở đây do LLM gán tự do, không đi qua
                    // CompetitorRegistry, nhưng vẫn có thể trùng tên hiển thị VN của một đối thủ
                    // dù bằng chứng thật nói về pháp nhân khác cùng thương hiệu — chỉ dùng host
                    // của URL nguồn thật (khách quan hơn tên thực thể) để phân loại thị trường.
                    ProductMarketScopeClassifier.MarketPosition market = ProductMarketScopeClassifier.classify(
                            null, null, null,
                            primarySource == null ? null : primarySource.url(),
                            primarySource == null ? null : primarySource.label(),
                            null);
                    findings.add(new BiFinding(bucket, subjectKey,
                            textVi, textEn, f.path("highlight").asBoolean(false), citations,
                            f.path("severity").isNull() ? null : f.path("severity").asText(null),
                            f.path("metric_percent").isNull() || !f.path("metric_percent").isNumber() ? null
                                    : f.path("metric_percent").asInt(),
                            market.scope(), market.geography()));
                }
            } catch (Exception e) {
                log.warn("Deep Research: synthesis JSON không đọc được, dùng bản dự phòng nguyên văn: {}", e.getMessage());
            }
        }

        if (findings.isEmpty()) {
            // Dự phòng: tổng hợp AI lỗi/không parse được — vẫn giữ nguyên tài liệu đã đọc được
            // thay vì mất trắng (đúng tinh thần AdhocDocxService: giữ nguyên liệu thô khi AI lỗi).
            openGaps.add("Tổng hợp AI không tạo được nhận định có cấu trúc — dưới đây là tài liệu thô đã thu thập.");
            for (GatheredSource g : gathered) {
                ProductMarketScopeClassifier.MarketPosition market = ProductMarketScopeClassifier.classify(
                        null, null, null, g.url(), g.label(), null);
                findings.add(new BiFinding(BiFinding.COMPETITIVE_THEME, null, g.excerpt(), false,
                        List.of(new BiCitation(g.label(), g.acquisition(), g.url())),
                        market.scope(), market.geography()));
            }
        }

        Set<String> sourceLines = new LinkedHashSet<>();
        for (GatheredSource g : gathered) sourceLines.add(g.label() + " (" + g.acquisition() + ")");

        return new BiReportContent(title, period, null, generatedAt, gathered.size(),
                findings, List.copyOf(sourceLines), openGaps);
    }

    private String safeComplete(String systemPrompt, String userPrompt) {
        try {
            return llm.complete(systemPrompt, userPrompt, null);
        } catch (LlmException e) {
            log.warn("Deep Research: LLM lỗi: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode parseJson(String raw) throws Exception {
        String cleaned = raw.strip().replaceAll("(?s)^```(?:json)?", "").replaceAll("(?s)```$", "").strip();
        try {
            return mapper.readTree(cleaned);
        } catch (Exception first) {
            return mapper.readTree(JsonRepair.repairUnescapedQuotes(cleaned));
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() <= maxChars ? t : t.substring(0, maxChars) + "…";
    }

    private static String shortLabel(String prompt) {
        String t = prompt.strip();
        return t.length() <= 70 ? t : t.substring(0, 70) + "…";
    }
}
