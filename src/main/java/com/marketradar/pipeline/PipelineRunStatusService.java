package com.marketradar.pipeline;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        statuses.put(stage, new StageStatus(RunState.RUNNING, start, null, null, null));
        executor.submit(() -> {
            try {
                String output = job.get();
                statuses.put(stage, new StageStatus(RunState.SUCCESS, start, Instant.now(), output, null));
            } catch (Exception e) {
                statuses.put(stage, new StageStatus(RunState.FAILED, start, Instant.now(), null,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
        return true;
    }
}
