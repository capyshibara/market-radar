package com.marketradar.research;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-08-03: bản ghi lâu dài cho mỗi lần chạy Deep Research — trước đây kết quả chỉ sống
 * trong {@link DeepResearchResultCache} (bộ nhớ tiến trình, tối đa 20 kết quả, MẤT khi app
 * restart) và không có nơi nào trong app liệt kê lại các lần đã chạy.
 *
 * 2026-08-03 (v2, feedback "cho vào hàng đợi, có màn hình theo dõi"): trước đây 1 lần chạy chỉ
 * tồn tại gắn với ĐÚNG request HTTP đã submit nó (SSE hoặc sync POST) — đóng tab là mất theo dõi,
 * và không nộp được nhiều prompt cùng lúc. Giờ một run được TẠO ở trạng thái QUEUED ngay khi nộp
 * (không cần đợi worker rảnh), rồi {@link DeepResearchQueueService} — 1 worker nền xử lý TUẦN TỰ
 * (giữ nguyên tính an toàn "không chạy song song 2 lần Deep Research" đã quyết định trước đó,
 * tránh đụng độ mã claim/pipeline) — cập nhật progressLog/status trực tiếp vào DB khi chạy, nên
 * xem tiến trình được từ BẤT KỲ tab/thiết bị nào, không phụ thuộc request gốc còn mở hay không.
 *
 * contentJson lưu nguyên {@link com.marketradar.report.bi.BiReportContent} đã tổng hợp (bản xem
 * nhanh, CHƯA qua Fact-checker/Verifier) để render lại đúng như lúc vừa chạy xong, không cần
 * chạy lại agent. newDocIdsCsv lưu id các RawDoc THẬT đã nạp vào pipeline xác thực từ lần chạy
 * này — dùng để tra cứu claim/trạng thái duyệt phát sinh từ đó, trả lời câu hỏi "nó chui vào báo
 * cáo chung như nào".
 */
@Entity
@Table(name = "deep_research_run")
public class DeepResearchRun {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String prompt;

    // 2026-08-03: KHÔNG nullable=false ở đây dù luôn có giá trị cho run mới — bảng này đã tồn
    // tại từ trước lần thêm 2 cột này (bản ghi cũ trước khi có hàng đợi). Hibernate ddl-auto:
    // update thêm cột NOT NULL vào bảng ĐÃ CÓ DỮ LIỆU sẽ tự làm ALTER TABLE thất bại (không có
    // default để backfill hàng cũ) — quan sát thật: app crash ngay lúc khởi động (DeepResearchQueueService
    // đọc bảng ngay trong @PostConstruct, sớm hơn mọi migration ApplicationRunner). Để nullable
    // ở tầng DB, chỉ ràng buộc "luôn có giá trị cho run mới" ở tầng Java qua giá trị khởi tạo
    // + constructor — bản ghi CŨ (trước khi có 2 cột này) có status/queuedAt = null, xử lý an
    // toàn ở DeepResearchHistoryController thay vì bắt DB migrate ngược cho dữ liệu không còn
    // quan trọng (chỉ là bản xem nhanh Deep Research, không phải claim/evidence thật).
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status = Status.QUEUED;

    private Instant queuedAt = Instant.now();

    private Instant startedAt;
    private Instant finishedAt;

    /** Từng dòng tiến trình (giống onStep của DeepResearchService), nối bằng \n, nối thêm khi
     *  worker chạy — cho phép mở lại trang bất cứ lúc nào và thấy log gần nhất, không chỉ khi
     *  request gốc còn sống. */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String progressLog = "";

    @Column(nullable = false)
    private long elapsedMs;

    @Column(nullable = false)
    private int sourceCount;

    @Column(nullable = false)
    private int newDocCount;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String contentJson;

    /** Id các RawDoc mới nạp vào pipeline từ lần chạy này, nối bằng dấu phẩy — rỗng nếu không
     *  nạp được tài liệu nào (vd toàn bộ nguồn tìm được bị từ chối/trùng). */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String newDocIdsCsv;

    protected DeepResearchRun() {}

    /** Tạo ở trạng thái QUEUED — chỉ cần prompt, mọi thứ khác điền dần khi worker xử lý. */
    public DeepResearchRun(String prompt) {
        this.prompt = prompt;
    }

    public Long getId() { return id; }
    public String getPrompt() { return prompt; }
    public Status getStatus() { return status; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getProgressLog() { return progressLog; }
    public long getElapsedMs() { return elapsedMs; }
    public int getSourceCount() { return sourceCount; }
    public int getNewDocCount() { return newDocCount; }
    public String getContentJson() { return contentJson; }
    public String getNewDocIdsCsv() { return newDocIdsCsv; }

    public void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    public void appendStep(String line) {
        this.progressLog = (this.progressLog == null || this.progressLog.isBlank())
                ? line : this.progressLog + "\n" + line;
    }

    public void markDone(int sourceCount, int newDocCount, String contentJson, String newDocIdsCsv) {
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
        this.elapsedMs = startedAt == null ? 0 : finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        this.sourceCount = sourceCount;
        this.newDocCount = newDocCount;
        this.contentJson = contentJson;
        this.newDocIdsCsv = newDocIdsCsv;
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
        this.elapsedMs = startedAt == null ? 0 : finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        appendStep("LỖI: " + reason);
    }

    public List<Long> newDocIds() {
        if (newDocIdsCsv == null || newDocIdsCsv.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
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
