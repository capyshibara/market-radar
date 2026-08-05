import com.marketradar.domain.PipelineRunLog;
import com.marketradar.pipeline.PipelineRunStatusService;
import com.marketradar.repo.PipelineRunLogRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Regression guard: an uncaught task Error must never strand a stage in RUNNING. */
public class PipelineRunStatusServiceFailureTest {

    public static void main(String[] args) throws Exception {
        AtomicReference<PipelineRunLog> stored = new AtomicReference<>();
        PipelineRunLogRepository repository = (PipelineRunLogRepository) Proxy.newProxyInstance(
                PipelineRunLogRepository.class.getClassLoader(),
                new Class<?>[]{PipelineRunLogRepository.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "maxBatchId" -> Optional.of(1);
                    case "save" -> {
                        PipelineRunLog row = (PipelineRunLog) methodArgs[0];
                        if (row.getId() == null) setId(row, 1L);
                        stored.set(row);
                        yield row;
                    }
                    case "findById" -> Optional.ofNullable(stored.get());
                    case "toString" -> "PipelineRunLogRepoStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == methodArgs[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PipelineRunStatusService service = new PipelineRunStatusService(repository);
        assert service.trigger("extract", () -> { throw new AssertionError("simulated worker error"); });
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (service.get("extract").state() == PipelineRunStatusService.RunState.RUNNING
                && System.nanoTime() < deadline) Thread.sleep(10);

        assert service.get("extract").state() == PipelineRunStatusService.RunState.FAILED
                : service.get("extract");
        assert service.get("extract").error().contains("simulated worker error")
                : service.get("extract");
        assert stored.get() != null && "FAILED".equals(stored.get().getState()) : stored.get();
        System.out.println("PipelineRunStatusServiceFailureTest: ALL PASS");
    }

    private static void setId(PipelineRunLog row, long id) throws Exception {
        Field field = PipelineRunLog.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(row, id);
    }
}
