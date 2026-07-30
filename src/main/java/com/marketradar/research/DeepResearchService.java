package com.marketradar.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketradar.fetch.SafeFetcher;
import com.marketradar.llm.JsonRepair;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.parse.ContentParsers;
import com.marketradar.report.bi.BiCitation;
import com.marketradar.report.bi.BiFinding;
import com.marketradar.report.bi.BiReportContent;
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
    private static final int MAX_ITERATIONS = 5;
    private static final int MAX_SOURCES = 8;
    private static final int MAX_NEW_SOURCES_PER_SEARCH = 3;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepResearchService(LlmClient llm, NewsDiscoveryService discovery,
                               BrowserRenderService browserRender, SafeFetcher fetcher,
                               ContentParsers parsers) {
        this.llm = llm;
        this.discovery = discovery;
        this.browserRender = browserRender;
        this.fetcher = fetcher;
        this.parsers = parsers;
    }

    private record GatheredSource(String label, String url, String acquisition, String excerpt) {}

    public BiReportContent research(String prompt) {
        return research(prompt, step -> {});
    }

    /** @param onStep nhận 1 dòng trạng thái mỗi bước — dùng để stream tiến trình qua SSE
     *  (xem DeepResearchController); no-op an toàn khi gọi đồng bộ không cần theo dõi. */
    public BiReportContent research(String prompt, Consumer<String> onStep) {
        List<GatheredSource> gathered = new ArrayList<>();
        onStep.accept("Bắt đầu Deep Research cho: \"" + prompt + "\"");

        for (int iteration = 0; iteration < MAX_ITERATIONS && gathered.size() < MAX_SOURCES; iteration++) {
            PlanAction action = plan(prompt, gathered, iteration);
            if (action == null || "STOP".equalsIgnoreCase(action.action())) {
                onStep.accept("Vòng " + (iteration + 1) + "/" + MAX_ITERATIONS + " — AI quyết định: DỪNG"
                        + (action != null && !action.reason().isBlank() ? " (" + action.reason() + ")" : ""));
                break;
            }
            if ("SEARCH".equalsIgnoreCase(action.action()) && action.target() != null && !action.target().isBlank()) {
                onStep.accept("Vòng " + (iteration + 1) + "/" + MAX_ITERATIONS + " — Tìm kiếm mở: \"" + action.target() + "\"");
                int before = gathered.size();
                runSearch(action.target(), gathered);
                onStep.accept("→ Đọc được " + (gathered.size() - before) + " nguồn mới (tổng " + gathered.size() + ")");
            } else if ("BROWSE".equalsIgnoreCase(action.action()) && action.target() != null && !action.target().isBlank()) {
                onStep.accept("Vòng " + (iteration + 1) + "/" + MAX_ITERATIONS + " — Render trình duyệt: " + action.target());
                int before = gathered.size();
                runBrowse(action.target(), gathered);
                onStep.accept(gathered.size() > before ? "→ Đọc thành công" : "→ Render lỗi, bỏ qua nguồn này");
            }
        }

        onStep.accept("Đang tổng hợp báo cáo từ " + gathered.size() + " nguồn…");
        BiReportContent content = synthesize(prompt, gathered);
        onStep.accept("Hoàn tất — " + content.findings().size() + " nhận định qua " + gathered.size() + " nguồn.");
        return content;
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
            var parsed = parsers.parseArticleHtml(html.getBytes(StandardCharsets.UTF_8));
            gathered.add(new GatheredSource(
                    parsed.title() != null && !parsed.title().isBlank() ? parsed.title() : url,
                    url, "Render trình duyệt", truncate(parsed.text(), EXCERPT_CHARS_FOR_SYNTHESIS)));
        } catch (Exception e) {
            log.warn("Deep Research: render lỗi cho {}: {}", url, e.getMessage());
        }
    }

    private static boolean alreadyGathered(List<GatheredSource> gathered, String url) {
        return gathered.stream().anyMatch(g -> g.url().equals(url));
    }

    private record PlanAction(String action, String target, String reason) {}

    private PlanAction plan(String prompt, List<GatheredSource> gathered, int iteration) {
        StringBuilder user = new StringBuilder();
        user.append("YÊU CẦU GỐC: ").append(prompt).append("\n---\n");
        user.append("Số nguồn đã thu thập: ").append(gathered.size()).append('\n');
        for (int i = 0; i < gathered.size(); i++) {
            GatheredSource g = gathered.get(i);
            user.append(i + 1).append(". [").append(g.label()).append("] (").append(g.url())
                    .append(") — ").append(g.acquisition()).append('\n')
                    .append("   ").append(truncate(g.excerpt(), EXCERPT_CHARS_FOR_PLANNER)).append('\n');
        }
        user.append("---\n");
        user.append("Hãy quyết định bước tiếp theo. Trả về ĐÚNG 1 JSON object, không thêm chữ nào khác:\n");
        user.append("{\"action\":\"SEARCH|BROWSE|STOP\",\"target\":\"...\",\"reason\":\"...\"}\n");
        user.append("- SEARCH: tìm kiếm mở (Google/Bing News RSS) theo 1 câu hỏi/từ khoá cụ thể (target = câu tìm).\n");
        user.append("- BROWSE: có 1 URL cụ thể cần đọc bằng trình duyệt thật (target = URL đầy đủ).\n");
        user.append("- STOP: đã đủ thông tin để tổng hợp, hoặc không còn hướng tìm khả thi (target để trống).\n");
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
                .append("\"text_vi\":\"...\",\"highlight\":true,\"source_refs\":[1,2]}]}\n");
        user.append("- source_refs: BẮT BUỘC ít nhất 1 số thứ tự nguồn ở trên làm căn cứ cho finding này.\n");
        user.append("- highlight=true cho tối đa 3 finding quan trọng nhất (lên trang Tóm tắt điều hành).\n");
        user.append("- Không bịa: nếu không đủ dữ liệu cho bucket nào thì bỏ qua bucket đó hoàn toàn.");

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
                    List<BiCitation> citations = new ArrayList<>();
                    for (JsonNode refNode : f.path("source_refs")) {
                        int idx = refNode.asInt(-1) - 1;
                        if (idx >= 0 && idx < gathered.size()) {
                            GatheredSource g = gathered.get(idx);
                            citations.add(new BiCitation(g.label(), g.acquisition(), g.url()));
                        }
                    }
                    findings.add(new BiFinding(bucket,
                            f.path("subject_key").isNull() ? null : f.path("subject_key").asText(null),
                            textVi, f.path("highlight").asBoolean(false), citations));
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
                findings.add(new BiFinding(BiFinding.COMPETITIVE_THEME, null, g.excerpt(), false,
                        List.of(new BiCitation(g.label(), g.acquisition(), g.url()))));
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
