package com.marketradar.interpret;

import com.marketradar.domain.EvidenceFact;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evidence pack — đầu vào DUY NHẤT của Interpreter (bounded: model không thấy gì khác).
 * rawDocId = null với pack cấp report (exec summary).
 */
public record EvidencePack(Long rawDocId, List<EvidenceFact> facts) {

    public Map<String, EvidenceFact> byCode() {
        return facts.stream().collect(Collectors.toMap(EvidenceFact::getFactCode, Function.identity()));
    }

    public Set<String> codes() {
        return facts.stream().map(EvidenceFact::getFactCode).collect(Collectors.toSet());
    }

    /** Render pack thành văn bản cho prompt — span NGUYÊN VĂN, kèm trường cấu trúc.
     *  2026-08-03 (Router): hiện thêm nhãn Router đã gán (bucket/subjectKey/severity/kpi) khi
     *  có — để Interpreter chọn ĐÚNG hướng dẫn viết theo bucket (xem SYSTEM_DOC "HƯỚNG DẪN
     *  RIÊNG THEO BUCKET") thay vì tự đoán/tự phân loại lại. */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder("EVIDENCE PACK:\n");
        for (EvidenceFact f : facts) {
            sb.append("--- FACT ").append(f.getFactCode()).append(" ---\n");
            sb.append("loại: ").append(f.getFactType()).append('\n');
            if (f.getBiBucket() != null) sb.append("[ROUTER] bucket: ").append(f.getBiBucket()).append('\n');
            if (f.getSubjectKey() != null) sb.append("[ROUTER] chủ thể: ").append(f.getSubjectKey()).append('\n');
            if (f.getHighlightCardLabel() != null) sb.append("[ROUTER] nhãn thẻ: ").append(f.getHighlightCardLabel()).append('\n');
            if (f.getSeverity() != null) sb.append("[ROUTER] mức độ: ").append(f.getSeverity())
                    .append(f.getSeverityTrend() != null ? " (" + f.getSeverityTrend() + ")" : "").append('\n');
            if (f.getKpiLabel() != null) sb.append("[ROUTER] chỉ số: ").append(f.getKpiLabel())
                    .append(" = ").append(f.getKpiValue()).append('\n');
            if (f.getEventDate() != null) sb.append("ngày sự kiện: ").append(f.getEventDate()).append('\n');
            if (f.getCompany() != null) sb.append("công ty: ").append(f.getCompany()).append('\n');
            if (f.getProductName() != null) sb.append("sản phẩm: ").append(f.getProductName()).append('\n');
            sb.append("span nguyên văn (").append(f.getSpanLanguage()).append("): ")
              .append(f.getSpanText()).append('\n');
        }
        return sb.toString();
    }
}
