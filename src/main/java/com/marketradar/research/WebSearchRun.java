package com.marketradar.research;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 2026-08-03: lịch sử Web Search (tìm nhanh, không AI, đồng bộ) — nhẹ hơn hẳn DeepResearchRun
 * vì search chạy xong ngay trong 1 request (vài giây), không cần status/queue/progress, chỉ cần
 * ghi lại query + số kết quả để có trang "Quản lý" xem lại đã tìm gì (yêu cầu Strategy: mỗi mục
 * Web Search / Deep Research đều có Create + Management).
 */
@Entity
@Table(name = "web_search_run")
public class WebSearchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String query;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private int resultCount;

    protected WebSearchRun() {}

    public WebSearchRun(String query, int resultCount) {
        this.query = query;
        this.resultCount = resultCount;
    }

    public Long getId() { return id; }
    public String getQuery() { return query; }
    public Instant getCreatedAt() { return createdAt; }
    public int getResultCount() { return resultCount; }
}
