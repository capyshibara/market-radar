import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Port NGUYÊN VĂN các hàm thuần của GroundingGateL1 để test không cần Spring/Jackson. */
public class GateL1Test {

    static final Pattern D_ZH  = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    static final Pattern D_VI  = Pattern.compile("ngày\\s+(\\d{1,2})\\s+tháng\\s+(\\d{1,2})\\s+năm\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    static final Pattern D_SL  = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    static final Pattern D_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    static final Pattern NUM = Pattern.compile("(\\d+(?:[.,]\\d+)*)\\s*(%|％)?");
    static final String[][] QUOTES = {
            {"\"", "\""}, {"“", "”"}, {"'", "'"}, {"‘", "’"},
            {"「", "」"}, {"『", "』"}, {"«", "»"},
    };

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

    static Set<LocalDate> extractDates(String text) {
        Set<LocalDate> out = new HashSet<>();
        collectDate(D_ZH.matcher(text), out, 1, 2, 3);
        collectDate(D_ISO.matcher(text), out, 1, 2, 3);
        Matcher vi = D_VI.matcher(text);
        while (vi.find()) addSafe(out, vi.group(3), vi.group(2), vi.group(1));
        Matcher sl = D_SL.matcher(text);
        while (sl.find()) addSafe(out, sl.group(3), sl.group(2), sl.group(1));
        return out;
    }
    static void collectDate(Matcher m, Set<LocalDate> out, int y, int mo, int d) {
        while (m.find()) addSafe(out, m.group(y), m.group(mo), m.group(d));
    }
    static void addSafe(Set<LocalDate> out, String y, String mo, String d) {
        try { out.add(LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d))); }
        catch (Exception ignored) {}
    }
    static String stripDates(String text) {
        for (Pattern p : List.of(D_ZH, D_VI, D_SL, D_ISO)) text = p.matcher(text).replaceAll(" ");
        return text;
    }

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
            canon.add(new BigDecimal(raw.replaceAll("[.,]", "")).stripTrailingZeros());
            int lastSep = Math.max(raw.lastIndexOf('.'), raw.lastIndexOf(','));
            if (lastSep >= 0) {
                String intPart = raw.substring(0, lastSep).replaceAll("[.,]", "");
                String decPart = raw.substring(lastSep + 1);
                try { canon.add(new BigDecimal(intPart + "." + decPart).stripTrailingZeros()); }
                catch (NumberFormatException ignored) {}
            }
            out.add(new NumToken(raw, pct, canon));
        }
        return out;
    }

    // ===================== TESTS =====================
    static int pass = 0, fail = 0;
    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else      { fail++; System.out.println("  FAIL  " + name); }
    }
    static boolean numsAllMatch(String claim, String evidence) {
        List<NumToken> cn = extractNumbers(stripDates(claim));
        List<NumToken> en = extractNumbers(evidence); // evidence không gỡ ngày (giả định b)
        return cn.stream().allMatch(t -> en.stream().anyMatch(e -> e.matches(t)));
    }

    public static void main(String[] args) {
        String spanZh = "华晟人寿保险股份有限公司（示例）获批推出\"金福长盈\"分红型终身寿险，保证利率为2.0%，首年最低保费人民币10,000元。该产品自2026年7月1日起在全国范围销售。";
        String spanVi = "điều chỉnh phí quản lý quỹ của sản phẩm liên kết đơn vị 'An Phát Đầu Tư' từ 2,0%/năm xuống 1,75%/năm, áp dụng từ ngày 01/08/2026 cho cả hợp đồng mới và hợp đồng hiện hữu.";

        System.out.println("== Ngày ==");
        check("zh 2026年7月1日 -> 2026-07-01", extractDates(spanZh).contains(LocalDate.of(2026,7,1)));
        check("vi slash 01/08/2026 (dd/MM)", extractDates(spanVi).contains(LocalDate.of(2026,8,1)));
        check("vi chữ 'ngày 1 tháng 8 năm 2026'", extractDates("áp dụng từ ngày 1 tháng 8 năm 2026").contains(LocalDate.of(2026,8,1)));
        check("ISO 2026-08-01", extractDates("hiệu lực 2026-08-01").contains(LocalDate.of(2026,8,1)));
        check("claim ngày khớp evidence xuyên định dạng",
                extractDates(spanZh).contains(extractDates("bán từ ngày 01/07/2026").iterator().next()));
        check("ngày rác 32/13/2026 bị bỏ, không crash", extractDates("32/13/2026").isEmpty());

        System.out.println("== Số xuyên ngôn ngữ/định dạng ==");
        check("claim '2,0%' vs zh '2.0%'", numsAllMatch("lãi suất đảm bảo 2,0%", spanZh));
        check("claim '10.000' (vi) vs zh '10,000'", numsAllMatch("phí tối thiểu 10.000 nhân dân tệ", spanZh));
        check("claim '1,75%' vs vi '1,75%'", numsAllMatch("giảm xuống 1,75%", spanVi));
        check("claim số bịa 25.000 -> FAIL", !numsAllMatch("25.000 hợp đồng khai thác mới", spanZh));
        check("percent flag: '2%' không khớp '2' trần", !numsAllMatch("tăng 2%", "có 2 sản phẩm"));
        check("năm trần '2026' trong claim khớp evidence (evidence không gỡ ngày)",
                numsAllMatch("kể từ 2026", spanZh));

        System.out.println("== Tên trong ngoặc kép ==");
        List<String> q1 = extractQuoted("Sản phẩm \"金福长盈\" được phê duyệt.");
        check("bắt được tên script gốc trong ngoặc thẳng", q1.contains("金福长盈"));
        check("tên gốc tìm thấy verbatim trong span", spanZh.contains("金福长盈"));
        check("tên DỊCH 'Kim Phúc Trường Doanh' KHÔNG có trong span", !spanZh.contains("Kim Phúc Trường Doanh"));
        List<String> q2 = extractQuoted("sản phẩm 'An Phát Đầu Tư' giảm phí");
        check("bắt tên trong nháy đơn", q2.contains("An Phát Đầu Tư"));
        check("span zh dùng ngoặc kép thẳng quanh 金福长盈 — extract từ chính span",
                extractQuoted(spanZh).contains("金福长盈"));

        System.out.println("== Demo inject claim (end-to-end logic) ==");
        String demo = "Sản phẩm \"Kim Phúc Trường Doanh\" là sản phẩm bán chạy nhất tuần qua với 25.000 hợp đồng khai thác mới.";
        boolean nameFail = extractQuoted(demo).stream().anyMatch(n -> !spanZh.contains(n));
        boolean numFail = !numsAllMatch(demo, spanZh);
        check("demo claim: tên FAIL", nameFail);
        check("demo claim: số FAIL", numFail);

        System.out.println("\n" + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
