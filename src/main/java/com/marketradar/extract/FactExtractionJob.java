package com.marketradar.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.marketradar.domain.Classification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.FactExtractionRun;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.llm.JsonRepair;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.domain.PipelineItemLog;
import com.marketradar.pipeline.PipelineRunStatusService;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.FactExtractionRunRepository;
import com.marketradar.repo.LlmCallLogRepository;
import com.marketradar.repo.PipelineItemLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI#2 — Evidence Extractor (Batch 8): lấp khoảng trống giữa "crawl thật" và
 * "claim thật". Với mỗi RawDoc đã CONFIRMED bởi classifier (và không phải bản
 * trùng), model trích các span NGUYÊN VĂN đáng làm evidence.
 *
 * Invariant giữ bằng CODE, không tin model:
 *  - Mọi span trả về bị kiểm tra substring EXACT với rawText — không khớp
 *    nguyên văn → LOẠI span đó (log rõ), không sửa, không "gần đúng".
 *  - company/productName chỉ được giữ nếu xuất hiện nguyên văn TRONG span
 *    (đây là các trường Gate L1 sẽ đối chiếu verbatim ở bước interpret).
 *  - Model STUB → không trích gì (fail loud) — không có fact heuristic giả.
 *
 * Chạy NGOÀI transaction lớn (bài học 2026-07-12: classify 1 transaction/2.5h
 * không nhìn thấy tiến độ) — mỗi doc commit một extraction edition riêng. Guard
 * idempotent dựa trên pipeline+model+prompt+content signature, không còn chỉ hỏi
 * "doc đã có fact chưa".
 *
 * Dùng WRITER client (@Primary, Claude): chất lượng chọn span quyết định chất
 * lượng mọi thứ phía sau, volume thấp (chỉ doc CONFIRMED — cỡ chục call/vòng).
 */
@Service
public class FactExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(FactExtractionJob.class);
    // 2026-07-15 (audit chất lượng): 6.000 → 24.000. Bài full-text trung bình 6k-16k ký tự;
    // cap cũ cắt mất nửa sau của đa số bài (và trước fix parseArticleHtml thì 6k đầu còn là
    // menu). 24k ký tự tiếng Việt ~ 8-12k token — vừa vặn context các model hiện dùng.
    // 2026-07-15 (feedback Hanh): 5 → 8. Span trung bình chỉ ~229 ký tự → quá ít nguyên
    // liệu để tổng hợp insight sắc. Trích nhiều fact hơn + ưu tiên đoạn giàu dữ kiện.
    // 2026-08-02 (feedback Hanh): 8 → 20. Báo cáo ra quá thưa thớt — 8/chunk (24k ký tự,
    // ~1 bài dài) là trần cứng THẬT SỰ mất dữ kiện, không phải gate tạm giữ lại chờ duyệt
    // (khác Gate L1/L2 phía sau, vốn LƯU LẠI mọi câu kể cả fail để audit). Nới trần lên
    // để bài giàu dữ kiện không bị cắt mất phần sau chunk.
    private static final int MAX_FACTS_PER_CHUNK = 20;

    private static final String SYSTEM = """
            MODE:EXTRACT_FACTS — Bạn nhận TIÊU ĐỀ + NỘI DUNG một tài liệu tin tức ngành
            bảo hiểm nhân thọ. Nhiệm vụ: trích tối đa %d ĐOẠN NGUYÊN VĂN (span) trong
            MỖI CHUNK chứa
            sự kiện/sản phẩm/quy định/số liệu đáng đưa vào evidence store.

            CHỌN SPAN GIÀU DỮ KIỆN: ưu tiên những đoạn chứa CON SỐ cụ thể (phí, quyền lợi,
            doanh thu, tỷ lệ, ngày) VÀ nêu rõ CHỦ THỂ + CƠ CHẾ (ai làm gì, điều kiện gì) —
            đó là nguyên liệu để viết insight sắc. Trích đủ trọn ý (cả câu/mệnh đề đầy đủ),
            không cắt cụt còn vài chữ. Nếu tài liệu có nhiều dữ kiện đáng giá, trích NHIỀU
            span (tới mức tối đa) thay vì chỉ 1-2.

            RÀNG BUỘC TUYỆT ĐỐI:
            - "span" phải là chuỗi CHÉP NGUYÊN VĂN từ tài liệu, KHÔNG sửa một ký tự nào
              (kể cả dấu câu, khoảng trắng). Hệ thống sẽ đối chiếu exact-match và loại
              mọi span không khớp. Trích trọn câu/mệnh đề để đủ ngữ cảnh, nhưng vẫn phải
              là chuỗi liên tục nguyên văn (không ghép các đoạn rời).
            - QUAN TRỌNG (JSON hợp lệ): nếu văn bản gốc trong span có dấu ngoặc kép "
              (vd thuật ngữ được định nghĩa như "NFYP", "HĐBH"), PHẢI escape thành \"
              trong JSON string — dấu " chưa escape sẽ làm hỏng cấu trúc JSON và toàn
              bộ output bị loại. Ví dụ ĐÚNG: "span":"...phí bảo hiểm (\"NFYP\") bao gồm..."
            - KHÔNG bịa thông tin không có trong tài liệu. Tài liệu không có gì đáng
              trích → trả {"facts": []}.
            - company / product_name: chỉ điền nếu tên đó nằm NGUYÊN VĂN trong span.
            - event_date: ngày sự kiện XẢY RA; effective_date: ngày bắt đầu hiệu lực;
              expiry_date: ngày hết hiệu lực/kết thúc; forecast_horizon: mốc dự báo/mục tiêu.
              Chỉ điền khi văn bản ghi rõ, dạng YYYY-MM-DD. Không dùng ngày xuất bản thay thế.
            - summary_vi / summary_en: MỘT câu làm TIÊU ĐỀ digest kinh doanh — người đọc
              lướt qua phải nắm được ngay điều gì xảy ra và vì sao đáng chú ý
              (chủ thể + hành động + con số/chi tiết đắt nhất). KHÔNG viết kiểu
              "Bài viết nói về…", không lặp nguyên văn span. GIỌNG TRUNG LẬP, khách quan:
              KHÔNG khen ngợi/PR (cấm "dẫn đầu", "hàng đầu", "uy tín", "danh giá", "vinh dự",
              "khẳng định vị thế") — nêu dữ kiện, không tán dương, kể cả với đối thủ.
            - Nếu phần NGỮ CẢNH ghi thị trường là REGIONAL (nguồn ngoài Việt Nam —
              không phải đối thủ trực tiếp): viết summary theo hướng BÀI HỌC/GỢI Ý
              cho công ty bảo hiểm nhân thọ Việt Nam (ý tưởng sản phẩm, quy trình,
              mô hình vận hành có thể tham khảo), không viết như tin đối thủ.

            Trả về DUY NHẤT JSON đúng dạng (không markdown, không giải thích):
            {"facts":[{"span":"...","fact_type":"EVENT|PRODUCT_LAUNCH|FEE_CHANGE|REGULATION|METRIC",
            "company":null,"product_name":null,"event_date":null,
            "effective_date":null,"expiry_date":null,"forecast_horizon":null,
            "summary_vi":"...","summary_en":"..."}]}
            """.formatted(MAX_FACTS_PER_CHUNK);

    /**
     * 2026-08-03 — Router (feedback: "Router không được phân loại linh tinh... phải làm rõ
     * requirement, đọc file báo cáo mẫu"): gán nhãn CHO FACT ngay sau khi Researcher (chính là
     * job này) trích xong span, TRƯỚC khi Analyst/Interpreter chạy — thay cho cách cũ là để
     * Interpreter tự vừa viết câu vừa đoán bi_bucket trong CÙNG 1 lần gọi (lỗi kiến trúc CFO
     * chỉ ra: trộn "phân loại" vào "phân tích"). Taxonomy suy trực tiếp từ file mẫu CFO gửi
     * ("Business Intelligence - July 2026.pptx", 14 slide thật), KHÔNG phải suy diễn.
     *
     * Không gán DEEP_DIVE ở đây — đó là quyết định của Connector (gộp nhiều fact thành 1 bài
     * phân tích), không phải quyết định per-fact của Router.
     */
    private static final String ROUTER_SYSTEM = """
            MODE:ROUTE_FACT
            Bạn phân loại 1 fact (đoạn nguyên văn đã trích) vào đúng vị trí trong Business
            Intelligence Report, dựa trên NỘI DUNG THẬT — không đoán, không suy diễn ngoài
            những gì đoạn văn nói.

            1. "bucket" — bắt buộc, đúng 1 trong 7 giá trị:
               - MACRO_ECONOMIC: chỉ số/chính sách kinh tế vĩ mô hoặc TOÀN NGÀNH bảo hiểm,
                 KHÔNG gắn riêng 1 công ty (vd GDP, lạm phát, FDI, quy định áp cho cả ngành).
               - COMPETITIVE_THEME: một PATTERN/xu hướng liên quan từ 2 công ty trở lên hoặc
                 toàn thị trường (vd "chuyển từ mở rộng đại lý sang cạnh tranh nền tảng số") —
                 KHÔNG dùng cho tin riêng của 1 công ty.
               - SCHEDULED_EVENT: LỊCH/NGÀY một sự kiện SẮP diễn ra (công bố KQKD, họp báo...).
                 Không dùng cho sự kiện ĐÃ xảy ra rồi.
               - COMPANY_EVENT: tin THÔNG THƯỜNG của MỘT công ty cụ thể đã xảy ra (ra mắt sản
                 phẩm, nhân sự, CSR, kết quả tài chính, hợp tác, M&A...). Đây là bucket MẶC ĐỊNH
                 cho tin 1 công ty khi không rõ ràng thuộc bucket nào khác.
               - MARKET_SHARE_OR_AWARD: số liệu thị phần (vd % APE) hoặc giải thưởng/xếp hạng
                 MỘT công ty ĐÃ ĐẠT ĐƯỢC.
               - TECH_AI_SIGNAL: động thái/năng lực AI hoặc công nghệ — CỦA MỘT công ty cụ thể
                 (đầu tư, ra mắt, tuyển dụng liên quan AI) HOẶC số liệu ĐỊNH CỠ THỊ TRƯỜNG AI/
                 insurtech nói chung (không gắn 1 công ty — set kpi_label/kpi_value, để severity
                 null).
               - STRATEGIC_COMPARISON: câu SO SÁNH TRỰC TIẾP 2 công ty trở lên trong cùng 1 câu/
                 đoạn — không phải liệt kê nhiều công ty làm việc khác nhau.

            2. "subject_key" — tên công ty (giữ NGUYÊN VĂN như trong span, không tự dịch/rút
               gọn) khi bucket cần gắn 1 công ty (COMPANY_EVENT, SCHEDULED_EVENT,
               MARKET_SHARE_OR_AWARD, TECH_AI_SIGNAL công ty cụ thể); tên chủ đề ngắn khi
               COMPETITIVE_THEME; để trống nếu không áp dụng.

            3. "highlight_card_label" — CHỈ điền khi bucket=COMPANY_EVENT: 1 nhãn ngắn viết
               hoa có gạch dưới (vd PRODUCT_LAUNCH, BANCASSURANCE, BRAND_CAMPAIGN, HR_AWARD,
               HIRING_SIGNAL, STRATEGIC_POSITIONING, DIVESTMENT) mô tả ĐÚNG loại tin — tự đặt
               theo nội dung, không có danh sách cố định. NGOẠI LỆ BẮT BUỘC: nếu fact nói về
               kết quả/số liệu của CÔNG TY MẸ toàn cầu/khu vực (không tách riêng thị trường
               Việt Nam, hoặc rõ ràng gộp chung nhiều thị trường) — PHẢI dùng đúng nhãn
               "PARENT_GROUP" (đây là nhãn tồn tại để tránh đúng lỗi quy kết sai công ty: tin
               công ty mẹ toàn cầu bị đọc nhầm thành diễn biến tại Việt Nam).

            4. "severity"/"severity_trend" — CHỈ điền khi bucket=TECH_AI_SIGNAL và fact đánh
               giá MỨC ĐỘ ĐE DỌA cạnh tranh của 1 công ty cụ thể: severity = HIGH/MEDIUM/LOW;
               severity_trend = RISING/FALLING/STABLE nếu văn bản có nêu xu hướng thay đổi
               (vd "đang tăng tốc"), null nếu không rõ. Để cả 2 trống nếu đây là số liệu thị
               trường chung (không đánh giá theo công ty).

            5. "event_date_range_start"/"event_date_range_end" (định dạng YYYY-MM-DD) — CHỈ
               điền khi văn bản nêu rõ ngày/khoảng ngày CỤ THỂ cho 1 sự kiện (bucket
               SCHEDULED_EVENT hoặc COMPANY_EVENT). Cùng 1 ngày thì start=end. KHÔNG đoán ngày
               không có trong văn bản.

            6. "kpi_label"/"kpi_value" — CHỈ điền khi fact là 1 CHỈ SỐ độc lập, không gắn 1
               công ty cụ thể (vd kpi_label="GDP Growth", kpi_value="7.9%"; kpi_label=
               "Insurtech market size (2034)", kpi_value="USD 878.7 million"). kpi_value giữ
               nguyên định dạng như trong văn bản (không tự tách số/đơn vị).

            7. "highlight" — bắt buộc true/false: fact này có đủ quan trọng để lên trang Tóm
               tắt điều hành không (dựa trên mức độ ảnh hưởng thật tới vị thế cạnh tranh, KHÔNG
               phải dựa trên có nhiều con số/tên riêng hay không).

            Gọi tool "route" với đúng các trường trên — trường không áp dụng thì để trống/null,
            KHÔNG bịa giá trị.
            """;

    private static final java.util.List<LlmClient.LlmTool> ROUTER_TOOLS = buildRouterTools();

    private static java.util.List<LlmClient.LlmTool> buildRouterTools() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("bucket").put("type", "string").put("description",
                "MACRO_ECONOMIC|COMPETITIVE_THEME|SCHEDULED_EVENT|COMPANY_EVENT|"
                        + "MARKET_SHARE_OR_AWARD|TECH_AI_SIGNAL|STRATEGIC_COMPARISON");
        props.putObject("subject_key").put("type", "string");
        props.putObject("highlight_card_label").put("type", "string");
        props.putObject("severity").put("type", "string").put("description", "HIGH|MEDIUM|LOW");
        props.putObject("severity_trend").put("type", "string").put("description", "RISING|FALLING|STABLE");
        props.putObject("event_date_range_start").put("type", "string").put("description", "YYYY-MM-DD");
        props.putObject("event_date_range_end").put("type", "string").put("description", "YYYY-MM-DD");
        props.putObject("kpi_label").put("type", "string");
        props.putObject("kpi_value").put("type", "string");
        props.putObject("highlight").put("type", "boolean");
        schema.putArray("required").add("bucket").add("highlight");
        return java.util.List.of(new LlmClient.LlmTool("route",
                "Gán nhãn Router cho 1 fact — chủ đề, chủ thể, vị trí trên report.", schema));
    }

    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;
    private final LlmCallLogRepository callLog;
    private final LlmClient llm;   // WRITER (@Primary)
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean replayCache;
    private final PipelineRunStatusService progress;
    private final PipelineItemLogRepository itemLogs;
    private final com.marketradar.prompt.PromptService promptService;
    private final FactExtractionRunRepository extractionRuns;
    private final ExtractionPersistenceService extractionPersistence;
    private final ExtractionBackfillService backfill;

    public FactExtractionJob(ClassificationRepository classifications, EvidenceFactRepository facts,
                             LlmCallLogRepository callLog, LlmClient llm,
                             @Value("${marketradar.llm.replay-cache:true}") boolean replayCache,
                             PipelineRunStatusService progress, PipelineItemLogRepository itemLogs,
                             com.marketradar.prompt.PromptService promptService,
                             FactExtractionRunRepository extractionRuns,
                             ExtractionPersistenceService extractionPersistence,
                             ExtractionBackfillService backfill) {
        this.classifications = classifications;
        this.facts = facts;
        this.callLog = callLog;
        this.llm = llm;
        this.replayCache = replayCache;
        this.progress = progress;
        this.itemLogs = itemLogs;
        this.promptService = promptService;
        this.extractionRuns = extractionRuns;
        this.extractionPersistence = extractionPersistence;
        this.backfill = backfill;
        promptService.registerDefault(com.marketradar.prompt.PromptKey.EXTRACT, SYSTEM);
    }

    public String runOnce() {
        return runSelected(null);
    }

    /** Explicit, bounded reprocessing entry point. Invalid/current/incomplete IDs are rejected. */
    public String runTargeted(List<Long> requestedIds) {
        var selection = backfill.selectTargets(requestedIds);
        if (selection.acceptedIds().isEmpty()) {
            return "No eligible targeted docs. " + String.join("; ", selection.rejected()) + "\n";
        }
        String result = runSelected(Set.copyOf(selection.acceptedIds()));
        if (!selection.rejected().isEmpty()) {
            result += "Rejected targets: " + String.join("; ", selection.rejected()) + "\n";
        }
        return result;
    }

    private String runSelected(Set<Long> targetedIds) {
        if ("STUB".equals(llm.providerName())) {
            return "EXTRACT: LLM is STUB — not extracting facts (no fake heuristic facts). "
                    + "Configure a writer key (WRITER_API_KEY or ANTHROPIC_API_KEY) and run again.\n";
        }

        List<Classification> confirmed = classifications.findAllForDisplay().stream()
                .filter(c -> c.getStatus() == Classification.Status.CONFIRMED)
                .filter(c -> targetedIds == null || targetedIds.contains(c.getRawDoc().getId()))
                .toList();
        if (confirmed.isEmpty()) return "No CONFIRMED docs yet — run Classify first.\n";

        ExtractionVersioning.CurrentVersion version = ExtractionVersioning.current(
                llm.providerName(), promptService.body(com.marketradar.prompt.PromptKey.EXTRACT));

        long eligible = confirmed.stream()
                .map(Classification::getRawDoc)
                .filter(d -> extractionAssessment(d, version).eligible())
                .count();
        progress.startProgress("extract", (int) eligible);
        Long runLogId = progress.currentRunLogId("extract");

        StringBuilder sb = new StringBuilder();
        int docsDone = 0, docsSkipped = 0, factsSaved = 0, spansRejected = 0;

        for (Classification c : confirmed) {
            RawDoc doc = c.getRawDoc();
            var assessment = extractionAssessment(doc, version);
            if (!assessment.eligible()) {
                docsSkipped++;
                logItem(runLogId, doc, "SKIPPED_" + assessment.state(),
                        assessment.reasonCode() + " — " + assessment.reason());
                continue;
            }

            String signature = ExtractionVersioning.signature(version, doc);
            LongDocumentChunker.Plan chunkPlan = LongDocumentChunker.plan(doc.getRawText());
            FactExtractionRun extractionRun = extractionPersistence.begin(
                    doc, version, signature, chunkPlan);
            EnumMap<RejectionReason, Integer> rejections = new EnumMap<>(RejectionReason.class);
            LinkedHashMap<String, FactDraft> uniqueDrafts = new LinkedHashMap<>();
            int chunksCompleted = 0, factsProposed = 0, duplicateSpans = 0;
            boolean failed = false;

            for (LongDocumentChunker.Chunk chunk : chunkPlan.chunks()) {
                String raw;
                try {
                    raw = callWithCache(doc, version, chunk, chunkPlan.chunkCount());
                } catch (LlmException e) {
                    ExtractionRunMetrics metrics = metrics(chunkPlan, chunksCompleted,
                            factsProposed, rejections, duplicateSpans);
                    extractionPersistence.fail(extractionRun.getId(),
                            FactExtractionRun.Status.LLM_ERROR,
                            "chunk " + (chunk.index() + 1) + "/" + chunkPlan.chunkCount()
                                    + ": " + e.getMessage(), metrics);
                    log.error("EXTRACT LLM error doc#{} chunk {}/{}: {}", doc.getId(),
                            chunk.index() + 1, chunkPlan.chunkCount(), e.getMessage());
                    sb.append("doc#").append(doc.getId()).append(": LLM_ERROR at chunk ")
                      .append(chunk.index() + 1).append('/').append(chunkPlan.chunkCount())
                      .append(" — prior active facts preserved\n");
                    logItem(runLogId, doc, "LLM_ERROR",
                            "chunk " + (chunk.index() + 1) + "/" + chunkPlan.chunkCount());
                    progress.stepProgress("extract");
                    failed = true;
                    break;
                }

                ChunkParseResult parsed = parseAndGate(raw, doc);
                factsProposed += parsed.proposed();
                mergeRejections(rejections, parsed.rejections());
                if (parsed.schemaRejected()) {
                    ExtractionRunMetrics metrics = metrics(chunkPlan, chunksCompleted,
                            factsProposed, rejections, duplicateSpans);
                    extractionPersistence.fail(extractionRun.getId(),
                            FactExtractionRun.Status.SCHEMA_REJECTED,
                            "chunk " + (chunk.index() + 1) + "/" + chunkPlan.chunkCount()
                                    + " output was not valid JSON", metrics);
                    sb.append("doc#").append(doc.getId()).append(": SCHEMA_REJECTED at chunk ")
                      .append(chunk.index() + 1).append('/').append(chunkPlan.chunkCount())
                      .append(" — prior active facts preserved\n");
                    logItem(runLogId, doc, "SCHEMA_REJECTED",
                            "chunk " + (chunk.index() + 1) + "/" + chunkPlan.chunkCount());
                    progress.stepProgress("extract");
                    failed = true;
                    break;
                }
                chunksCompleted++;
                for (FactDraft draft : parsed.accepted()) {
                    String key = draft.factType().name() + "\u0000" + draft.span();
                    if (uniqueDrafts.putIfAbsent(key, draft) != null) {
                        duplicateSpans++;
                        rejections.merge(RejectionReason.OVERLAP_DUPLICATE, 1, Integer::sum);
                    }
                }
            }
            if (failed) continue;

            List<EvidenceFact> accepted = materializeFacts(new ArrayList<>(uniqueDrafts.values()), doc);
            ExtractionRunMetrics metrics = metrics(chunkPlan, chunksCompleted,
                    factsProposed, rejections, duplicateSpans);
            spansRejected += metrics.spansRejected();
            if (accepted.isEmpty()) {
                extractionPersistence.fail(extractionRun.getId(),
                        FactExtractionRun.Status.EMPTY_RESULT,
                        "all chunks completed but no accepted evidence facts", metrics);
                sb.append("doc#").append(doc.getId())
                  .append(": EMPTY_RESULT — prior active facts preserved\n");
                logItem(runLogId, doc, "EMPTY_RESULT",
                        "all chunks completed but no accepted facts; " + metrics.rejectionSummary());
                progress.stepProgress("extract");
                continue;
            }
            int superseded = extractionPersistence.succeed(
                    extractionRun.getId(), accepted, metrics);
            factsSaved += accepted.size();
            docsDone++;
            sb.append("doc#").append(doc.getId()).append(": +").append(accepted.size())
              .append(" fact across ").append(chunkPlan.chunkCount()).append(" chunk(s)")
              .append(metrics.spansRejected() > 0
                      ? " (" + metrics.spansRejected() + " rejected: " + metrics.rejectionSummary() + ")" : "")
              .append(superseded > 0 ? " · " + superseded + " prior fact(s) superseded" : "")
              .append(" — ").append(truncate(doc.getTitle(), 60)).append('\n');
            log.info("Extract doc#{} → +{} fact, {} chunk(s), {} rejected",
                    doc.getId(), accepted.size(), chunkPlan.chunkCount(), metrics.spansRejected());
            logItem(runLogId, doc, "OK", "+" + accepted.size() + " fact · "
                    + chunkPlan.chunkCount() + " chunk(s) · " + metrics.rejectionSummary());
            progress.stepProgress("extract");
        }

        sb.insert(0, "Extracted " + docsDone + " doc(s) (+" + factsSaved + " fact(s), "
                + spansRejected + " span(s) rejected — not verbatim), skipped "
                + docsSkipped + " (current/incomplete/duplicate). Provider: " + llm.providerName()
                + " · extraction version: " + version.pipelineVersion()
                + " · prompt: " + version.promptSha256().substring(0, 12) + "\n");
        return sb.toString();
    }

    private ExtractionContentDiagnostics.Assessment extractionAssessment(
            RawDoc doc, ExtractionVersioning.CurrentVersion version) {
        boolean current = extractionRuns.existsByRawDocAndExtractionSignatureAndStatusAndCurrentEditionTrue(
                doc, ExtractionVersioning.signature(version, doc),
                FactExtractionRun.Status.SUCCESS);
        return ExtractionContentDiagnostics.assessDetailed(doc, true, current);
    }

    // ---------- LLM call + replay cache (cùng cơ chế các job khác) ----------

    private String callWithCache(RawDoc doc, ExtractionVersioning.CurrentVersion version,
                                 LongDocumentChunker.Chunk chunk, int chunkCount)
            throws LlmException {
        String system = version.promptBody();
        String user = buildUserPrompt(doc, chunk, chunkCount);
        // Hash gồm providerName (fix 2026-07-15, đồng bộ với Interpreter/TopicClassifier):
        // không có nó, đổi model xong replay-cache vẫn trả response CŨ của model trước.
        String hash = sha256(llm.providerName() + "\n===\n" + system + "\n---\n" + user);
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(hash, 0);
            if (cached.isPresent()) return cached.get().getResponseText();
        }
        long t0 = System.currentTimeMillis();
        // temperature=null — trích xuất cần deterministic, không cần đa dạng
        String raw = llm.complete(system, user, null);
        callLog.save(new LlmCallLog("EXTRACT", llm.providerName(), hash, 0,
                raw, doc.getId(), System.currentTimeMillis() - t0));
        return raw;
    }

    private String buildUserPrompt(RawDoc doc, LongDocumentChunker.Chunk chunk, int chunkCount) {
        return "NGỮ CẢNH: thị trường=" + market(doc)
                + (doc.isHomeCompanyContent() ? " · CHỦ THỂ=CHÍNH TECHCOM LIFE (không phải đối thủ, tài liệu tự nộp)" : "")
                + " · nguồn=" + doc.getSource().getName()
                + " · chunk=" + (chunk.index() + 1) + "/" + chunkCount
                + " · ký tự nguồn=" + chunk.startInclusive() + ".." + chunk.endExclusive()
                + "\nTIÊU ĐỀ: " + (doc.getTitle() == null ? "(không tiêu đề)" : doc.getTitle())
                + "\n\nNỘI DUNG CHUNK (chuỗi con nguyên văn của toàn bài):\n" + chunk.text();
    }

    /**
     * VN = đối thủ trực tiếp (competitor watch); REGIONAL = nguồn khu vực/toàn cầu —
     * đọc như bài học/cảm hứng, không phải động thái đối thủ (feedback Hanh 2026-07-13).
     * Suy deterministic từ nguồn: ngôn ngữ vi hoặc host .vn → VN.
     */
    public static String market(RawDoc doc) {
        String host = doc.getSource().getAllowedHost();
        return "vi".equals(doc.getSource().getLanguage())
                || (host != null && host.endsWith(".vn")) ? "VN" : "REGIONAL";
    }

    // ---------- Parse + gate nguyên văn ----------

    private ChunkParseResult parseAndGate(String raw, RawDoc doc) {
        JsonNode arr;
        EnumMap<RejectionReason, Integer> rejections = new EnumMap<>(RejectionReason.class);
        String cleaned = raw.strip()
                .replaceAll("(?s)^```(?:json)?", "")
                .replaceAll("(?s)```$", "")
                .strip();
        try {
            arr = mapper.readTree(cleaned).get("facts");
        } catch (Exception first) {
            // Lưới an toàn: prompt đã nhắc escape dấu " trong span nhưng model thỉnh
            // thoảng vẫn quên (quan sát thật 2026-07-13) — thử lại sau khi JsonRepair sửa.
            try {
                arr = mapper.readTree(JsonRepair.repairUnescapedQuotes(cleaned)).get("facts");
            } catch (Exception second) {
                rejections.put(RejectionReason.MALFORMED_JSON, 1);
                return new ChunkParseResult(true, List.of(), 0, rejections);
            }
        }
        if (arr == null || !arr.isArray()) {
            rejections.put(RejectionReason.MALFORMED_JSON, 1);
            return new ChunkParseResult(true, List.of(), 0, rejections);
        }

        // Gate đối chiếu trên rawText ĐẦY ĐỦ (span phải nằm trong phần model được xem,
        // nhưng contains trên full text vẫn đúng và đơn giản hơn)
        String rawText = doc.getRawText();
        List<FactDraft> accepted = new ArrayList<>();
        int proposed = arr.size();

        for (JsonNode n : arr) {
            if (accepted.size() >= MAX_FACTS_PER_CHUNK) {
                rejections.merge(RejectionReason.CHUNK_FACT_LIMIT, 1, Integer::sum);
                continue;
            }
            String span = text(n, "span");
            if (span == null || span.isBlank()) {
                rejections.merge(RejectionReason.MISSING_SPAN, 1, Integer::sum);
                continue;
            }
            if (!rawText.contains(span)) {
                rejections.merge(RejectionReason.NOT_VERBATIM, 1, Integer::sum);
                log.warn("Extract doc#{}: span bị loại (không khớp nguyên văn): {}",
                        doc.getId(), truncate(span, 80));
                continue;
            }
            EvidenceFact.FactType type;
            try {
                type = EvidenceFact.FactType.valueOf(text(n, "fact_type"));
            } catch (Exception e) {
                rejections.merge(RejectionReason.INVALID_FACT_TYPE, 1, Integer::sum);
                continue;
            }

            // company/product: chỉ giữ nếu nguyên văn nằm TRONG span (Gate L1 đối chiếu sau này)
            String company = text(n, "company");
            if (company != null && !span.contains(company)) {
                company = null;
                rejections.merge(RejectionReason.UNGROUNDED_COMPANY, 1, Integer::sum);
            }
            String product = text(n, "product_name");
            if (product != null && !span.contains(product)) {
                product = null;
                rejections.merge(RejectionReason.UNGROUNDED_PRODUCT, 1, Integer::sum);
            }
            var occurred = parseGroundedDate(n, "event_date", span, rejections);
            var effective = parseGroundedDate(n, "effective_date", span, rejections);
            var expiry = parseGroundedDate(n, "expiry_date", span, rejections);
            var forecast = parseGroundedDate(n, "forecast_horizon", span, rejections);
            if (unsafeCriticalDate(effective) || unsafeCriticalDate(expiry)) {
                rejections.merge(RejectionReason.CRITICAL_TEMPORAL_DATE_REJECTED,
                        1, Integer::sum);
                continue;
            }
            accepted.add(new FactDraft(type, span, company, product, occurred.date(), effective.date(),
                    expiry.date(), forecast.date(), text(n, "summary_vi"), text(n, "summary_en")));
        }
        return new ChunkParseResult(false, List.copyOf(accepted), proposed, rejections);
    }

    private List<EvidenceFact> materializeFacts(List<FactDraft> drafts, RawDoc doc) {
        List<EvidenceFact> result = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            FactDraft d = drafts.get(i);
            EvidenceFact fact = new EvidenceFact(nextCode(i), doc, d.factType(),
                    d.span(), doc.getLanguage())
                    .category(categoryVi(d.factType())).categoryEn(categoryEn(d.factType()))
                    .company(d.company()).productName(d.productName())
                    .eventDate(d.occurredDate()).occurredDate(d.occurredDate())
                    .effectiveDate(d.effectiveDate()).expiryDate(d.expiryDate())
                    .forecastHorizon(d.forecastHorizon())
                    .summaryVi(d.summaryVi()).summaryEn(d.summaryEn());
            route(fact, doc);
            result.add(fact);
        }
        return result;
    }

    /**
     * Router — gọi 1 lần/fact qua tool-calling (cùng độ tin cậy hơn JSON tự do đã áp dụng cho
     * plan() của Deep Research và classifyVietnamRelevance). Fail closed về "không gán" (mọi
     * field null/false) khi lỗi — fact vẫn được lưu bình thường, chỉ là chưa có nhãn Router,
     * PeriodicalBiAdapter tự rơi về heuristic cũ cho các fact chưa route (không mất dữ liệu).
     */
    private void route(EvidenceFact fact, RawDoc doc) {
        String user = "NGỮ CẢNH: thị trường=" + market(doc)
                + (doc.isHomeCompanyContent() ? " · CHỦ THỂ=CHÍNH TECHCOM LIFE (không phải đối thủ, tài liệu tự nộp — "
                        + "nếu fact hợp lý cho so sánh chiến lược, đặt subject_key=\"Techcom Life\")" : "")
                + " · nguồn=" + doc.getSource().getName()
                + "\nTIÊU ĐỀ: " + (doc.getTitle() == null ? "(không tiêu đề)" : doc.getTitle())
                + "\nCÔNG TY (nếu có): " + (fact.getCompany() == null ? "(không rõ)" : fact.getCompany())
                + "\nSẢN PHẨM (nếu có): " + (fact.getProductName() == null ? "(không rõ)" : fact.getProductName())
                + "\nNGÀY SỰ KIỆN (nếu có): " + (fact.getEventDate() == null ? "(không rõ)" : fact.getEventDate())
                + "\nSPAN NGUYÊN VĂN:\n" + fact.getSpanText()
                + "\n---\nGọi tool route để gán nhãn.";
        try {
            String raw = routeCallWithCache(doc, user);
            JsonNode args = mapper.readTree(raw);
            applyRouting(fact, args);
        } catch (LlmException e) {
            log.warn("Router: lỗi LLM cho fact {} (doc#{}), để trống nhãn: {}",
                    fact.getFactCode(), doc.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Router: không parse được kết quả cho fact {} (doc#{}): {}",
                    fact.getFactCode(), doc.getId(), e.getMessage());
        }
    }

    private static final java.util.Set<String> VALID_ROUTER_BUCKETS = java.util.Set.of(
            "MACRO_ECONOMIC", "COMPETITIVE_THEME", "SCHEDULED_EVENT", "COMPANY_EVENT",
            "MARKET_SHARE_OR_AWARD", "TECH_AI_SIGNAL", "STRATEGIC_COMPARISON");
    private static final java.util.Set<String> VALID_SEVERITIES = java.util.Set.of("HIGH", "MEDIUM", "LOW");
    private static final java.util.Set<String> VALID_TRENDS = java.util.Set.of("RISING", "FALLING", "STABLE");

    static void applyRouting(EvidenceFact fact, JsonNode args) {
        String bucket = upperOrNull(args.path("bucket").asText(null));
        if (bucket != null && VALID_ROUTER_BUCKETS.contains(bucket)) fact.biBucket(bucket);
        String subjectKey = textOrNull(args, "subject_key");
        if (subjectKey != null) fact.subjectKey(subjectKey);
        String cardLabel = textOrNull(args, "highlight_card_label");
        if (cardLabel != null) fact.highlightCardLabel(cardLabel.strip().toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
        String severity = upperOrNull(args.path("severity").asText(null));
        if (severity != null && VALID_SEVERITIES.contains(severity)) fact.severity(severity);
        String trend = upperOrNull(args.path("severity_trend").asText(null));
        if (trend != null && VALID_TRENDS.contains(trend)) fact.severityTrend(trend);
        parseIsoDateSafe(textOrNull(args, "event_date_range_start")).ifPresent(fact::eventDateRangeStart);
        parseIsoDateSafe(textOrNull(args, "event_date_range_end")).ifPresent(fact::eventDateRangeEnd);
        String kpiLabel = textOrNull(args, "kpi_label");
        if (kpiLabel != null) fact.kpiLabel(kpiLabel);
        String kpiValue = textOrNull(args, "kpi_value");
        if (kpiValue != null) fact.kpiValue(kpiValue);
        fact.highlight(args.path("highlight").asBoolean(false));
    }

    private static String textOrNull(JsonNode args, String field) {
        String v = args.path(field).asText(null);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static java.util.Optional<LocalDate> parseIsoDateSafe(String s) {
        if (s == null) return java.util.Optional.empty();
        try { return java.util.Optional.of(LocalDate.parse(s.strip())); }
        catch (Exception e) { return java.util.Optional.empty(); }
    }

    /** Cùng cơ chế replay-cache với callWithCache(chunk) phía trên, khác purpose="ROUTE". */
    private String routeCallWithCache(RawDoc doc, String user) throws LlmException {
        String hash = sha256(llm.providerName() + "\nROUTE\n" + ROUTER_SYSTEM + "\n---\n" + user);
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(hash, 0);
            if (cached.isPresent()) return cached.get().getResponseText();
        }
        long t0 = System.currentTimeMillis();
        LlmClient.ToolChoice choice = llm.completeWithTools(ROUTER_SYSTEM, user, ROUTER_TOOLS, null);
        String responseJson = choice.arguments().toString();
        callLog.save(new LlmCallLog("ROUTE", llm.providerName(), hash, 0,
                responseJson, doc.getId(), System.currentTimeMillis() - t0));
        return responseJson;
    }

    private static EvidenceDateGrounding.Result parseGroundedDate(
            JsonNode n, String field, String span,
            EnumMap<RejectionReason, Integer> rejections) {
        String value = text(n, field);
        EvidenceDateGrounding.Result result = EvidenceDateGrounding.parseAndGround(value, span);
        if (result.status() == EvidenceDateGrounding.Status.INVALID_FORMAT) {
            rejections.merge(RejectionReason.INVALID_DATE, 1, Integer::sum);
        } else if (result.status() == EvidenceDateGrounding.Status.UNGROUNDED) {
            rejections.merge(RejectionReason.UNGROUNDED_DATE, 1, Integer::sum);
        }
        return result;
    }

    private static boolean unsafeCriticalDate(EvidenceDateGrounding.Result result) {
        return !EvidenceDateGrounding.criticalFieldAcceptable(result);
    }

    /**
     * F-003, F-004... — dựa trên MÃ LỚN NHẤT hiện có (F-001/F-002 là fact mẫu seed),
     * không dùng count() (fix 2026-07-13: count() vỡ khi có row bị xoá — xem
     * EvidenceFactRepository). offsetInDoc tránh trùng code giữa các fact CÙNG một
     * doc đang xử lý trong vòng lặp (chưa save nên count/max chưa đổi giữa các lần gọi).
     */
    private String nextCode(int offsetInDoc) {
        int max = facts.findAllFactCodes().stream()
                .mapToInt(FactExtractionJob::codeSuffix)
                .max().orElse(0);
        return String.format("F-%03d", max + 1 + offsetInDoc);
    }

    private static int codeSuffix(String code) {
        try { return Integer.parseInt(code.substring(2)); } catch (Exception e) { return 0; }
    }

    private static String categoryVi(EvidenceFact.FactType t) {
        return switch (t) {
            case EVENT -> "Sự kiện";
            case PRODUCT_LAUNCH -> "Ra mắt sản phẩm";
            case FEE_CHANGE -> "Thay đổi phí/quyền lợi";
            case REGULATION -> "Quy định";
            case METRIC -> "Số liệu";
        };
    }

    private static String categoryEn(EvidenceFact.FactType t) {
        return switch (t) {
            case EVENT -> "Event";
            case PRODUCT_LAUNCH -> "Product launch";
            case FEE_CHANGE -> "Fee/benefit change";
            case REGULATION -> "Regulation";
            case METRIC -> "Metric";
        };
    }

    private static ExtractionRunMetrics metrics(LongDocumentChunker.Plan plan,
                                                int chunksCompleted, int factsProposed,
                                                EnumMap<RejectionReason, Integer> rejections,
                                                int duplicateSpans) {
        int rejected = rejections.entrySet().stream()
                .filter(e -> e.getKey().countsAsRejectedSpan())
                .mapToInt(Map.Entry::getValue).sum();
        String summary = rejections.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getKey().name() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        return new ExtractionRunMetrics(plan.inputChars(), plan.chunkCount(), chunksCompleted,
                factsProposed, rejected, duplicateSpans, summary);
    }

    private static void mergeRejections(EnumMap<RejectionReason, Integer> target,
                                        Map<RejectionReason, Integer> additions) {
        additions.forEach((reason, count) -> target.merge(reason, count, Integer::sum));
    }

    private enum RejectionReason {
        MALFORMED_JSON(false), MISSING_SPAN(true), NOT_VERBATIM(true),
        INVALID_FACT_TYPE(true), CHUNK_FACT_LIMIT(true), OVERLAP_DUPLICATE(true),
        UNGROUNDED_COMPANY(false), UNGROUNDED_PRODUCT(false), INVALID_DATE(false),
        UNGROUNDED_DATE(false), CRITICAL_TEMPORAL_DATE_REJECTED(true);

        private final boolean rejectedSpan;
        RejectionReason(boolean rejectedSpan) { this.rejectedSpan = rejectedSpan; }
        boolean countsAsRejectedSpan() { return rejectedSpan; }
    }

    private record FactDraft(EvidenceFact.FactType factType, String span, String company,
                             String productName, LocalDate occurredDate,
                             LocalDate effectiveDate, LocalDate expiryDate,
                             LocalDate forecastHorizon, String summaryVi, String summaryEn) {}

    private record ChunkParseResult(boolean schemaRejected, List<FactDraft> accepted,
                                    int proposed,
                                    EnumMap<RejectionReason, Integer> rejections) {}

    private void logItem(Long runLogId, RawDoc doc, String status, String message) {
        if (runLogId == null) return;
        itemLogs.save(new PipelineItemLog(runLogId, PipelineItemLog.ItemType.RAW_DOC,
                String.valueOf(doc.getId()), doc.getTitle(), doc.getId(), status, message));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
