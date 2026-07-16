package com.marketradar.pipeline;

import org.springframework.stereotype.Service;
import com.marketradar.domain.PipelineRunLog;
import com.marketradar.repo.PipelineRunLogRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Batch 9 (feedback Hanh — "hard to tell if it's running"): chạy job pipeline
 * trên MỘT executor nền, trả HTTP response ngay lập tức thay vì block cả phút,
 * và theo dõi trạng thái từng stage để UI poll hiển thị RUNNING/SUCCESS/FAILED.
 *
 * Single-thread executor: các stage phụ thuộc thứ tự nhau (ingest → classify →
 * ...) nên chạy tuần tự tự nhiên là đúng; đồng thời chặn 2 job chạy chồng lên
 * nhau tranh chấp cùng DB/API rate limit.
 */
@Service
public class PipelineRunStatusService {

    public enum RunState { IDLE, RUNNING, SUCCESS, FAILED }

    public record StageStatus(RunState state, Instant startedAt, Instant finishedAt, String output, String error) {
        static StageStatus idle() { return new StageStatus(RunState.IDLE, null, null, null, null); }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-runner");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, StageStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Long> currentRunLogId = new ConcurrentHashMap<>();
    private final PipelineRunLogRepository runLogRepo;

    public PipelineRunStatusService(PipelineRunLogRepository runLogRepo) {
        this.runLogRepo = runLogRepo;
    }

    /**
     * Job dùng để gắn PipelineItemLog vào ĐÚNG lần chạy hiện tại của stage mình.
     * A scheduled/direct invocation outside {@link #trigger} must not append
     * events to an old completed run merely because its id remains in memory.
     */
    public Long currentRunLogId(String stage) {
        return get(stage).state() == RunState.RUNNING ? currentRunLogId.get(stage) : null;
    }

    /** Batch 10 (feedback Hanh — "very very helpful" progress bar): completed/total
     * per running stage, set by the job itself (only it knows its own loop size). */
    public record Progress(int completed, int total) {}

    private static final class ProgressHolder {
        final AtomicInteger completed = new AtomicInteger(0);
        final int total;
        ProgressHolder(int total) { this.total = total; }
    }

    private final Map<String, ProgressHolder> progress = new ConcurrentHashMap<>();

    /** Job gọi lúc bắt đầu vòng lặp — total = số item sẽ xử lý (đã biết trước, vd list.size()). */
    public void startProgress(String stage, int total) { progress.put(stage, new ProgressHolder(total)); }

    /** Job gọi sau MỖI item xử lý xong (thành công hay lỗi đều tính — đây là tiến độ chạy qua,
     * không phải tỷ lệ thành công). */
    public void stepProgress(String stage) {
        ProgressHolder p = progress.get(stage);
        if (p != null) p.completed.incrementAndGet();
    }

    public Progress getProgress(String stage) {
        ProgressHolder p = progress.get(stage);
        return p == null ? null : new Progress(p.completed.get(), p.total);
    }

    public StageStatus get(String stage) { return statuses.getOrDefault(stage, StageStatus.idle()); }

    public Map<String, StageStatus> all(String... stageOrder) {
        Map<String, StageStatus> m = new LinkedHashMap<>();
        for (String s : stageOrder) m.put(s, get(s));
        return m;
    }

    public boolean anyRunning() {
        return statuses.values().stream().anyMatch(s -> s.state() == RunState.RUNNING);
    }

    /** @return false nếu có stage khác đang RUNNING (không submit — tránh chồng job). */
    public synchronized boolean trigger(String stage, Supplier<String> job) {
        if (anyRunning()) return false;
        Instant start = Instant.now();
        progress.remove(stage); // xoá tiến độ lần chạy trước — job tự startProgress() lại nếu cần
        statuses.put(stage, new StageStatus(RunState.RUNNING, start, null, null, null));

        int batchId = "ingest".equals(stage)
                ? runLogRepo.maxBatchId().orElse(0) + 1
                : Math.max(runLogRepo.maxBatchId().orElse(1), 1);
        Long runLogId = runLogRepo.save(new PipelineRunLog(stage, batchId, start)).getId();
        currentRunLogId.put(stage, runLogId);

        executor.submit(() -> {
            try {
                String output = job.get();
                statuses.put(stage, new StageStatus(RunState.SUCCESS, start, Instant.now(), output, null));
                finishRunLog(runLogId, "SUCCESS", stage, null);
            } catch (Exception e) {
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                statuses.put(stage, new StageStatus(RunState.FAILED, start, Instant.now(), null, err));
                finishRunLog(runLogId, "FAILED", stage, err);
            }
        });
        return true;
    }

    private void finishRunLog(Long runLogId, String state, String stage, String error) {
        Progress p = getProgress(stage);
        runLogRepo.findById(runLogId).ifPresent(log -> {
            log.finish(state, Instant.now(), p == null ? 0 : p.completed(), p == null ? 0 : p.total(), error);
            runLogRepo.save(log);
        });
    }
}
