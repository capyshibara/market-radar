package com.marketradar.classify;

import com.marketradar.domain.Classification;
import com.marketradar.domain.Department;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only desk view over persisted routing decisions. The desk shows exactly what
 * {@link Router} wrote (departments + audit note) — it never re-routes or re-labels.
 */
@Service
public class DeskService {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final int DETAIL_LIMIT = 100;

    private final ClassificationRepository classifications;
    private final EvidenceFactRepository facts;

    public DeskService(ClassificationRepository classifications, EvidenceFactRepository facts) {
        this.classifications = classifications;
        this.facts = facts;
    }

    @Transactional(readOnly = true)
    public List<DeskSummary> overview() {
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        return java.util.Arrays.stream(Department.values()).map(dept -> {
            List<Classification> routed = classifications.findRoutedByDepartment(dept);
            long fresh = routed.stream().filter(c -> c.getCreatedAt().isAfter(weekAgo)).count();
            return new DeskSummary(dept, routed.size(), fresh,
                    toItems(routed.stream().limit(3).toList()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<DeskItem> deskItems(Department dept) {
        return toItems(classifications.findRoutedByDepartment(dept).stream()
                .limit(DETAIL_LIMIT).toList());
    }

    private List<DeskItem> toItems(List<Classification> routed) {
        List<Long> docIds = routed.stream().map(c -> c.getRawDoc().getId()).toList();
        Map<Long, String> firstFactByDoc = new HashMap<>();
        if (!docIds.isEmpty()) {
            for (EvidenceFact fact : facts.findActiveByRawDocIdIn(docIds)) {
                firstFactByDoc.putIfAbsent(fact.getRawDoc().getId(), fact.getFactCode());
            }
        }
        return routed.stream().map(c -> {
            var doc = c.getRawDoc();
            String url = doc.getUrl() == null ? "" : doc.getUrl();
            return new DeskItem(doc.getId(),
                    doc.getTitle() == null || doc.getTitle().isBlank() ? "Document #" + doc.getId() : doc.getTitle(),
                    url, url.startsWith("https://") || url.startsWith("http://"),
                    doc.getPublisherName() == null || doc.getPublisherName().isBlank()
                            ? doc.getSource().getName() : doc.getPublisherName(),
                    doc.getPublishedAt() == null ? "—" : DATE.format(doc.getPublishedAt()),
                    c.getLabels().stream().map(Enum::name).sorted().toList(),
                    c.getDepartments().stream().map(Enum::name).sorted().toList(),
                    c.getNote() == null ? "" : c.getNote(),
                    firstFactByDoc.get(doc.getId()));
        }).toList();
    }

    public record DeskSummary(Department dept, long total, long newThisWeek, List<DeskItem> recent) {}

    public record DeskItem(long docId, String title, String url, boolean externalLink,
                           String sourceName, String publishedLabel, List<String> labels,
                           List<String> departments, String routingNote, String factCode) {}
}
