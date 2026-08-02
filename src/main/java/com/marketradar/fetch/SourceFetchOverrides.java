package com.marketradar.fetch;

/**
 * Per-source fetch quirks (POST body, raised byte cap) shared between the real scheduled
 * crawl (IngestionJob) and the diagnostic health check (SourceHealthCheckService), so the two
 * can never drift out of sync. A drift here has repeatedly made the health check misreport a
 * perfectly live source as broken: MOF_ISA/CATHAY_VN/DAIICHI_VN/HKIA's real ingest sends a
 * specific POST body that a plain health-check GET never sent (server responds with an error
 * page or a safety-blocked redirect instead of the real JSON); FWD_VN needed a raised byte cap
 * the health check's default 5MB was rejecting. Single source of truth for both call sites.
 */
public final class SourceFetchOverrides {
    private SourceFetchOverrides() {}

    /** rootCategoryId của chuyên mục "Quản lý giám sát bảo hiểm" trên portal MOF (xác nhận live 2026-07-14). */
    private static final String MOF_INSURANCE_ROOT_CATEGORY = "8dc0b2a0-38bd-427c-b6d5-c97a6f9952b4";

    /**
     * Query GraphQL THẬT của Cathay Life, bắt được bằng cách vá window.fetch trên trang
     * /cathay/news rồi bấm tab chuyên mục thật (không đoán schema). ncategory_id="1" =
     * "Hoạt động kinh doanh" — chuyên mục tin công ty/PR, sát nghĩa insurance news nhất.
     */
    public static final String CATHAY_VN_GRAPHQL_BODY = """
            {"variables":{"condition":{"ncategory_id":"1","start":1,"end":15},"ncategory_id":"1"},\
            "query":"query news($condition: NewsParams!, $ncategory_id: Int) {\\n    news(condition: $condition) {\\n        news_id\\n        images\\n        images_name\\n        content\\n        featured\\n        ncategory_id\\n        posted_at\\n    }\\n    count(ncategory_id: $ncategory_id)\\n}"}""";

    /** Trang HTML thật của HKIA (không phải endpoint .php) — url tương đối trong response resolve theo đây. */
    public static final String HKIA_PAGE_URL = "https://www.ia.org.hk/en/infocenter/press_releases.html";

    /** FWD_VN /vi/blog/ embed ~331 bài trong __NEXT_DATA__ (~7-8MB) — vượt cap mặc định 5MB. Đã
     *  xác nhận thủ công đây là nội dung thật (không phải payload tấn công). */
    public static final long FWD_VN_MAX_BYTES = 12L * 1024 * 1024;

    /** POST body bắt buộc cho endpoint danh sách của nguồn này, hoặc null nếu nguồn dùng GET thường. */
    public static String postBodyFor(String sourceCode) {
        return switch (sourceCode) {
            case "MOF_ISA" -> "{\"rootCategoryId\":\"" + MOF_INSURANCE_ROOT_CATEGORY + "\"}";
            case "DAIICHI_VN" -> "{}";
            case "HKIA" -> "";
            case "CATHAY_VN" -> CATHAY_VN_GRAPHQL_BODY;
            default -> null;
        };
    }

    /** Trần byte nâng riêng cho nguồn này, hoặc -1 nếu dùng cap mặc định của SafeFetcher. */
    public static long maxBytesOverrideFor(String sourceCode) {
        return "FWD_VN".equals(sourceCode) ? FWD_VN_MAX_BYTES : -1;
    }
}
