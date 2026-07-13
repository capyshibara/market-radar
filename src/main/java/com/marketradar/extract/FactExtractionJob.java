package com.marketradar.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.marketradar.domain.Classification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.LlmCallLog;
import com.marketradar.domain.RawDoc;
import com.marketradar.llm.JsonRepair;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.LlmException;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.LlmCallLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
 * không nhìn thấy tiến độ) — mỗi doc tự commit qua repository save, chạy lại
 * an toàn nhờ guard existsByRawDoc.
 *
 * Dùng WRITER client (@Primary, Claude): chất lượng chọn span quyết định chất
 * lượng mọi thứ phía sau, volume thấp (chỉ doc CONFIRMED — cỡ chục call/vòng).
 */
@Service
public class FactExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(FactExtractionJob.class);
    private static final int MAX_INPUT_CHARS = 6000;
    private static final int MAX_FACTS_PER_DOC = 5;

    private static final String SYSTEM = """
            MODE:EXTRACT_FACTS — Bạn nhận TIÊU ĐỀ + NỘI DUNG một tài liệu tin tức ngành
            bảo hiểm nhân thọ. Nhiệm vụ: trích tối đa %d ĐOẠN NGUYÊN VĂN (span) chứa
            sự kiện/sản phẩm/quy định/số liệu đáng đưa vào evidence store.

            RÀNG BUỘC TUYỆT ĐỐI:
            - "span" phải là chuỗi CHÉP NGUYÊN VĂN từ tài liệu, KHÔNG sửa một ký tự nào
              (kể cả dấu câu, khoảng trắng). Hệ thống sẽ đối chiếu exact-match và loại
              mọi span không khớp.
            - QUAN TRỌNG (JSON hợp lệ): nếu văn bản gốc trong span có dấu ngoặc kép "
              (vd thuật ngữ được định nghĩa như "NFYP", "HĐBH"), PHẢI escape thành \"
              trong JSON string — dấu " chưa escape sẽ làm hỏng cấu trúc JSON và toàn
              bộ output bị loại. Ví dụ ĐÚNG: "span":"...phí bảo hiểm (\"NFYP\") bao gồm..."
            - KHÔNG bịa thông tin không có trong tài liệu. Tài liệu không có gì đáng
              trích → trả {"facts": []}.
            - company / product_name: chỉ điền nếu tên đó nằm NGUYÊN VĂN trong span.
            - event_date: chỉ điền nếu ngày ghi rõ trong tài liệu, dạng YYYY-MM-DD.
            - summary_vi / summary_en: MỘT câu làm TIÊU ĐỀ digest kinh doanh — người đọc
              lướt qua phải nắm được ngay điều gì xảy ra và vì sao đáng chú ý
              (chủ thể + hành động + con số/chi tiết đắt nhất). KHÔNG viết kiểu
              "Bài viết nói về…", không lặp nguyên văn span.
            - Nếu phần NGỮ CẢNH ghi thị trường là REGIONAL (nguồn ngoài Việt Nam —
              không phải đối thủ trực tiếp): viết summary theo hướng BÀI HỌC/GỢI Ý
              cho công ty bảo hiểm nhân thọ Việt Nam (ý tưởng sản phẩm, quy trình,
              mô hình vận hành có thể tham khảo), không viết như tin đối thủ.

            Trả về DUY NHẤT JSON đúng dạng (không markdown, không giải thích):
            {"facts":[{"span":"...","fact_type":"EVENT|PRODUCT_LAUNCH|FEE_CHANGE|REGULATION|METRIC",
            "company":null,"product_name":null,"event_date":null,
            "summary_vi":"...","summary_en":"..."}]}
            """.formatted(MAX_FACTS_PER_DOC);

    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;
    private final LlmCallLogRepository callLog;
    private final LlmClient llm;   // WRITER (@Primary)
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean replayCache;

    public FactExtractionJob(ClassificationRepository classifications, EvidenceFactRepository facts,
                             LlmCallLogRepository callLog, LlmClient llm,
                             @Value("${marketradar.llm.replay-cache:true}") boolean replayCache) {
        this.classifications = classifications;
        this.facts = facts;
        this.callLog = callLog;
        this.llm = llm;
        this.replayCache = replayCache;
    }

    public String runOnce() {
        if ("STUB".equals(llm.providerName())) {
            return "EXTRACT: LLM is STUB — not extracting facts (no fake heuristic facts). "
                    + "Configure a writer key (WRITER_API_KEY or ANTHROPIC_API_KEY) and run again.\n";
        }

        List<Classification> confirmed = classifications.findAllForDisplay().stream()
                .filter(c -> c.getStatus() == Classification.Status.CONFIRMED)
                .toList();
        if (confirmed.isEmpty()) return "No CONFIRMED docs yet — run Classify first.\n";

        StringBuilder sb = new StringBuilder();
        int docsDone = 0, docsSkipped = 0, factsSaved = 0, spansRejected = 0;

        for (Classification c : confirmed) {
            RawDoc doc = c.getRawDoc();
            if (doc.getDuplicateOfId() != null) { docsSkipped++; continue; }
            if (doc.getRawText() == null || doc.getRawText().isBlank()) { docsSkipped++; continue; }
            if (facts.existsByRawDoc(doc)) { docsSkipped++; continue; }

            String raw;
            try {
                raw = callWithCache(doc);
            } catch (LlmException e) {
                log.error("EXTRACT lỗi LLM doc#{}: {}", doc.getId(), e.getMessage());
                sb.append("doc#").append(doc.getId()).append(": LLM_ERROR — ")
                  .append(e.getMessage()).append('\n');
                continue;
            }

            ParseResult pr = parseAndGate(raw, doc);
            spansRejected += pr.rejected;
            if (pr.schemaRejected) {
                sb.append("doc#").append(doc.getId()).append(": SCHEMA_REJECTED (output was not valid JSON)\n");
                continue;
            }
            for (EvidenceFact f : pr.accepted) {
                facts.save(f);
                factsSaved++;
            }
            docsDone++;
            sb.append("doc#").append(doc.getId()).append(": +").append(pr.accepted.size())
              .append(" fact").append(pr.rejected > 0 ? " (" + pr.rejected + " span rejected — not verbatim)" : "")
              .append(" — ").append(truncate(doc.getTitle(), 60)).append('\n');
            log.info("Extract doc#{} → +{} fact, {} span rejected", doc.getId(), pr.accepted.size(), pr.rejected);
        }

        sb.insert(0, "Extracted " + docsDone + " doc(s) (+" + factsSaved + " fact(s), "
                + spansRejected + " span(s) rejected — not verbatim), skipped "
                + docsSkipped + " (duplicate/already extracted/no text). Provider: " + llm.providerName() + "\n");
        return sb.toString();
    }

    // ---------- LLM call + replay cache (cùng cơ chế các job khác) ----------

    private String callWithCache(RawDoc doc) throws LlmException {
        String user = buildUserPrompt(doc);
        String hash = sha256(SYSTEM + "\n---\n" + user);
        if (replayCache) {
            var cached = callLog.findFirstByPromptSha256AndSampleIndexOrderByCreatedAtDesc(hash, 0);
            if (cached.isPresent()) return cached.get().getResponseText();
        }
        long t0 = System.currentTimeMillis();
        // temperature=null — trích xuất cần deterministic, không cần đa dạng
        String raw = llm.complete(SYSTEM, user, null);
        callLog.save(new LlmCallLog("EXTRACT", llm.providerName(), hash, 0,
                raw, doc.getId(), System.currentTimeMillis() - t0));
        return raw;
    }

    private String buildUserPrompt(RawDoc doc) {
        String text = doc.getRawText();
        if (text.length() > MAX_INPUT_CHARS) text = text.substring(0, MAX_INPUT_CHARS);
        return "NGỮ CẢNH: thị trường=" + market(doc)
                + " · nguồn=" + doc.getSource().getName()
                + "\nTIÊU ĐỀ: " + (doc.getTitle() == null ? "(không tiêu đề)" : doc.getTitle())
                + "\n\nNỘI DUNG:\n" + text;
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

    private record ParseResult(boolean schemaRejected, List<EvidenceFact> accepted, int rejected) {}

    private ParseResult parseAndGate(String raw, RawDoc doc) {
        JsonNode arr;
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
                return new ParseResult(true, List.of(), 0);
            }
        }
        if (arr == null || !arr.isArray()) return new ParseResult(true, List.of(), 0);

        // Gate đối chiếu trên rawText ĐẦY ĐỦ (span phải nằm trong phần model được xem,
        // nhưng contains trên full text vẫn đúng và đơn giản hơn)
        String rawText = doc.getRawText();
        List<EvidenceFact> accepted = new ArrayList<>();
        int rejected = 0;

        for (JsonNode n : arr) {
            if (accepted.size() >= MAX_FACTS_PER_DOC) break;
            String span = text(n, "span");
            if (span == null || span.isBlank() || !rawText.contains(span)) {
                rejected++;
                log.warn("Extract doc#{}: span bị loại (không khớp nguyên văn): {}",
                        doc.getId(), truncate(span, 80));
                continue;
            }
            EvidenceFact.FactType type;
            try {
                type = EvidenceFact.FactType.valueOf(text(n, "fact_type"));
            } catch (Exception e) { rejected++; continue; } // fact_type ngoài enum → loại (không đoán)

            EvidenceFact f = new EvidenceFact(nextCode(accepted.size()), doc, type,
                    span, doc.getLanguage());
            // Nhãn category hiển thị trên report suy từ factType (deterministic,
            // không hỏi model) — thiếu thì report in "NULL".
            f.category(categoryVi(type)).categoryEn(categoryEn(type));

            // company/product: chỉ giữ nếu nguyên văn nằm TRONG span (Gate L1 đối chiếu sau này)
            String company = text(n, "company");
            if (company != null && span.contains(company)) f.company(company);
            String product = text(n, "product_name");
            if (product != null && span.contains(product)) f.productName(product);
            String date = text(n, "event_date");
            if (date != null) {
                try { f.eventDate(LocalDate.parse(date)); } catch (Exception ignored) {} // ngày hỏng → bỏ trường, giữ fact
            }
            String sv = text(n, "summary_vi");
            if (sv != null) f.summaryVi(sv);
            String se = text(n, "summary_en");
            if (se != null) f.summaryEn(se);
            accepted.add(f);
        }
        return new ParseResult(false, accepted, rejected);
    }

    /** F-003, F-004... — tuần tự theo count hiện có (F-001/F-002 là fact mẫu seed). */
    private String nextCode(int offsetInDoc) {
        return String.format("F-%03d", facts.count() + 1 + offsetInDoc);
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
