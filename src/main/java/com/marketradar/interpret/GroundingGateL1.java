package com.marketradar.interpret;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim.GateStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate lớp 1 — 100% CODE, KHÔNG AI. "Exact-match sau chuẩn hoá":
 *
 *  (1) Citation: câu phải có >=1 fact_code, mọi code phải tồn tại trong pack.
 *  (2) Tên riêng: mọi chuỗi trong ngoặc kép ("…" '…' "…" 「…」 『…』 «…»)
 *      phải xuất hiện VERBATIM (đúng script gốc) trong spanText/productName/company
 *      của các fact được trích dẫn. Đây là hợp đồng với Interpreter: tên sản phẩm/
 *      công ty bắt buộc đặt trong ngoặc kép, giữ nguyên văn.
 *  (3) Ngày: mọi ngày trong câu (dd/MM/yyyy · "ngày d tháng m năm yyyy" ·
 *      yyyy年M月d日 · yyyy-MM-dd) parse về ISO, phải khớp một ngày trong evidence
 *      (span hoặc eventDate của fact).
 *  (4) Số: mọi token số trong câu (SAU khi đã gỡ các ngày ở bước 3 khỏi văn bản)
 *      phải khớp một số trong evidence, so bằng TẬP DẠNG CHUẨN: với "1,75" sinh
 *      ứng viên {175, 1.75} (dấu phẩy có thể là thập phân vi hoặc phân tách nghìn),
 *      hai phía khớp khi giao tập khác rỗng VÀ cùng cờ phần trăm.
 *
 * GIẢ ĐỊNH ghi rõ: (a) dd/MM/yyyy theo quy ước VN (không hỗ trợ MM/dd);
 * (b) evidence phía đối chiếu KHÔNG gỡ ngày trước khi bóc số → tập số evidence
 * rộng hơn (giảm false-fail, chấp nhận một ít false-pass ở lớp này — lớp 2
 * entailment và HITL bọc phía sau); (c) số viết bằng chữ Hán (一千万) chưa hỗ trợ —
 * span regulator zh dùng chữ số Ả Rập cho phí/lãi suất là chủ yếu.
 */
@Service
public class GroundingGateL1 {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- Kết quả ----
    public record GateResult(GateStatus status, String detailJson) {}

    // ---- Regex ngày ----
    private static final Pattern D_ZH  = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern D_VI  = Pattern.compile("ngày\\s+(\\d{1,2})\\s+tháng\\s+(\\d{1,2})\\s+năm\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern D_SL  = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern D_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    // ---- Regex số (chạy SAU khi gỡ ngày): 1.234,56 / 10,000 / 2.0 / 25000, kèm % tuỳ chọn ----
    private static final Pattern NUM = Pattern.compile("(\\d+(?:[.,]\\d+)*)\\s*(%|％)?");

    // ---- Cặp ngoặc kép nhận diện tên riêng ----
    private static final String[][] QUOTES = {
            {"\"", "\""}, {"“", "”"}, {"'", "'"}, {"‘", "’"},
            {"「", "」"}, {"『", "』"}, {"«", "»"},
    };

    /*
     * A quoted demonstrative is not a proper name. Writers sometimes quote a
     * referring phrase for emphasis (for example, "thủ tục này" / "this
     * procedure"). Treating those phrases as names creates a false L1 failure
     * while adding no grounding protection. This is deliberately a short,
     * closed list of generic references: unknown quoted text still has to be
     * present verbatim, so company, product and regulation names remain gated.
     */
    private static final Pattern GENERIC_VI_QUOTED_REFERENCE = Pattern.compile(
            "(?iu)^(?:thủ tục|quy trình|quy định|văn bản|sản phẩm|hợp đồng|chính sách|"
                    + "thay đổi|điều chỉnh|yêu cầu|biện pháp|nội dung|thông tin|khoản phí|"
                    + "mức phí|quy tắc|điều khoản)\\s+(?:này|đó|ấy|trên|dưới)$");
    private static final Pattern GENERIC_EN_QUOTED_REFERENCE = Pattern.compile(
            "(?iu)^(?:this|that|these|those|the)\\s+(?:procedure|process|rule|regulation|"
                    + "document|product|policy|change|adjustment|requirement|measure|content|"
                    + "information|fee|premium|term|condition)s?$");

    public GateResult check(String claimText, List<String> citedCodes, List<EvidenceFact> citedFacts,
                            Set<String> allCodesInPack) {
        ObjectNode detail = mapper.createObjectNode();
        List<GateStatus> failures = new ArrayList<>();

        // (1) Citation
        ArrayNode citedNode = detail.putArray("citedCodes");
        citedCodes.forEach(citedNode::add);
        if (citedCodes.isEmpty()) {
            failures.add(GateStatus.FAIL_NO_CITATION);
        } else {
            List<String> unknown = citedCodes.stream()
                    .filter(c -> !allCodesInPack.contains(c)).toList();
            if (!unknown.isEmpty()) {
                failures.add(GateStatus.FAIL_UNKNOWN_FACT_CODE);
                ArrayNode u = detail.putArray("unknownCodes");
                unknown.forEach(u::add);
            }
        }

        // Corpus evidence từ các fact resolve được
        StringBuilder corpus = new StringBuilder();
        Set<LocalDate> evDates = new HashSet<>();
        for (EvidenceFact f : citedFacts) {
            corpus.append(f.getSpanText()).append('\n');
            if (f.getProductName() != null) corpus.append(f.getProductName()).append('\n');
            if (f.getCompany() != null) corpus.append(f.getCompany()).append('\n');
            if (f.getEventDate() != null) evDates.add(f.getEventDate());
        }
        String evidenceText = corpus.toString();
        evDates.addAll(extractDates(evidenceText));

        // (2) Tên trong ngoặc kép — verbatim theo script gốc. Generic
        // demonstratives are retained in the audit detail but are not names.
        List<String> quoted = extractQuoted(claimText);
        ArrayNode namesNode = detail.putArray("quotedNames");
        ArrayNode ignoredReferencesNode = detail.putArray("ignoredQuotedReferences");
        List<String> missingNames = new ArrayList<>();
        for (String q : quoted) {
            if (isGenericQuotedReference(q)) {
                ignoredReferencesNode.add(q);
                continue;
            }
            boolean found = evidenceText.contains(q);
            ObjectNode n = namesNode.addObject();
            n.put("name", q); n.put("foundVerbatim", found);
            if (!found) missingNames.add(q);
        }
        if (!missingNames.isEmpty()) failures.add(GateStatus.FAIL_NAME_NOT_IN_EVIDENCE);

        // (3) Ngày trong claim
        Set<LocalDate> claimDates = extractDates(claimText);
        ArrayNode datesNode = detail.putArray("claimDates");
        boolean dateFail = false;
        for (LocalDate d : claimDates) {
            boolean ok = evDates.contains(d);
            ObjectNode n = datesNode.addObject();
            n.put("date", d.toString()); n.put("inEvidence", ok);
            if (!ok) dateFail = true;
        }
        if (dateFail) failures.add(GateStatus.FAIL_DATE_NOT_IN_EVIDENCE);

        // (4) Số trong claim (sau khi gỡ ngày khỏi text claim)
        List<NumToken> claimNums = extractNumbers(stripDates(claimText));
        List<NumToken> evNums = extractNumbers(evidenceText); // KHÔNG gỡ ngày phía evidence (giả định b)
        ArrayNode numsNode = detail.putArray("claimNumbers");
        boolean numFail = false;
        for (NumToken t : claimNums) {
            boolean ok = evNums.stream().anyMatch(e -> e.matches(t));
            ObjectNode n = numsNode.addObject();
            n.put("raw", t.raw); n.put("percent", t.percent); n.put("inEvidence", ok);
            if (!ok) numFail = true;
        }
        if (numFail) failures.add(GateStatus.FAIL_NUMBER_NOT_IN_EVIDENCE);

        GateStatus status = failures.stream().min(Comparator.comparingInt(Enum::ordinal))
                .orElse(GateStatus.PASS);
        detail.put("status", status.name());
        return new GateResult(status, detail.toString());
    }

    /**
     * Batch 7 (i18n bilingual report): mỗi câu AI sinh giờ có CẢ textVi và textEn
     * trong cùng một lần gọi LLM. Gate L1 chạy exact-match trên CẢ HAI bản (tên/ngày/số
     * phải verbatim-đúng-script trong evidence ở từng ngôn ngữ) — dịch sai lệch một con số
     * hay đánh rơi tên trong ngoặc kép ở bản tiếng Anh phải bị chặn giống hệt bản tiếng Việt,
     * không được "free pass" chỉ vì bản kia PASS. Lỗi nặng hơn (ordinal nhỏ hơn) thắng.
     */
    public GateResult checkBilingual(String textVi, String textEn, List<String> citedCodes,
                                     List<EvidenceFact> citedFacts, Set<String> allCodesInPack) {
        GateResult viResult = check(textVi, citedCodes, citedFacts, allCodesInPack);
        GateResult enResult = check(textEn, citedCodes, citedFacts, allCodesInPack);
        GateStatus worst = viResult.status().ordinal() <= enResult.status().ordinal()
                ? viResult.status() : enResult.status();
        ObjectNode combined = mapper.createObjectNode();
        combined.put("status", worst.name());
        try {
            combined.set("vi", mapper.readTree(viResult.detailJson()));
            combined.set("en", mapper.readTree(enResult.detailJson()));
        } catch (Exception ignored) { /* detailJson luôn tự sinh hợp lệ ở trên — không xảy ra */ }
        return new GateResult(worst, combined.toString());
    }

    // ================= helpers =================

    static List<String> extractQuoted(String text) {
        List<String> out = new ArrayList<>();
        for (String[] pair : QUOTES) {
            Pattern p = Pattern.compile(Pattern.quote(pair[0]) + "([^" +
                    Pattern.quote(pair[0]) + Pattern.quote(pair[1]) + "]{2,120})" + Pattern.quote(pair[1]));
            Matcher m = p.matcher(text);
            while (m.find()) out.add(m.group(1).strip());
        }
        return out;
    }

    static boolean isGenericQuotedReference(String quoted) {
        if (quoted == null) return false;
        String text = quoted.strip().replaceAll("\\s+", " ");
        return GENERIC_VI_QUOTED_REFERENCE.matcher(text).matches()
                || GENERIC_EN_QUOTED_REFERENCE.matcher(text).matches();
    }

    static Set<LocalDate> extractDates(String text) {
        Set<LocalDate> out = new HashSet<>();
        collectDate(D_ZH.matcher(text), out, 1, 2, 3);   // yyyy年M月d日
        collectDate(D_ISO.matcher(text), out, 1, 2, 3);  // yyyy-MM-dd
        Matcher vi = D_VI.matcher(text);                 // ngày d tháng m năm yyyy
        while (vi.find()) addSafe(out, vi.group(3), vi.group(2), vi.group(1));
        Matcher sl = D_SL.matcher(text);                 // dd/MM/yyyy (giả định quy ước VN)
        while (sl.find()) addSafe(out, sl.group(3), sl.group(2), sl.group(1));
        return out;
    }

    private static void collectDate(Matcher m, Set<LocalDate> out, int y, int mo, int d) {
        while (m.find()) addSafe(out, m.group(y), m.group(mo), m.group(d));
    }

    private static void addSafe(Set<LocalDate> out, String y, String mo, String d) {
        try {
            out.add(LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d)));
        } catch (Exception ignored) { /* ngày không hợp lệ (32/13...) → bỏ, không đoán */ }
    }

    static String stripDates(String text) {
        for (Pattern p : List.of(D_ZH, D_VI, D_SL, D_ISO)) text = p.matcher(text).replaceAll(" ");
        return text;
    }

    /** Token số + tập dạng chuẩn (BigDecimal, stripTrailingZeros) + cờ phần trăm. */
    record NumToken(String raw, boolean percent, Set<BigDecimal> canon) {
        boolean matches(NumToken other) {
            if (this.percent != other.percent) return false;
            return this.canon.stream().anyMatch(other.canon::contains);
        }
    }

    static List<NumToken> extractNumbers(String text) {
        List<NumToken> out = new ArrayList<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            boolean pct = m.group(2) != null;
            Set<BigDecimal> canon = new HashSet<>();
            // Ứng viên 1: mọi dấu phân tách đều là phân tách nghìn → gỡ hết
            canon.add(new BigDecimal(raw.replaceAll("[.,]", "")).stripTrailingZeros());
            // Ứng viên 2: dấu phân tách CUỐI là thập phân, còn lại là nghìn
            int lastSep = Math.max(raw.lastIndexOf('.'), raw.lastIndexOf(','));
            if (lastSep >= 0) {
                String intPart = raw.substring(0, lastSep).replaceAll("[.,]", "");
                String decPart = raw.substring(lastSep + 1);
                try {
                    canon.add(new BigDecimal(intPart + "." + decPart).stripTrailingZeros());
                } catch (NumberFormatException ignored) {}
            }
            out.add(new NumToken(raw, pct, canon));
        }
        return out;
    }
}
