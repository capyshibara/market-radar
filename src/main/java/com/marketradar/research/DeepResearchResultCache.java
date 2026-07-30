package com.marketradar.research;

import com.marketradar.report.bi.BiReportContent;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deep Research KHÔNG ghi DB (xem DeepResearchService) — nhưng người dùng cần bấm "Tải PDF"/
 * "Tải .docx" SAU KHI đã xem báo cáo, mà không muốn chạy lại cả vòng lặp agent (tốn LLM call +
 * kết quả có thể khác lần trước vì không deterministic). Cache tạm trong bộ nhớ tiến trình, giới
 * hạn kích thước — mất khi restart app, đúng tinh thần "ad-hoc, không phải kho dữ liệu chính thức".
 */
@Component
public class DeepResearchResultCache {

    private static final int MAX_ENTRIES = 20;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, BiReportContent> store = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BiReportContent> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public String put(BiReportContent content) {
        String id = Long.toHexString(random.nextLong() & Long.MAX_VALUE);
        store.put(id, content);
        return id;
    }

    public BiReportContent get(String id) {
        return store.get(id);
    }
}
