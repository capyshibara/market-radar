package com.marketradar.prompt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Batch 12: nguồn prompt cho mọi stage AI. Mỗi stage ĐĂNG KÝ prompt mặc định của nó lúc
 * khởi động (registerDefault); khi gọi body(key) sẽ trả về BẢN GHI ĐÈ từ DB nếu ops đã sửa,
 * ngược lại trả prompt mặc định. Sửa ở /prompts áp dụng NGAY (lần gọi AI kế tiếp), không
 * cần khởi động lại.
 *
 * Lưu ý cache: thay đổi prompt làm đổi hash replay-cache (hash gồm cả system prompt), nên
 * prompt mới tự động không trúng cache cũ — lần chạy kế tiếp gọi LLM thật.
 */
@Service
public class PromptService {

    private final PromptOverrideRepository repo;
    private final Map<PromptKey, String> defaults = new EnumMap<>(PromptKey.class);

    public PromptService(PromptOverrideRepository repo) {
        this.repo = repo;
    }

    /** Stage gọi lúc khởi tạo để công bố prompt mặc định của mình. */
    public void registerDefault(PromptKey key, String body) {
        defaults.put(key, body);
    }

    /** Prompt hiệu lực: bản ghi đè DB nếu có, ngược lại mặc định đã đăng ký. */
    public String body(PromptKey key) {
        return repo.findByPromptKey(key.name())
                .map(PromptOverride::getBody)
                .filter(b -> b != null && !b.isBlank())
                .orElseGet(() -> defaults.getOrDefault(key, ""));
    }

    public String defaultBody(PromptKey key) { return defaults.getOrDefault(key, ""); }

    public boolean isOverridden(PromptKey key) { return repo.findByPromptKey(key.name()).isPresent(); }

    @Transactional
    public void save(PromptKey key, String body, String by) {
        repo.findByPromptKey(key.name())
                .ifPresentOrElse(o -> o.update(body, by),
                        () -> repo.save(new PromptOverride(key.name(), body, by)));
    }

    @Transactional
    public void reset(PromptKey key) {
        repo.findByPromptKey(key.name()).ifPresent(repo::delete);
    }

    /** Hàng hiển thị cho ops page: key, nhãn, mô tả, prompt hiệu lực, có đang ghi đè không. */
    public record Row(PromptKey key, String effectiveBody, boolean overridden) {}

    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (PromptKey k : PromptKey.values()) out.add(new Row(k, body(k), isOverridden(k)));
        return out;
    }
}
