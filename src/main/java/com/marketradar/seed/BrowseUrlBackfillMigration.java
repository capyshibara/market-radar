package com.marketradar.seed;

import com.marketradar.domain.Source;
import com.marketradar.repo.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Backfills Source.browseUrl for existing databases seeded before that field existed.
 * A handful of sources fetch through a POST/GraphQL API (fetchUrl) that returns nothing
 * useful to a plain GET click from a browser — the Source Registry's code-chip hyperlink
 * needs a separate human-facing page for those, or it looks like the source is dead when
 * it's actually fine (see SourceHealthCheckService/IngestionJob for the real method/body).
 *
 * Only fills a currently-blank browseUrl — never overwrites one an operator may have set.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class BrowseUrlBackfillMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BrowseUrlBackfillMigration.class);

    private static final Map<String, String> BROWSE_URL_BY_CODE = Map.of(
            "MOF_ISA", "https://www.mof.gov.vn/",
            "CATHAY_VN", "https://www.cathaylife.com.vn/cathay/news",
            "DAIICHI_VN", "https://dai-ichi-life.com.vn/tin-tuc",
            "HKIA", "https://www.ia.org.hk/en/infocenter/press_releases.html");

    /** Giá trị migration bản trước tự đoán rồi hoá ra sai (trang chủ, không phải trang tin thật)
     *  — cho phép lần chạy này SỬA ĐÈ đúng giá trị cũ này, không đụng browseUrl nào khác dù
     *  trùng hay khác (tôn trọng chỉnh sửa thật của operator nếu có trong tương lai). */
    private static final Map<String, String> KNOWN_WRONG_GUESSES = Map.of(
            "DAIICHI_VN", "https://dai-ichi-life.com.vn/");

    private final SourceRepository sources;

    public BrowseUrlBackfillMigration(SourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public void run(ApplicationArguments args) {
        int filled = 0;
        for (Map.Entry<String, String> entry : BROWSE_URL_BY_CODE.entrySet()) {
            Source source = sources.findByCode(entry.getKey()).orElse(null);
            if (source == null) continue;
            String current = source.getBrowseUrl();
            boolean blank = current == null || current.isBlank();
            boolean knownWrongGuess = !blank && current.equals(KNOWN_WRONG_GUESSES.get(entry.getKey()));
            if (!blank && !knownWrongGuess) continue;
            if (current != null && current.equals(entry.getValue())) continue;
            source.setBrowseUrl(entry.getValue());
            sources.save(source);
            filled++;
        }
        if (filled > 0) {
            log.info("Browse-URL backfill: set/corrected a human-facing link for {} source(s) whose fetchUrl is a "
                    + "POST/GraphQL API (MOF_ISA, CATHAY_VN, DAIICHI_VN, HKIA — not a plain-click URL).", filled);
        }
    }
}
