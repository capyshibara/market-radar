package com.marketradar.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 2026-08-03 (feedback: "màn hình cho phép nhập nhiều prompt và kích hoạt nhiều deep research
 * cùng lúc... cho vào hàng đợi... màn hình theo dõi trạng thái"): nhận N prompt cùng lúc, chạy
 * TUẦN TỰ qua đúng 1 worker nền — giữ nguyên quyết định trước đó (xem reset_and_run_overnight.sh)
 * KHÔNG chạy song song 2 lần Deep Research, vì mỗi lần đều tự gọi thẳng Classify/Extract/
 * Interpret/Verify (bỏ qua khoá toàn cục của PipelineRunStatusService) — chạy đồng thời có rủi ro
 * thật (đụng độ mã claim, xử lý trùng tài liệu). Hàng đợi giải quyết đúng nhu cầu "nộp nhiều cùng
 * lúc" mà KHÔNG cần chạy song song: nộp xong việc ngay, xử lý lần lượt ở nền.
 *
 * enqueue() tạo {@link DeepResearchRun} ở trạng thái QUEUED và trả về ngay (không đợi worker) —
 * người dùng thấy job xuất hiện trong hàng đợi tức thì, đóng tab vẫn không mất tiến trình vì
 * worker ghi progressLog/status thẳng vào DB, xem lại được từ bất kỳ tab/thiết bị nào.
 */
@Service
public class DeepResearchQueueService {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchQueueService.class);

    private final DeepResearchRunRepository runs;
    private final DeepResearchService deepResearch;
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<Long> pending = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "deep-research-queue-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean shuttingDown = false;

    public DeepResearchQueueService(DeepResearchRunRepository runs, DeepResearchService deepResearch) {
        this.runs = runs;
        this.deepResearch = deepResearch;
        worker.execute(this::workerLoop);
    }

    /** Hàng đợi trong bộ nhớ KHÔNG sống qua restart — mọi run còn QUEUED/RUNNING từ phiên
     *  trước (app bị tắt/crash giữa chừng) không bao giờ được worker mới nhặt lại, nên phải
     *  đánh dấu rõ FAILED thay vì để treo mãi ở màn hình theo dõi. */
    @PostConstruct
    public void recoverStaleRunsFromPreviousBoot() {
        List<DeepResearchRun> stale = runs.findByStatusIn(
                List.of(DeepResearchRun.Status.QUEUED, DeepResearchRun.Status.RUNNING));
        for (DeepResearchRun r : stale) {
            r.markFailed("App khởi động lại giữa chừng — hàng đợi trong bộ nhớ đã mất. Nộp lại yêu cầu này nếu cần.");
            runs.save(r);
        }
        if (!stale.isEmpty()) {
            log.warn("Deep Research queue: đánh dấu FAILED {} run còn dang dở từ phiên chạy trước.", stale.size());
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        worker.shutdownNow();
    }

    public Long enqueue(String prompt) {
        return enqueue(prompt, null, null, false);
    }

    public Long enqueue(String prompt, java.time.LocalDate rangeStart, java.time.LocalDate rangeEnd) {
        return enqueue(prompt, rangeStart, rangeEnd, false);
    }

    public Long enqueue(String prompt, java.time.LocalDate rangeStart, java.time.LocalDate rangeEnd, boolean vietnamOnly) {
        DeepResearchRun run = runs.save(new DeepResearchRun(prompt, rangeStart, rangeEnd, vietnamOnly));
        pending.add(run.getId());
        return run.getId();
    }

    private void workerLoop() {
        while (!shuttingDown) {
            try {
                Long id = pending.take();
                processOne(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception unexpected) {
                log.error("Deep Research queue worker: lỗi không lường trước, tiếp tục vòng lặp: {}",
                        unexpected.toString(), unexpected);
            }
        }
    }

    private void processOne(Long id) {
        DeepResearchRun run = runs.findById(id).orElse(null);
        if (run == null) return; // bị xoá trước khi worker kịp xử lý — bỏ qua an toàn
        run.markRunning();
        runs.save(run);
        try {
            DeepResearchService.ResearchResult result = deepResearch.research(run.getPrompt(),
                    run.getRangeStart(), run.getRangeEnd(), run.isVietnamOnly(), step -> {
                // Ghi ngay khi có bước mới — đây là lý do xem được tiến trình từ tab khác/sau khi
                // đóng tab gốc, khác hẳn SSE cũ chỉ sống trong đúng 1 request.
                DeepResearchRun fresh = runs.findById(id).orElse(null);
                if (fresh == null) return;
                fresh.appendStep(step);
                runs.save(fresh);
            });
            DeepResearchRun done = runs.findById(id).orElse(run);
            String contentJson = mapper.writeValueAsString(result.content());
            String newDocIdsCsv = result.newDocIds().isEmpty() ? null
                    : result.newDocIds().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
            done.markDone(result.sourceCount(), result.newDocIds().size(), contentJson, newDocIdsCsv);
            runs.save(done);
        } catch (Exception e) {
            log.error("Deep Research queue: run #{} lỗi: {}", id, e.getMessage(), e);
            DeepResearchRun failed = runs.findById(id).orElse(run);
            failed.markFailed(e.getMessage() == null ? e.toString() : e.getMessage());
            runs.save(failed);
        }
    }
}
