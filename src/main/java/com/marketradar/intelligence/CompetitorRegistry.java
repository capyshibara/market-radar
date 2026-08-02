package com.marketradar.intelligence;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sổ đăng ký đối thủ (thị trường bảo hiểm nhân thọ Việt Nam): tên chuẩn + bí danh
 * + các THỰC THỂ DỄ NHẦM cùng thương hiệu nhưng khác pháp nhân/khác thị trường.
 *
 * Sinh ra từ một sự cố thật: bản tóm tắt AI về Prudential plc (mẹ của Prudential
 * Việt Nam) trộn lẫn thông tin của Prudential Financial (Mỹ) — hai công ty KHÔNG
 * liên quan, chỉ trùng tên thương hiệu. Một phân tích thông minh trên thông tin
 * sai đối tượng nguy hiểm hơn không có phân tích nào (nguyên văn yêu cầu của
 * người dùng phía Strategy).
 *
 * Danh sách bí danh/marker là DỮ KIỆN CÔNG KHAI kiểm chứng được (tên pháp nhân,
 * thương hiệu con), không phải suy đoán. Chỉ thêm marker khi nó thực sự phân
 * biệt được hai pháp nhân (vd "PGIM" chỉ thuộc Prudential Financial Mỹ).
 *
 * Dùng ở 2 chỗ:
 *  - EntityAttributionGuard: cảnh báo khi claim quy kết công ty X nhưng bằng
 *    chứng lại nói về thực thể dễ nhầm của X.
 *  - PeriodicalBiAdapter: suy ra subjectKey (tên công ty) cho BiFinding từ nội
 *    dung claim, để báo cáo BI nhóm được trang "Điểm nổi bật" theo đối thủ.
 */
@Component
public class CompetitorRegistry {

    /** Một đối thủ: tên chuẩn hiển thị + các bí danh nhận diện trong văn bản. */
    public record Competitor(String canonicalName, List<String> aliases) {}

    /**
     * Một thực thể dễ nhầm với đối thủ đã đăng ký: cùng thương hiệu, khác pháp
     * nhân. markers = các chuỗi CHỈ xuất hiện khi văn bản nói về thực thể nhầm
     * (thương hiệu con, tên pháp nhân đầy đủ, mã niêm yết...).
     */
    public record Confusable(String competitorCanonical, String confusableName,
                             List<String> markers) {}

    private final List<Competitor> competitors;
    private final List<Confusable> confusables;
    private final Map<String, List<Pattern>> aliasPatterns = new LinkedHashMap<>();
    private final Map<String, List<Pattern>> markerPatterns = new LinkedHashMap<>();

    public CompetitorRegistry() {
        this.competitors = List.of(
                new Competitor("Prudential Việt Nam",
                        List.of("prudential việt nam", "prudential vietnam", "prudential plc", "prudential")),
                new Competitor("Manulife Việt Nam", List.of("manulife")),
                new Competitor("AIA Việt Nam", List.of("aia")),
                new Competitor("Dai-ichi Life Việt Nam", List.of("dai-ichi", "daiichi")),
                new Competitor("Chubb Life Việt Nam", List.of("chubb life", "chubb")),
                new Competitor("Hanwha Life Việt Nam", List.of("hanwha life", "hanwha")),
                new Competitor("FWD Việt Nam", List.of("fwd")),
                new Competitor("Generali Việt Nam", List.of("generali")),
                new Competitor("Sun Life Việt Nam", List.of("sun life", "sunlife")),
                new Competitor("Bảo Việt Nhân thọ",
                        List.of("bảo việt nhân thọ", "bao viet life", "baoviet life")),
                new Competitor("MB Ageas Life", List.of("mb ageas", "mb life", "mbal")),
                new Competitor("BIDV MetLife", List.of("bidv metlife", "bidv-metlife")),
                new Competitor("Cathay Life Việt Nam", List.of("cathay life", "cathay")),
                new Competitor("Shinhan Life Việt Nam", List.of("shinhan life")),
                new Competitor("Phú Hưng Life", List.of("phú hưng life", "phu hung life")));

        this.confusables = List.of(
                // Ca thật đã xảy ra: Prudential plc (Anh, mẹ của Prudential VN) ≠
                // Prudential Financial, Inc. (Mỹ, NYSE:PRU, sở hữu PGIM) — không liên quan.
                new Confusable("Prudential Việt Nam", "Prudential Financial (Hoa Kỳ)",
                        List.of("prudential financial", "pgim", "nyse: pru", "nyse:pru",
                                "newark, new jersey")),
                // Bảo Việt Nhân thọ (nhân thọ) ≠ Bảo hiểm Bảo Việt (phi nhân thọ) —
                // cùng tập đoàn, khác pháp nhân, số liệu không dùng thay nhau được.
                new Confusable("Bảo Việt Nhân thọ", "Bảo hiểm Bảo Việt (phi nhân thọ)",
                        List.of("bảo hiểm bảo việt", "baoviet insurance", "bảo việt phi nhân thọ")),
                // MetLife, Inc. (Mỹ) đứng riêng ≠ liên doanh BIDV MetLife tại VN.
                new Confusable("BIDV MetLife", "MetLife, Inc. (Hoa Kỳ)",
                        List.of("metlife, inc", "metlife inc", "nyse: met")));

        for (Competitor c : competitors) {
            aliasPatterns.put(c.canonicalName(), c.aliases().stream()
                    .map(CompetitorRegistry::wordPattern).toList());
        }
        for (Confusable cf : confusables) {
            markerPatterns.put(cf.confusableName(), cf.markers().stream()
                    .map(CompetitorRegistry::wordPattern).toList());
        }
    }

    /** \b + quote để "aia" không khớp giữa từ khác; (?iu) cho tiếng Việt có dấu. */
    private static Pattern wordPattern(String alias) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(alias)
                + "(?![\\p{L}\\p{N}])");
    }

    public List<Competitor> competitors() { return competitors; }

    public List<Confusable> confusables() { return confusables; }

    /** Đối thủ ĐẦU TIÊN (theo thứ tự đăng ký) được nhắc trong văn bản — null-safe. */
    public Optional<String> detectCompetitor(String text) {
        List<String> all = detectAllCompetitors(text);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Mọi đối thủ được nhắc trong văn bản, theo thứ tự đăng ký. */
    public List<String> detectAllCompetitors(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> found = new ArrayList<>();
        for (Competitor c : competitors) {
            for (Pattern p : aliasPatterns.get(c.canonicalName())) {
                if (p.matcher(text).find()) {
                    found.add(c.canonicalName());
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    /** true nếu văn bản có nhắc đối thủ canonicalName (qua bất kỳ bí danh nào). */
    public boolean mentions(String canonicalName, String text) {
        if (text == null || text.isBlank()) return false;
        List<Pattern> patterns = aliasPatterns.get(canonicalName);
        if (patterns == null) return false;
        return patterns.stream().anyMatch(p -> p.matcher(text).find());
    }

    /** Các thực thể dễ nhầm của competitor này có marker xuất hiện trong văn bản. */
    public List<Confusable> confusableMarkersIn(String competitorCanonical, String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Confusable> hits = new ArrayList<>();
        for (Confusable cf : confusables) {
            if (!cf.competitorCanonical().equals(competitorCanonical)) continue;
            for (Pattern p : markerPatterns.get(cf.confusableName())) {
                if (p.matcher(text).find()) {
                    hits.add(cf);
                    break;
                }
            }
        }
        return List.copyOf(hits);
    }
}
