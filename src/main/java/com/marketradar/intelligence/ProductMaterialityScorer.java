package com.marketradar.intelligence;

import com.marketradar.domain.Classification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;

/** Thin domain adapter around {@link ProductMaterialityRules}. */
@Service
public class ProductMaterialityScorer {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final Clock clock;

    public ProductMaterialityScorer() {
        this(Clock.system(REPORT_ZONE));
    }

    ProductMaterialityScorer(Clock clock) {
        this.clock = clock;
    }

    public ProductMaterialityRules.Score score(EvidenceFact fact, Classification classification) {
        if (fact == null) throw new IllegalArgumentException("fact is required");
        RawDoc doc = fact.getRawDoc();
        Set<String> labels = classification == null
                ? Set.of()
                : classification.getLabels().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
        String classificationStatus = classification == null ? null : classification.getStatus().name();
        LocalDate publishedDate = doc == null || doc.getPublishedAt() == null
                ? null : doc.getPublishedAt().atZone(REPORT_ZONE).toLocalDate();

        ProductMaterialityRules.Input input = new ProductMaterialityRules.Input(
                fact.getFactType() == null ? null : fact.getFactType().name(),
                labels,
                classificationStatus,
                doc == null ? null : doc.getTitle(),
                fact.getSpanText(),
                firstNonBlank(fact.getSummaryEn(), fact.getSummaryVi()),
                doc == null ? null : doc.getRawText(),
                fact.getCompany(),
                fact.getProductName(),
                publishedDate,
                fact.getEventDate(),
                LocalDate.now(clock),
                doc != null && doc.isFullTextFetched(),
                doc == null || doc.getParseStatus() == null ? null : doc.getParseStatus().name(),
                doc != null && doc.getDuplicateOfId() != null,
                doc == null || doc.getSource() == null ? null : doc.getSource().getTier());
        return ProductMaterialityRules.score(input);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
