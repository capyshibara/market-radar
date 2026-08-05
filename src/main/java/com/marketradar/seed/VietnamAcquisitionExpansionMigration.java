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
 * Acquisition-first remediation for the Vietnam market corpus.
 *
 * A registry row is activated here only when the application owns a listing/API/sitemap
 * parser for it. Geography tier never controls activation. Known generic homepages and
 * blocked URLs are explicitly inactive so they cannot masquerade as coverage.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class VietnamAcquisitionExpansionMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VietnamAcquisitionExpansionMigration.class);
    private final SourceRepository sources;

    public VietnamAcquisitionExpansionMigration(SourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public void run(ApplicationArguments args) {
        int changed = 0;
        for (Definition definition : DEFINITIONS) {
            Source source = sources.findByCode(definition.code()).orElse(null);
            boolean newlyCreated = source == null;
            boolean firstMetadataMigration = source != null && !source.hasExplicitIntelligenceMetadata();
            boolean configurationChanged = source != null && configurationChanged(source, definition);
            if (source == null) {
                source = new Source(definition.code(), definition.name(), definition.fetchUrl(),
                        definition.allowedHost(), definition.type(), definition.tier(), definition.language());
            } else {
                source.reconfigure(definition.name(), definition.fetchUrl(), definition.allowedHost(),
                        definition.type(), definition.tier(), definition.language());
            }
            if (newlyCreated || firstMetadataMigration || configurationChanged
                    || source.getBrowseUrl() == null || source.getBrowseUrl().isBlank()) {
                source.setBrowseUrl(definition.browseUrl());
            }
            if (newlyCreated || firstMetadataMigration || configurationChanged) {
                source.setActive(definition.active());
                // A new/changed fetch contract needs a fresh health audit. On normal
                // restarts preserve the operator's activation and verified state.
                source.setUrlUnverified(true);
                changed++;
            }
            sources.save(source);
        }
        if (changed > 0) {
            log.info("Vietnam acquisition expansion initialized/updated {} parser-owned source channels; "
                    + "unchanged operator health decisions were preserved", changed);
        }
    }

    private static boolean configurationChanged(Source source, Definition definition) {
        return !java.util.Objects.equals(source.getFetchUrl(), definition.fetchUrl())
                || !java.util.Objects.equals(source.getAllowedHost(), definition.allowedHost())
                || source.getType() != definition.type()
                || !java.util.Objects.equals(source.getLanguage(), definition.language());
    }

    private static final List<Definition> DEFINITIONS = List.of(
            // Official market/regulatory time series.
            active("MOF_ISA", "Cục Quản lý, giám sát bảo hiểm — Bộ Tài chính",
                    "https://www.mof.gov.vn/api/article/reads?offset=0&limit=100", "www.mof.gov.vn",
                    Source.SourceType.JSON, 1, "vi", "https://www.mof.gov.vn/quan-ly-giam-sat-bao-hiem"),
            inactive("IAV_VN", "Hiệp hội Bảo hiểm Việt Nam — kênh tổng hợp hiện redirect sang trang rỗng",
                    "https://iav.vn/News/GroupList/261", "iav.vn", Source.SourceType.HTML, 1, "vi"),
            active("IAV_LIFE_PRODUCTS", "Hiệp hội Bảo hiểm Việt Nam — sản phẩm bảo hiểm nhân thọ",
                    "https://iav.vn/News/Listtt/131?page=1", "iav.vn", Source.SourceType.HTML, 1, "vi",
                    "https://iav.vn/News/Listtt/131?page=1"),
            active("IAV_LIFE_DISCLOSURES", "Hiệp hội Bảo hiểm Việt Nam — công khai sản phẩm khối nhân thọ",
                    "https://iav.vn/News/Listtt/276?page=1", "iav.vn", Source.SourceType.HTML, 1, "vi",
                    "https://iav.vn/News/Listtt/276?page=1"),
            active("IAV_LIFE_ACTIVITIES", "Hiệp hội Bảo hiểm Việt Nam — hoạt động khối nhân thọ",
                    "https://iav.vn/News/Listtt/168?page=1", "iav.vn", Source.SourceType.HTML, 1, "vi",
                    "https://iav.vn/News/Listtt/168?page=1"),
            active("NSO_VN", "Cục Thống kê — báo cáo kinh tế xã hội hàng tháng",
                    "https://www.nso.gov.vn/bao-cao-tinh-hinh-kinh-te-xa-hoi-hang-thang/", "www.nso.gov.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("HNX_GOVERNMENT_BONDS", "Sở GDCK Hà Nội — thị trường trái phiếu Chính phủ hàng tháng",
                    "https://hnx.vn/vi-vn/ModuleMedia/MediaCenter/TrungTamTruyenThong", "hnx.vn",
                    Source.SourceType.HTML, 1, "vi", "https://hnx.vn/vi-vn/hnx.html"),
            active("SBV_MARKET_OPERATIONS", "Ngân hàng Nhà nước — lãi suất, ngoại tệ và liên ngân hàng",
                    "https://www.sbv.gov.vn/", "www.sbv.gov.vn",
                    Source.SourceType.HTML, 1, "vi", "https://www.sbv.gov.vn/"),

            // Missing/new Vietnam life insurers and a second official AIA disclosure channel.
            active("MVI_LIFE", "MVI Life — tin tức và thông báo",
                    "https://www.mvilife.com.vn/vi/Ve-chung-toi/Tin-tuc.html", "www.mvilife.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("TECHCOM_LIFE", "Techcom Life — news sitemap",
                    "https://www.techcomlife.com/sitemap.xml/", "www.techcomlife.com",
                    Source.SourceType.SITEMAP, 1, "vi", "https://www.techcomlife.com/news/all-news-events-11/"),
            active("AIA_VN_NOTICES", "AIA Việt Nam — thông báo sản phẩm và dịch vụ",
                    "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/thong-bao/.html", "www.aia.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("AIA_VN", "AIA Việt Nam — thông cáo báo chí",
                    "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/thong-cao-bao-chi.html",
                    "www.aia.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("MAP_LIFE", "MAP Life — tin tức (PKIX chain completed with pinned GlobalSign intermediate)",
                    "https://www.map-life.com.vn/news", "www.map-life.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("BVNT", "Bảo Việt Nhân thọ — tin tức",
                    "https://www.baovietnhantho.com.vn/tin-tuc", "www.baovietnhantho.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("SUNLIFE_VN", "Sun Life Việt Nam — tin tức và sự kiện 2026",
                    "https://www.sunlife.com.vn/vn/ve-chung-toi/tin-tuc-su-kien/2026/",
                    "www.sunlife.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("BAOVIET_HOLDINGS_NEWS",
                    "Tập đoàn Bảo Việt — kết quả kinh doanh có dữ liệu Bảo Việt Nhân thọ",
                    "https://www.baoviet.com.vn/vi/tin-tuc-su-kien", "www.baoviet.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("AIA_GROUP_RESULTS",
                    "AIA Group — results filtered to explicit Vietnam performance",
                    "https://www.aia.com/en/media-centre/press-releases", "www.aia.com",
                    Source.SourceType.HTML, 3, "en", null),
            active("TECHCOMBANK_IR_LIFE_RESULTS",
                    "Techcombank IR — official bancassurance and Techcom Life results",
                    "https://techcombank.com/nha-dau-tu", "techcombank.com",
                    Source.SourceType.HTML, 1, "vi", null),
            active("BIDV_METLIFE_AGENCY_2026",
                    "BIDV — official 2026 agency-contract disclosure with BIDV MetLife",
                    "https://bidv.com.vn/bidv/quan-he-nha-dau-tu/thong-tin-co-dong/congbothongtin/2026/cbtt%2Bgiao%2Bdich%2Bvoi%2Bben%2Bco%2Blien%2Bquan%2Bbidv%2Bmetlife",
                    "bidv.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("VIETCOMBANK_FWD_DISTRIBUTION_2026",
                    "Vietcombank — official 2026 FWD distribution and bundled-protection case",
                    "https://www.vietcombank.com.vn/vi-VN/Trang-thong-tin-dien-tu/Articles/2026/06/26/20260626_MKTTT_TCBC_HMH_TietkiemVietcombanknhanBaohiemSuckhoeFWD",
                    "www.vietcombank.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("MOF_INSURANCE_CYBER_RISK_2026",
                    "Viện Phát triển bảo hiểm/Bộ Tài chính — AI, data and cyber-risk recommendations 2026",
                    "https://vidi.mof.gov.vn/api/article/getbyslug?slug=tu-bao-cao-rui-ro-toan-cau-2026-cua-dien-dan-kinh-te-the-gioi-mot-so-khuyen-nghi-cho-thi-truong-bao-hiem-viet-nam",
                    "vidi.mof.gov.vn", Source.SourceType.JSON, 1, "vi",
                    "https://vidi.mof.gov.vn/vien-phat-trien-bao-hiem-viet-nam/nghien-cuu-trao-doi/tu-bao-cao-rui-ro-toan-cau-2026-cua-dien-dan-kinh-te-the-gioi-mot-so-khuyen-nghi-cho-thi-truong-bao-hiem-viet-nam"),
            active("GOV_PERSONAL_DATA_INSURANCE_2026",
                    "Công an tỉnh Cà Mau — personal-data duties in insurance effective 2026",
                    "https://congan.camau.gov.vn/ch40/7679-Bao-ve-du-lieu-ca-nhan-trong-hoat-dong-kinh-doanh-bao-hiem.mhtml",
                    "congan.camau.gov.vn", Source.SourceType.HTML, 1, "vi", null),
            active("IAV_CHUBB_IGLOO_2026",
                    "Hiệp hội Bảo hiểm Việt Nam — Chubb Life and Igloo digital distribution case",
                    "https://hiephoibaohiemvietnam.vn/tin-hoat-dong-khoi-nhan-tho/345136-chubb-life-viet-nam-ky-ket-hop-tac-cung-igloo-thuc-day-kha-nang-tiep-can-bao-hiem-thong-qua-nen-tang-so",
                    "hiephoibaohiemvietnam.vn", Source.SourceType.HTML, 1, "vi", null),
            active("MILLIMAN_VN_LIFE_LANDSCAPE_2026",
                    "Milliman — Vietnam life-insurance landscape after the mis-selling crisis",
                    "https://media.milliman.com/v1/media/edge/images/millimaninc5660-milliman6442-prod27d5-0001/media/Milliman/PDFs/2026-Articles/6-8-26_Vietnam-e-alert_Vietnams_life_insurance_landscape_after_the_mis-selling_crisis.pdf",
                    "media.milliman.com", Source.SourceType.PDF, 2, "en", null),
            active("FINANCE_RESEARCH_DATA_INSURANCE_2026",
                    "Tạp chí Kinh tế - Tài chính — four-dimensional impact of personal-data law on insurance",
                    "https://nghiencuu.tapchikinhtetaichinh.vn/danh-gia-da-chieu-va-du-bao-tac-dong-cua-luat-bao-ve-du-lieu-ca-nhan-den-nganh-bao-hiem-viet-nam-trong-ky-nguyen-so-131986.html",
                    "nghiencuu.tapchikinhtetaichinh.vn", Source.SourceType.HTML, 2, "vi", null),
            active("LUATVIETNAM_ONLINE_INSURANCE_DATA_2026",
                    "LuatVietnam — online-insurance data and cybersecurity duties from July 2026",
                    "https://english.luatvietnam.vn/legal-updates/personal-data-protection-required-for-online-insurance-services-from-july-1-2026-892-110396-article.html",
                    "english.luatvietnam.vn", Source.SourceType.HTML, 2, "en", null),

            // Official statutory-report lane.  A company newsroom cannot substitute for
            // audited/interim statements: these channels contribute the numbers required
            // for competitor financial-performance cells in the CFO report.
            active("BVNT_FINANCIALS", "Bảo Việt Nhân thọ — statutory financial reports",
                    "https://www.baovietnhantho.com.vn/bao-cao-tai-chinh",
                    "www.baovietnhantho.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("AIA_VN_FINANCIALS", "AIA Việt Nam — statutory financial reports",
                    "https://www.aia.com.vn/vi/ve-chung-toi/truyen-thong/bao-cao-tai-chinh.html",
                    "www.aia.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("PRUDENTIAL_VN_FINANCIALS", "Prudential Việt Nam — statutory financial reports",
                    "https://www.prudential.com.vn/vi/ve-chung-toi/prudential-viet-nam/bao-cao-tai-chinh/",
                    "www.prudential.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("MANULIFE_VN_FINANCIALS", "Manulife Việt Nam — statutory financial reports",
                    "https://www.manulife.com.vn/vi/ve-chung-toi/bao-cao-tai-chinh.html",
                    "www.manulife.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("MB_LIFE_FINANCIALS", "MB Life — statutory financial reports",
                    "https://mblife.vn/bao-cao-tai-chinh", "mblife.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("HANWHA_VN_FINANCIALS", "Hanwha Life Việt Nam — statutory financial reports",
                    "https://www.hanwhalife.com.vn/vi/about", "www.hanwhalife.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("CHUBB_VN_FINANCIALS", "Chubb Life Việt Nam — statutory financial reports",
                    "https://www.chubb.com/vn-vn/about-chubb/chubb-life-financial-statement.html",
                    "www.chubb.com", Source.SourceType.HTML, 1, "vi", null),
            active("FUBON_VN_FINANCIALS", "Fubon Life Việt Nam — statutory financial reports",
                    "https://www.fubonlife.com.vn/gioi-thieu/bao-cao-tai-chinh.html?tab=1",
                    "www.fubonlife.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("MAP_LIFE_FINANCIALS", "MAP Life — statutory financial reports (verified PKIX chain repair)",
                    "https://www.map-life.com.vn/financial-report", "www.map-life.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("MVI_LIFE_FINANCIALS", "MVI Life — statutory financial reports",
                    "https://www.mvilife.com.vn/vi/Ve-chung-toi/bao-cao.html", "www.mvilife.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("TECHCOM_LIFE_FINANCIALS", "Techcom Life — statutory financial reports",
                    "https://www.techcomlife.com/ve-chung-toi/bao-cao-tai-chinh-12/",
                    "www.techcomlife.com", Source.SourceType.HTML, 1, "vi", null),
            active("DAIICHI_VN_FINANCIALS", "Dai-ichi Life Việt Nam — statutory financial reports",
                    "https://dai-ichi-life.com.vn/bao-cao-tai-chinh-32", "dai-ichi-life.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("PHU_HUNG_LIFE_FINANCIALS", "Phú Hưng Life — statutory financial reports",
                    "https://www.phuhunglife.com/vn/tin-tuc/phu-hung-life-bao-cao-tai-chinh-2025/",
                    "www.phuhunglife.com", Source.SourceType.HTML, 1, "vi", null),
            active("GENERALI_VN_FINANCIALS", "Generali Việt Nam — statutory financial reports",
                    "https://generali.vn/page/company/bao-cao-tai-chinh", "generali.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("SUNLIFE_VN_FINANCIALS", "Sun Life Việt Nam — statutory financial reports",
                    "https://www.sunlife.com.vn/vn/bao-cao-tai-chinh-nam-2025-da-kiem-toan/",
                    "www.sunlife.com.vn", Source.SourceType.HTML, 1, "vi", null),
            active("BIDV_METLIFE_FINANCIALS", "BIDV MetLife — statutory financial reports API",
                    "https://www.bidvmetlife.com.vn/bin/MLApp/globalMarketingPlatform/fetchFormsLibrary?products=bao_cao_tai_chinh_full&formsPath=%2Fcontent%2Fdam%2Fmetlifecom%2Fvn%2FPDFs%2Fforms-library-vn%2Freports-vn",
                    "www.bidvmetlife.com.vn", Source.SourceType.JSON, 1, "vi",
                    "https://www.bidvmetlife.com.vn/about-us/reports/"),
            active("FWD_VN_FINANCIALS", "FWD Việt Nam — statutory financial reports",
                    "https://www.fwd.com.vn/vi/about-us/", "www.fwd.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("SHINHAN_VN_FINANCIALS", "Shinhan Life Việt Nam — statutory financial reports",
                    "https://www.shinhanlifevn.com.vn/financial-statement", "www.shinhanlifevn.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("DAIICHI_VN", "Dai-ichi Life Việt Nam — tin tức và thông báo",
                    "https://dai-ichi-life.com.vn/tin-tuc", "dai-ichi-life.com.vn",
                    Source.SourceType.HTML, 1, "vi", null),

            // Independent Vietnam market lenses with dedicated insurance pages.
            active("TNCK_VN", "Tin nhanh Chứng khoán — chuyên mục bảo hiểm",
                    "https://www.tinnhanhchungkhoan.vn/bao-hiem/", "www.tinnhanhchungkhoan.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("VNECONOMY", "VnEconomy — bảo hiểm tài chính",
                    "https://vneconomy.vn/bao-hiem-tai-chinh.htm?page=1", "vneconomy.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("VIETNAMNET_LIFE", "VietnamNet — bảo hiểm nhân thọ",
                    "https://vietnamnet.vn/bao-hiem-nhan-tho-tag11682689471233933485.html", "vietnamnet.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("VIR_INSURANCE", "Vietnam Investment Review — insurance",
                    "https://vir.com.vn/money/insurance", "vir.com.vn",
                    Source.SourceType.HTML, 2, "en", null),
            active("VIETNAMPLUS_INSURANCE", "VietnamPlus — chuyên đề bảo hiểm",
                    "https://www.vietnamplus.vn/tag/bao-hiem-tag7034.vnp", "www.vietnamplus.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("BAODAUTU_LIFE", "Báo Đầu tư — bảo hiểm nhân thọ",
                    "https://baodautu.vn/tag/bao-hiem-nhan-tho/", "baodautu.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("VIETNAMFINANCE_LIFE", "VietnamFinance — tài chính tiêu dùng, lọc bảo hiểm nhân thọ",
                    "https://vietnamfinance.vn/tai-chinh-tieu-dung/", "vietnamfinance.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("BNEWS_FINANCE_INSURANCE", "BNews/TTXVN — RSS tài chính, lọc bảo hiểm tư nhân",
                    "https://bnews.vn/rss/tai-chinh-17.rss", "bnews.vn",
                    Source.SourceType.RSS, 2, "vi", null),
            active("BIZHUB_INSURANCE", "Vietnam News/BizHub — Vietnam insurance",
                    "https://bizhub.vietnamnews.vn/insurance", "bizhub.vietnamnews.vn",
                    Source.SourceType.HTML, 2, "en", null),
            active("BAOCHINHPHU_INSURANCE", "Báo Chính phủ — chính sách bảo hiểm",
                    "https://baochinhphu.vn/bao-hiem.html", "baochinhphu.vn",
                    Source.SourceType.HTML, 1, "vi", null),
            active("VNEXPRESS_INSURANCE", "VnExpress — kinh doanh bảo hiểm",
                    "https://vnexpress.net/kinh-doanh/bao-hiem", "vnexpress.net",
                    Source.SourceType.HTML, 2, "vi", null),
            active("TBNH", "Thời báo Ngân hàng — tag bảo hiểm (NHNN)",
                    "https://thoibaonganhang.vn/tag/bao-hiem-141.tag", "thoibaonganhang.vn",
                    Source.SourceType.HTML, 2, "vi", null),
            active("THEINVESTOR_INSURANCE", "The Investor — Vietnam insurance search",
                    "https://theinvestor.vn/insurance-search1/", "theinvestor.vn",
                    Source.SourceType.HTML, 2, "en", null),
            active("DDD_FINANCIAL_SERVICES", "Diễn đàn Doanh nghiệp — dịch vụ tài chính",
                    "https://diendandoanhnghiep.vn/ngan-hang-chung-khoan/dich-vu-tai-chinh",
                    "diendandoanhnghiep.vn", Source.SourceType.HTML, 2, "vi", null),
            inactive("TBTCO_LIFE_SEARCH", "Thời báo Tài chính Việt Nam — endpoint hiện không ổn định",
                    "https://thoibaotaichinhvietnam.vn/search_enginer.html?p=search&q=b%E1%BA%A3o%20hi%E1%BB%83m%20nh%C3%A2n%20th%E1%BB%8D",
                    "thoibaotaichinhvietnam.vn", Source.SourceType.HTML, 1, "vi"),

            // Re-enable sources whose former comments/status became stale after live recheck.
            active("MANULIFE_VN", "Manulife Việt Nam — thông cáo báo chí",
                    "https://www.manulife.com.vn/vi/ve-chung-toi/tin-tuc-va-su-kien/thong-cao-bao-chi.html",
                    "www.manulife.com.vn", Source.SourceType.HTML, 1, "vi", null),

            // Keep unresolved rows for audit/UI, but do not count them as live coverage until
            // a targeted parser/fallback exists.
            inactive("CAFEF", "CafeF — parser not yet qualified",
                    "https://cafef.vn/", "cafef.vn", Source.SourceType.HTML, 2, "vi"),
            inactive("LP_LIFE", "LP Life — newly licensed June 2026; no official publishing endpoint yet",
                    "https://www.mof.gov.vn/quan-ly-giam-sat-bao-hiem/tin-quan-ly-giam-sat/cap-giay-phep-thanh-lap-va-hoat-dong-cho-cong-ty-co-phan-bao-hiem-nhan-tho-lp",
                    "www.mof.gov.vn", Source.SourceType.HTML, 1, "vi")
    );

    private static Definition active(String code, String name, String url, String host,
                                     Source.SourceType type, int tier, String language, String browseUrl) {
        return new Definition(code, name, url, host, type, tier, language, true, browseUrl);
    }

    private static Definition inactive(String code, String name, String url, String host,
                                       Source.SourceType type, int tier, String language) {
        return new Definition(code, name, url, host, type, tier, language, false, null);
    }

    private record Definition(String code, String name, String fetchUrl, String allowedHost,
                              Source.SourceType type, int tier, String language,
                              boolean active, String browseUrl) {}
}
