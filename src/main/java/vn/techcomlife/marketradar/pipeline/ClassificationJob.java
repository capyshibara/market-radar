package vn.techcomlife.marketradar.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.techcomlife.marketradar.classify.Router;
import vn.techcomlife.marketradar.classify.TopicClassifier;
import vn.techcomlife.marketradar.domain.Classification;
import vn.techcomlife.marketradar.domain.RawDoc;
import vn.techcomlife.marketradar.repo.ClassificationRepository;
import vn.techcomlife.marketradar.repo.RawDocRepository;

/**
 * Bước 4 pipeline: phân loại (AI#1) + routing (bảng tra) cho các RawDoc
 * đã ingest OK và chưa được phân loại.
 */
@Service
public class ClassificationJob {

    private static final Logger log = LoggerFactory.getLogger(ClassificationJob.class);

    private final RawDocRepository rawDocs;
    private final ClassificationRepository classifications;
    private final TopicClassifier classifier;
    private final Router router;

    public ClassificationJob(RawDocRepository rawDocs, ClassificationRepository classifications,
                             TopicClassifier classifier, Router router) {
        this.rawDocs = rawDocs;
        this.classifications = classifications;
        this.classifier = classifier;
        this.router = router;
    }

    @Transactional
    public String runOnce() {
        int done = 0, skipped = 0;
        StringBuilder summary = new StringBuilder();
        for (RawDoc doc : rawDocs.findAll()) {
            if (doc.getParseStatus() != RawDoc.ParseStatus.OK) { skipped++; continue; }
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
        summary.insert(0, "Phân loại xong " + done + " doc, bỏ qua " + skipped + " (đã phân loại/parse lỗi)\n");
        return summary.toString();
    }
}
