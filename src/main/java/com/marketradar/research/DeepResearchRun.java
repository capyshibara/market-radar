package com.marketradar.research;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 2026-08-03: bản ghi lâu dài cho mỗi lần chạy Deep Research — trước đây kết quả chỉ sống
 * trong {@link DeepResearchResultCache} (bộ nhớ tiến trình, tối đa 20 kết quả, MẤT khi app
 * restart) và không có nơi nào trong app liệt kê lại các lần đã chạy — người dùng chỉ xem được
 * kết quả ngay lúc vừa chạy xong qua link SSE trả về, không quay lại xem sau được (feedback:
 * "không nhìn thấy kết quả deep research ở đâu cả, cần có chỗ xem report và cả lịch sử chạy").
 *
 * contentJson lưu nguyên {@link com.marketradar.report.bi.BiReportContent} đã tổng hợp (bản xem
 * nhanh, CHƯA qua Fact-checker/Verifier) để render lại đúng như lúc vừa chạy xong, không cần
 * chạy lại agent. newDocIdsCsv lưu id các RawDoc THẬT đã nạp vào pipeline xác thực từ lần chạy
 * này — dùng để tra cứu claim/trạng thái duyệt phát sinh từ đó (xem DeepResearchHistoryController),
 * trả lời câu hỏi "nó chui vào báo cáo chung như nào".
 */
@Entity
@Table(name = "deep_research_run")
public class DeepResearchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String prompt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private long elapsedMs;

    @Column(nullable = false)
    private int sourceCount;

    @Column(nullable = false)
    private int newDocCount;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String contentJson;

    /** Id các RawDoc mới nạp vào pipeline từ lần chạy này, nối bằng dấu phẩy — rỗng nếu không
     *  nạp được tài liệu nào (vd toàn bộ nguồn tìm được bị từ chối/trùng). */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String newDocIdsCsv;

    protected DeepResearchRun() {}

    public DeepResearchRun(String prompt, long elapsedMs, int sourceCount, int newDocCount,
                           String contentJson, String newDocIdsCsv) {
        this.prompt = prompt;
        this.elapsedMs = elapsedMs;
        this.sourceCount = sourceCount;
        this.newDocCount = newDocCount;
        this.contentJson = contentJson;
        this.newDocIdsCsv = newDocIdsCsv;
    }

    public Long getId() { return id; }
    public String getPrompt() { return prompt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getElapsedMs() { return elapsedMs; }
    public int getSourceCount() { return sourceCount; }
    public int getNewDocCount() { return newDocCount; }
    public String getContentJson() { return contentJson; }
    public String getNewDocIdsCsv() { return newDocIdsCsv; }

    public java.util.List<Long> newDocIds() {
        if (newDocIdsCsv == null || newDocIdsCsv.isBlank()) return java.util.List.of();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String part : newDocIdsCsv.split(",")) {
            if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
        }
        return ids;
    }

    /** Short label for list views — first ~90 chars of the prompt. */
    public String shortPrompt() {
        String p = prompt.strip();
        return p.length() <= 90 ? p : p.substring(0, 90) + "…";
    }
}
