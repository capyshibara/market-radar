package com.marketradar.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.marketradar.classify.Router;
import com.marketradar.classify.TopicClassifier;
import com.marketradar.dedup.DedupJob;
import com.marketradar.domain.Classification;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.RawDocRepository;

/**
 * Bước 4 pipeline: phân loại (AI#1) + routing (bảng tra) cho các RawDoc
 * đã ingest OK và chưa được phân loại.
 *
 * Dedup LUÔN chạy trước classify trong cùng lần gọi (không phụ thuộc thứ tự
 * curl tay) — RawDoc đã bị đánh dấu duplicateOfId (bản thua) thì KHÔNG tốn
 * LLM call phân loại, vì báo cáo cuối cùng lọc bản trùng ra rồi (ReportController).
 */
@Service
public class ClassificationJob {

    private static final Logger log = LoggerFactory.getLogger(ClassificationJob.class);

    private final RawDocRepository rawDocs;
    private final ClassificationRepository classifications;
    private final TopicClassifier classifier;
    private final Router router;
    private final DedupJob dedupJob;

    public ClassificationJob(RawDocRepository rawDocs, ClassificationRepository classifications,
                             TopicClassifier classifier, Router router, DedupJob dedupJob) {
        this.rawDocs = rawDocs;
        this.classifications = classifications;
        this.classifier = classifier;
        this.router = router;
        this.dedupJob = dedupJob;
    }

    /**
     * KHÔNG @Transactional (bỏ 2026-07-13): trước đây cả vòng chạy là MỘT transaction —
     * classify DeepSeek giờ cao điểm mất hàng giờ, suốt thời gian đó không thấy tiến độ,
     * và Ctrl+C/crash mất TOÀN BỘ kết quả + LlmCallLog (mất luôn replay-cache đã trả tiền).
     * Giờ mỗi doc tự commit (save từng entity = transaction riêng): tiến độ nhìn được
     * ngay ở /classifications, chạy lại tiếp tục từ chỗ dừng nhờ guard existsByRawDoc.
     * DedupJob giữ @Transactional riêng của nó (gọi qua proxy — nhanh, ~10s).
     */
    public String runOnce() {
        String dedupSummary = dedupJob.runOnce();

        int done = 0, skipped = 0, skippedDuplicate = 0;
        StringBuilder summary = new StringBuilder();
        for (RawDoc doc : rawDocs.findAll()) {
            if (doc.getParseStatus() != RawDoc.ParseStatus.OK) { skipped++; continue; }
            if (doc.getDuplicateOfId() != null) { skippedDuplicate++; continue; }
            if (classifications.existsByRawDoc(doc)) { skipped++; continue; }
            try {
                Classification c = classifier.classify(doc);
                router.route(c);
                classifications.save(c);
                done++;
                summary.append("doc#").append(doc.getId()).append(": ")
                       .append(c.getStatus()).append(' ').append(c.getLabels())
                       .append(" → ").append(c.getRoutingStatus())
                       .append(' ').append(c.getDepartments()).append('\n');
            } catch (Exception e) {
                log.error("Classify lỗi doc#{}", doc.getId(), e);
                summary.append("doc#").append(doc.getId()).append(": ERROR — ")
                       .append(e.getMessage()).append('\n');
            }
        }
        summary.insert(0, "Classified " + done + " doc(s), skipped " + skipped
                + " (already classified/parse error), skipped " + skippedDuplicate
                + " (duplicate — filtered by dedup before costing an LLM call)\n"
                + "--- Dedup (runs before classify) ---\n" + dedupSummary + "---\n");
        return summary.toString();
    }
}
