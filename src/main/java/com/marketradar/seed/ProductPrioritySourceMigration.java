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

import java.util.List;

/**
 * Backfills the curated Product-source set into databases that were created before
 * the expanded source registry existed. SeedData intentionally does nothing once
 * a database has any sources, which otherwise leaves an old demo database without
 * the Vietnam competitor/regulatory feeds that the current ingestion parsers know
 * how to process.
 *
 * This is deliberately additive: an existing source is never overwritten, enabled
 * or otherwise changed. Operators retain control over verification/activation and
 * no network request is made during startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProductPrioritySourceMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductPrioritySourceMigration.class);

    private final SourceRepository sources;

    public ProductPrioritySourceMigration(SourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public void run(ApplicationArguments args) {
        // On a fresh database SeedData owns the complete initial registry (and its
        // sample facts/routing rules). Do not pre-populate it here, or SeedData's
        // intentional "sources already exist" guard would skip that setup.
        if (sources.count() == 0) {
            return;
        }
        int added = 0;
        for (SourceDefinition definition : PRODUCT_PRIORITY_SOURCES) {
            if (sources.findByCode(definition.code()).isPresent()) {
                continue;
            }
            sources.save(definition.toSource());
            added++;
        }
        if (added > 0) {
            log.info("Added {} missing Product-priority sources to the registry; no existing source was changed", added);
        }
    }

    /*
     * Each entry below already has a dedicated parser/ingestion route in
     * IngestionJob. Do not add a source here merely because its homepage exists:
     * sources without an owned, validated parser commonly produce navigation text
     * or title-only records, which cannot improve Product evidence depth.
     */
    private static final List<SourceDefinition> PRODUCT_PRIORITY_SOURCES = List.of(
            regulator("MOF_ISA", "Cục Quản lý, giám sát bảo hiểm — Bộ Tài chính",
                    "https://www.mof.gov.vn/api/article/reads?offset=0&limit=25", "www.mof.gov.vn"),
            vietnamInsurer("AIA_VN", "AIA Việt Nam",
                    "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/su-kien-noi-bat.html", "www.aia.com.vn", Source.SourceType.HTML),
            vietnamInsurer("PRUDENTIAL_VN", "Prudential Việt Nam",
                    "https://www.prudential.com.vn/vi/ve-chung-toi/thong-cao-bao-chi/", "www.prudential.com.vn", Source.SourceType.HTML),
            vietnamInsurer("MB_AGEAS", "MB Ageas Life", "https://mblife.vn/goc-bao-chi", "mblife.vn", Source.SourceType.HTML),
            vietnamInsurer("PHU_HUNG_LIFE", "Phú Hưng Life",
                    "https://www.phuhunglife.com/vn/tin-tuc/?currentPage=1&year=all&categoryId=2907",
                    "www.phuhunglife.com", Source.SourceType.HTML),
            vietnamInsurer("BIDV_METLIFE", "BIDV MetLife",
                    "https://www.bidvmetlife.com.vn/bin/MLApp/globalMarketingPlatform/fetchArticleColumnGridArticleListing"
                            + "?articleDataConfig=taxonomy&articleDataTaxonomy=/content/metlife/vn/homepage/about-us/news"
                            + "&startArticleNum=0&endArticleNum=25&sortby=date&dateSort=desc",
                    "www.bidvmetlife.com.vn", Source.SourceType.JSON),
            vietnamInsurer("FUBON_VN", "Fubon Việt Nam", "https://www.fubonlife.com.vn/tin-tuc.html?tab=5",
                    "www.fubonlife.com.vn", Source.SourceType.HTML),
            vietnamInsurer("CATHAY_VN", "Cathay Life Việt Nam", "https://www.cathaylife.com.vn/cathay/api/graphql",
                    "www.cathaylife.com.vn", Source.SourceType.JSON),
            vietnamInsurer("SHINHAN_VN", "Shinhan Life Việt Nam",
                    "https://www.shinhanlifevn.com.vn/api/v1/application/getContent/press-release",
                    "www.shinhanlifevn.com.vn", Source.SourceType.JSON),
            vietnamInsurer("CHUBB_VN", "Chubb Life Việt Nam",
                    "https://www.chubb.com/vn-en/media-centre/press-release.html", "www.chubb.com", Source.SourceType.HTML),
            vietnamInsurer("DAIICHI_VN", "Dai-ichi Life Việt Nam", "https://dai-ichi-life.com.vn/api/news/home",
                    "dai-ichi-life.com.vn", Source.SourceType.JSON),
            vietnamInsurer("FWD_VN", "FWD Việt Nam", "https://www.fwd.com.vn/vi/blog/", "www.fwd.com.vn", Source.SourceType.HTML),
            vietnamInsurer("GENERALI_VN", "Generali Việt Nam",
                    "https://generali.vn/api/cms/api/thong-cao-bao-chis"
                            + "?fields%5B0%5D=title&fields%5B1%5D=slug&fields%5B2%5D=published_date"
                            + "&pagination%5Bpage%5D=1&pagination%5BpageSize%5D=25"
                            + "&sort%5B0%5D=published_date%3Adesc",
                    "generali.vn", Source.SourceType.JSON),
            vietnamInsurer("HANWHA_VN", "Hanwha Life Việt Nam", "https://hanwhalife.com.vn/vi/news",
                    "hanwhalife.com.vn", Source.SourceType.HTML)
    );

    private static SourceDefinition regulator(String code, String name, String fetchUrl, String allowedHost) {
        return new SourceDefinition(code, name, fetchUrl, allowedHost, Source.SourceType.JSON, 1, "vi");
    }

    private static SourceDefinition vietnamInsurer(String code, String name, String fetchUrl,
                                                    String allowedHost, Source.SourceType type) {
        return new SourceDefinition(code, name, fetchUrl, allowedHost, type, 2, "vi");
    }

    private record SourceDefinition(String code, String name, String fetchUrl, String allowedHost,
                                    Source.SourceType type, int tier, String language) {
        Source toSource() {
            return new Source(code, name, fetchUrl, allowedHost, type, tier, language);
        }
    }
}
