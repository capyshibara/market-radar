package com.marketradar.report.bi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.marketradar.product.ProductMarketScope;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Một nhận định đã tổng hợp, gắn với đúng 1 trong 7 bucket của Business Intelligence Report.
 * Là điểm chung giữa 2 nguồn dữ liệu khác hẳn nhau (report định kỳ đọc từ kho evidence đã
 * duyệt; Deep Research tổng hợp tức thời từ kết quả agent vừa tìm/đọc) — BiReportPageBuilder
 * và template chỉ biết đến BiFinding, không quan tâm nó tới từ đâu.
 *
 * @param bucket     một trong Bucket hằng số bên dưới
 * @param subjectKey khoá nhóm cho bucket cần gộp theo chủ thể (STRATEGIC_COMPARISON: tên cặp
 *                   so sánh; SCHEDULED_EVENT/COMPANY_EVENT: tên công ty/mốc) — null nếu bucket
 *                   không cần nhóm (MACRO_ECONOMIC, TECH_AI_SIGNAL dùng ngay 1 finding/thẻ)
 * @param textVi     nội dung nhận định, tiếng Việt
 * @param textEn     bản tiếng Anh — null khi nguồn gốc chưa có bản dịch; tầng trình bày sẽ công
 *                   khai việc thiếu bản dịch thay vì trộn textVi vào bản EN. Không bao giờ tự dịch
 *                   máy ở tầng trình bày, chỉ dùng bản đã có từ nguồn dữ liệu/LLM synthesis.
 * @param highlight  true nếu đủ quan trọng để lên trang Tóm tắt điều hành (EXEC)
 * @param severity      HIGH/MEDIUM/LOW — CHỈ áp dụng cho TECH_AI_SIGNAL: có giá trị nghĩa là đây
 *                      là 1 dòng AI Threat Map (đánh giá rủi ro theo công ty, trang riêng); null
 *                      nghĩa là đây là 1 số liệu định cỡ thị trường AI/insurtech (trang KPI
 *                      riêng). Null với mọi bucket khác.
 * @param metricPercent 0-100, CHỈ áp dụng cho MARKET_SHARE_OR_AWARD khi có số liệu thật để vẽ
 *                      thanh bar (vd thị phần APE %) — null thì trang đó tự chuyển sang trình bày
 *                      dạng bảng (MATRIX) thay vì bịa % để vẽ bar.
 * @param scope      VIETNAM hay INTERNATIONAL — suy ra DETERMINISTIC từ nguồn của bằng chứng
 *                   (ProductMarketScopeClassifier, KHÔNG phải LLM đoán). Hiển thị công khai trên
 *                   từng finding trong report thay vì ẩn/lọc cứng nguồn quốc tế — đúng tinh thần
 *                   CFO nêu (đừng để một tin quốc tế bị đọc nhầm thành đối thủ tại Việt Nam).
 * @param geography  nhãn địa lý cụ thể hơn khi INTERNATIONAL (vd "Japan", "Hong Kong"); "Vietnam"
 *                   khi VIETNAM; "Global / regional" khi không xác định được quốc gia cụ thể.
 * @param eventDateLabel nhãn thời gian tự do (vd "26-27/08/2026", "đầu tháng 8/2026") — CHỈ áp
 *                   dụng cho SCHEDULED_EVENT khi nguồn nêu rõ mốc thời gian cụ thể; null nghĩa là
 *                   chưa biết mốc chính xác (finding vẫn hợp lệ, chỉ không lên được trang "Lịch sự
 *                   kiện dự kiến" dạng bảng — vẫn hiện ở trang danh sách sự kiện chung). Hiện chỉ
 *                   Deep Research có thể điền trực tiếp; report định kỳ khôi phục ngày từ những
 *                   EvidenceFact đã được claim trích dẫn và chỉ giữ ngày nằm trong cửa sổ báo cáo.
 * @param highlightCardLabel nhãn thẻ Router tự đặt (PRODUCT_LAUNCH, BANCASSURANCE, PARENT_GROUP...)
 *                   — CHỈ áp dụng cho COMPANY_EVENT, null cho mọi bucket khác (xem
 *                   EvidenceFact#highlightCardLabel — Router gán, nguồn gốc từ file mẫu CFO).
 * @param severityTrend RISING/FALLING/STABLE — cờ phụ của severity (CHỈ TECH_AI_SIGNAL có
 *                   severity), null nếu nguồn không nêu rõ xu hướng.
 * @param kpiLabel/kpiValue CHỈ áp dụng khi finding là 1 CHỈ SỐ độc lập không gắn 1 công ty (vd
 *                   kpiLabel="GDP Growth", kpiValue="7.9%") — dùng cho MACRO_ECONOMIC và nhánh
 *                   định cỡ thị trường của TECH_AI_SIGNAL (severity null). null nếu finding là
 *                   câu văn tường thuật thông thường, không phải 1 KPI độc lập.
 * @param eventDateRangeStart/End ngày/khoảng ngày CÓ CẤU TRÚC (khác eventDateLabel tự do phía
 *                   trên) — CHỈ SCHEDULED_EVENT/COMPANY_EVENT khi Router xác định được ngày thật
 *                   từ EvidenceFact, dùng để dựng lưới lịch thật (file mẫu CFO có lưới tháng thật,
 *                   không phải bảng 2 cột).
 */
public record BiFinding(String bucket, String subjectKey, String textVi, String textEn,
                        boolean highlight, List<BiCitation> citations, String severity,
                        Integer metricPercent, ProductMarketScope scope, String geography,
                        String eventDateLabel, String highlightCardLabel, String severityTrend,
                        String kpiLabel, String kpiValue,
                        LocalDate eventDateRangeStart, LocalDate eventDateRangeEnd,
                        String evidenceGrade) {

    public BiFinding {
        textVi = cleanDisplayText(textVi);
        textEn = cleanDisplayText(textEn);
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (severity != null) {
            String normalized = severity.strip().toUpperCase(java.util.Locale.ROOT);
            severity = switch (normalized) {
                case "HIGH", "MEDIUM", "LOW" -> normalized;
                default -> null;
            };
        }
        if (severityTrend != null) {
            String normalized = severityTrend.strip().toUpperCase(java.util.Locale.ROOT);
            severityTrend = switch (normalized) {
                case "RISING", "FALLING", "STABLE" -> normalized;
                default -> null;
            };
        }
        if (metricPercent != null) metricPercent = Math.max(0, Math.min(100, metricPercent));
        scope = scope == null ? ProductMarketScope.INTERNATIONAL : scope;
        geography = geography == null || geography.isBlank() ? "Global / regional" : geography.strip();
        eventDateLabel = eventDateLabel == null || eventDateLabel.isBlank() ? null : eventDateLabel.strip();
        highlightCardLabel = highlightCardLabel == null || highlightCardLabel.isBlank() ? null : highlightCardLabel.strip();
        kpiLabel = kpiLabel == null || kpiLabel.isBlank() ? null : kpiLabel.strip();
        kpiValue = kpiValue == null || kpiValue.isBlank() ? null : kpiValue.strip();
        evidenceGrade = switch (evidenceGrade == null ? "" : evidenceGrade) {
            case "EDITORIAL_WATCH" -> "EDITORIAL_WATCH";
            case "REVIEWED_ANALYSIS" -> "REVIEWED_ANALYSIS";
            default -> "DECISION_GRADE";
        };
    }

    /** Compatibility constructor for existing adapters and persisted Deep Research JSON. */
    public BiFinding(String bucket, String subjectKey, String textVi, String textEn,
                     boolean highlight, List<BiCitation> citations, String severity,
                     Integer metricPercent, ProductMarketScope scope, String geography,
                     String eventDateLabel, String highlightCardLabel, String severityTrend,
                     String kpiLabel, String kpiValue,
                     LocalDate eventDateRangeStart, LocalDate eventDateRangeEnd) {
        this(bucket, subjectKey, textVi, textEn, highlight, citations, severity, metricPercent,
                scope, geography, eventDateLabel, highlightCardLabel, severityTrend,
                kpiLabel, kpiValue, eventDateRangeStart, eventDateRangeEnd, "DECISION_GRADE");
    }

    /** Convenience constructor for the common case of no severity/metric/event date/Router labels. */
    public BiFinding(String bucket, String subjectKey, String textVi, String textEn,
                     boolean highlight, List<BiCitation> citations,
                     ProductMarketScope scope, String geography) {
        this(bucket, subjectKey, textVi, textEn, highlight, citations, null, null, scope, geography,
                null, null, null, null, null, null, null, "DECISION_GRADE");
    }

    /** Convenience constructor for VI-only content (no English translation available yet). */
    public BiFinding(String bucket, String subjectKey, String textVi,
                     boolean highlight, List<BiCitation> citations,
                     ProductMarketScope scope, String geography) {
        this(bucket, subjectKey, textVi, null, highlight, citations, null, null, scope, geography,
                null, null, null, null, null, null, null, "DECISION_GRADE");
    }

    /** The finding text in the requested language. A missing translation is disclosed instead
     *  of silently leaking a full Vietnamese sentence into the English edition (or vice versa). */
    public String text(boolean vi) {
        String selected;
        if (vi) {
            selected = textVi != null && !textVi.isBlank()
                    ? textVi : "Chưa có bản diễn đạt tiếng Việt cho nhận định đã xác minh này.";
        } else {
            selected = textEn != null && !textEn.isBlank()
                    ? textEn : "An English rendering is not yet available for this verified finding.";
        }
        return localizeEditorialTerms(selected, vi);
    }

    /** Reader-facing role inferred only from the wording already verified by the two gates.
     *  This is presentation metadata, never a new factual claim. */
    public String roleLabel(boolean vi) {
        String value = text(vi).strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("implication") || value.startsWith("hệ quả")
                || value.startsWith("hàm ý")) {
            return vi ? "HÀM Ý QUẢN TRỊ" : "MANAGEMENT IMPLICATION";
        }
        if (value.startsWith("caveat") || value.startsWith("lưu ý")
                || value.startsWith("giới hạn")) {
            return vi ? "GIỚI HẠN BẰNG CHỨNG" : "EVIDENCE BOUNDARY";
        }
        if (value.startsWith("pattern") || value.startsWith("mô hình")
                || value.startsWith("subject observation") || value.startsWith("phân tích chủ đề")) {
            return vi ? "MÔ HÌNH / QUAN SÁT" : "PATTERN / OBSERVATION";
        }
        return vi ? "NHẬN ĐỊNH ĐÃ XÁC MINH" : "VERIFIED FINDING";
    }

    /** Exact structured event date when available. The report never invents a calendar date. */
    public String activityDateLabel(boolean vi) {
        if (eventDateRangeStart == null) return vi ? "Chưa có ngày cấu trúc" : "No structured date";
        DateTimeFormatter one = DateTimeFormatter.ofPattern(vi ? "dd/MM/yyyy" : "dd MMM yyyy",
                vi ? Locale.forLanguageTag("vi") : Locale.ENGLISH);
        if (eventDateRangeEnd == null || eventDateRangeEnd.equals(eventDateRangeStart)) {
            return eventDateRangeStart.format(one);
        }
        return eventDateRangeStart.format(one) + " – " + eventDateRangeEnd.format(one);
    }

    private static String cleanDisplayText(String value) {
        if (value == null) return null;
        String cleaned = value.strip()
                // PDF fonts/renderers do not map non-breaking and figure dashes reliably;
                // normalize every Unicode dash variant to the portable ASCII hyphen.
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                // Gate-safe storage used '#' for several dash variants. Restore a normal ASCII
                // hyphen only when it sits inside a token (year#on#year, NĐ#CP, digital#first).
                .replaceAll("(?<=[\\p{L}\\p{N}])#(?=[\\p{L}\\p{N}])", "-")
                // These are prompt scaffolds, not intelligence. Keep the actual sentence and
                // remove only exact, known suffixes that leaked from older Analyst responses.
                .replaceAll("(?iu)\\s*;?\\s*\\(Pattern:\\s*two\\s*facts\\)\\s*", " ")
                .replaceAll("(?iu)\\s*;?\\s*\\(Observation\\s*→\\s*Implication\\s*→\\s*Caveat\\)\\s*", " ")
                .replaceAll("\\s+([,.;:])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .strip();
        return cleaned;
    }

    /** Older Analyst editions occasionally retained English workflow labels in otherwise
     * Vietnamese prose. Localize those presentation terms deterministically at render time;
     * this changes no entity, number, date, attribution or substantive claim. */
    private static String localizeEditorialTerms(String value, boolean vi) {
        if (!vi || value == null || value.isBlank()) return value;
        return value
                .replaceFirst("(?iu)^Implication(?: for management)?\\s*:\\s*", "Hàm ý quản trị: ")
                .replaceFirst("(?iu)^Caveat\\s*:\\s*", "Giới hạn bằng chứng: ")
                .replaceFirst("(?iu)^Pattern\\s*:\\s*", "Mẫu hình: ")
                .replaceFirst("(?iu)^Subject observation\\s*:\\s*", "Quan sát chủ đề: ")
                .replaceAll("(?iu)\\bfacts?\\b", "dữ kiện")
                .replaceAll("(?iu)\\bevidence\\b", "bằng chứng")
                .replaceAll("(?iu)\\bverifier\\b", "bộ kiểm chứng")
                .replaceAll("(?iu)\\bgates?\\b", "cổng kiểm định");
    }

    // 2026-08-03: @JsonIgnore BẮT BUỘC — Jackson tự coi mọi isXxx() là 1 thuộc tính JSON
    // ("vietnamMarket") dù không phải field thật của record. Ghi thì vô hại (dư 1 trường), nhưng
    // đọc lại bằng ObjectMapper mặc định (FAIL_ON_UNKNOWN_PROPERTIES=true) thì vỡ ngay vì record
    // không có tham số "vietnamMarket" nào để nhận — đây chính là nguyên nhân lỗi
    // "Không đọc được nội dung đã lưu" của Deep Research (contentJson ghi thành công, đọc lại vỡ).
    @JsonIgnore
    public boolean isVietnamMarket() { return scope == ProductMarketScope.VIETNAM; }

    /** Short badge label for the report — "Việt Nam"/"Vietnam" or the specific international
     *  geography (falls back to "Global / regional" when no country could be determined). */
    public String marketLabel(boolean vi) {
        if (isVietnamMarket()) return vi ? "Việt Nam" : "Vietnam";
        if (!vi) return geography;
        return switch (geography) {
            case "Hong Kong" -> "Hồng Kông";
            case "Singapore" -> "Singapore";
            case "Taiwan" -> "Đài Loan";
            case "South Korea" -> "Hàn Quốc";
            case "Japan" -> "Nhật Bản";
            case "China" -> "Trung Quốc";
            case "Indonesia" -> "Indonesia";
            case "Malaysia" -> "Malaysia";
            case "Philippines" -> "Philippines";
            case "Thailand" -> "Thái Lan";
            case "Global / regional" -> "Toàn cầu / khu vực";
            default -> geography;
        };
    }

    public static final String MACRO_ECONOMIC = "MACRO_ECONOMIC";
    public static final String COMPETITIVE_THEME = "COMPETITIVE_THEME";
    public static final String SCHEDULED_EVENT = "SCHEDULED_EVENT";
    public static final String COMPANY_EVENT = "COMPANY_EVENT";
    public static final String MARKET_SHARE_OR_AWARD = "MARKET_SHARE_OR_AWARD";
    public static final String TECH_AI_SIGNAL = "TECH_AI_SIGNAL";
    public static final String STRATEGIC_COMPARISON = "STRATEGIC_COMPARISON";
    /** 2026-08-03 (file mẫu CFO — slide "DEEP DIVE"): bài phân tích dài, tổng hợp NHIỀU fact
     *  (có thể khác bucket nhau) thành 1 luận điểm — dành cho vài chủ đề Connector đề xuất là
     *  đủ quan trọng để đào sâu, không phải mọi fact đều có bucket này. */
    public static final String DEEP_DIVE = "DEEP_DIVE";
}
