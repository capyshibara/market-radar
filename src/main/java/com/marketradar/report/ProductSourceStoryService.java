package com.marketradar.report;

import com.marketradar.domain.Classification;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.RawDoc;
import com.marketradar.product.BilingualTextPolicy;
import com.marketradar.product.CurrentProductNewsTopic;
import com.marketradar.product.ProductMarketScope;
import com.marketradar.product.ProductMarketScopeClassifier;
import com.marketradar.repo.ClassificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reader-facing bridge from a report fact code to the complete stored source.
 *
 * <p>The bilingual retelling comes only from the extractor's stored summaries. The
 * surrounding context is deterministic Product reading guidance; the verbatim span
 * and full crawled document remain unchanged and visibly separate.</p>
 */
@Service
public class ProductSourceStoryService {
    private static final Pattern FACT_CODE = Pattern.compile("F-\\d+");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final EvidenceFactRepository facts;
    private final ClassificationRepository classifications;

    public ProductSourceStoryService(EvidenceFactRepository facts,
                                     ClassificationRepository classifications) {
        this.facts = facts;
        this.classifications = classifications;
    }

    @Transactional(readOnly = true)
    public SourceStory story(String requestedFactCode, Locale locale) {
        String factCode = normalizeFactCode(requestedFactCode);
        EvidenceFact fact = facts.findAllByFactCodeInForAudit(List.of(factCode)).stream()
                .filter(candidate -> factCode.equals(candidate.getFactCode()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Source story not found: " + factCode));
        RawDoc doc = fact.getRawDoc();
        boolean vi = "vi".equals(locale.getLanguage());
        Classification classification = classifications.findByRawDocIdIn(List.of(doc.getId())).stream()
                .findFirst().orElse(null);
        Set<String> labels = classification == null ? Set.of() : classification.getLabels().stream()
                .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        CurrentProductNewsTopic topic = CurrentProductNewsTopic.from(labels, fact.getFactType().name());
        ProductMarketScopeClassifier.MarketPosition market = ProductMarketScopeClassifier.classify(fact);

        String sourceName = doc.getPublisherName() == null || doc.getPublisherName().isBlank()
                ? doc.getSource().getName() : doc.getPublisherName().strip();
        String retelling = BilingualTextPolicy.safeDisplaySummary(
                vi ? fact.getSummaryVi() : fact.getSummaryEn(), vi);
        boolean translatedRetellingAvailable = !retelling.isBlank();
        if (!translatedRetellingAvailable) retelling = missingRetelling(vi);

        String fullText = doc.getRawText() == null ? "" : doc.getRawText();
        SourceText sourceText = splitSourceText(fullText, fact.getSpanText());
        return new SourceStory(factCode, doc.getId(), safeTitle(doc), sourceName,
                doc.getSource().getCode(), doc.getSource().getTier(), doc.getUrl(),
                hasExternalLink(doc.getUrl()), date(doc, vi), fetched(doc, vi),
                fact.getFactType().name(), languageLabel(fact.getSpanLanguage(), vi),
                intakeLabel(doc.getIntakeMethod(), vi), doc.isFullTextFetched(), fullText.length(),
                topic.label(vi), marketLabel(market.scope(), vi), geographyLabel(market.geography(), vi),
                retelling, translatedRetellingAvailable, topic.readerContext(vi),
                topic.productMeaning(vi), topic.reviewQuestion(vi), topic.validationStep(vi),
                limitation(market.scope(), vi), fact.getSpanText(), sourceText);
    }

    static SourceText splitSourceText(String fullText, String evidence) {
        String body = fullText == null ? "" : fullText;
        String span = evidence == null ? "" : evidence;
        if (body.isBlank()) return new SourceText("", span, "", span, false);
        int position = span.isBlank() ? -1 : body.indexOf(span);
        if (position < 0) return new SourceText("", span, "", body, false);
        return new SourceText(body.substring(0, position), span,
                body.substring(position + span.length()), body, true);
    }

    private static String normalizeFactCode(String requested) {
        String normalized = requested == null ? "" : requested.strip().toUpperCase(Locale.ROOT);
        if (!FACT_CODE.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid source story code");
        }
        return normalized;
    }

    private static String safeTitle(RawDoc doc) {
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) return doc.getTitle().strip();
        if (doc.getOriginalFilename() != null && !doc.getOriginalFilename().isBlank()) {
            return doc.getOriginalFilename().strip();
        }
        return "Document #" + doc.getId();
    }

    private static String date(RawDoc doc, boolean vi) {
        if (doc.getPublishedAt() == null) return vi ? "Không có ngày công bố" : "Publication date unavailable";
        return DateTimeFormatter.ofPattern(vi ? "dd/MM/yyyy" : "MMM d, yyyy",
                        vi ? Locale.forLanguageTag("vi") : Locale.ENGLISH)
                .withZone(REPORT_ZONE).format(doc.getPublishedAt());
    }

    private static String fetched(RawDoc doc, boolean vi) {
        if (doc.getFetchedAt() == null) return "—";
        return DateTimeFormatter.ofPattern(vi ? "dd/MM/yyyy HH:mm" : "MMM d, yyyy HH:mm",
                        vi ? Locale.forLanguageTag("vi") : Locale.ENGLISH)
                .withZone(REPORT_ZONE).format(doc.getFetchedAt());
    }

    private static String missingRetelling(boolean vi) {
        return vi
                ? "Chưa có bản kể lại tiếng Việt vượt qua kiểm tra ngôn ngữ. Hãy đọc phần bằng chứng được tô sáng và toàn văn nguồn bên dưới; hệ thống không tự đoán bản dịch còn thiếu."
                : "No English retelling passed the language check. Read the highlighted evidence and full source below; the system does not invent a missing translation.";
    }

    private static String limitation(ProductMarketScope scope, boolean vi) {
        if (scope == ProductMarketScope.VIETNAM) {
            return vi
                    ? "Đây là một hồ sơ nguồn Việt Nam đã được truy vết. Nó hỗ trợ sự kiện được mô tả, nhưng một nguồn riêng lẻ chưa chứng minh nhu cầu khách hàng, lợi nhuận hoặc xu hướng toàn thị trường."
                    : "This is one traceable Vietnam source record. It supports the event described, but one source does not prove customer demand, profitability or a market-wide trend.";
        }
        return vi
                ? "Câu chuyện xảy ra ngoài Việt Nam. Nó là điểm đối chiếu để đặt câu hỏi, không phải bằng chứng rằng cơ hội, quy định hoặc hành vi khách hàng tương tự sẽ áp dụng tại Việt Nam."
                : "This story comes from outside Vietnam. It is a comparator for asking questions—not proof that the same opportunity, regulation or customer behaviour applies in Vietnam.";
    }

    private static String marketLabel(ProductMarketScope scope, boolean vi) {
        return scope == ProductMarketScope.VIETNAM
                ? (vi ? "Việt Nam" : "Vietnam") : (vi ? "Quốc tế" : "International");
    }

    private static String geographyLabel(String geography, boolean vi) {
        if (!vi) return geography;
        return switch (geography) {
            case "Vietnam" -> "Việt Nam";
            case "South Korea" -> "Hàn Quốc";
            case "Japan" -> "Nhật Bản";
            case "China" -> "Trung Quốc";
            case "Global / regional" -> "Toàn cầu / khu vực";
            default -> geography;
        };
    }

    private static String languageLabel(String language, boolean vi) {
        if (vi) {
            return switch (language == null ? "" : language) {
                case "vi" -> "Tiếng Việt";
                case "en" -> "Tiếng Anh";
                case "zh" -> "Tiếng Trung";
                case "ja" -> "Tiếng Nhật";
                case "ko" -> "Tiếng Hàn";
                default -> "Ngôn ngữ gốc";
            };
        }
        return switch (language == null ? "" : language) {
            case "vi" -> "Vietnamese";
            case "en" -> "English";
            case "zh" -> "Chinese";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            default -> "Original language";
        };
    }

    private static String intakeLabel(RawDoc.IntakeMethod intake, boolean vi) {
        RawDoc.IntakeMethod safe = intake == null ? RawDoc.IntakeMethod.CRAWLED : intake;
        if (vi) {
            return switch (safe) {
                case CRAWLED -> "Thu thập tự động";
                case MANUAL_TEXT -> "Văn bản nhập thủ công";
                case FILE_UPLOAD -> "Tệp tải lên";
            };
        }
        return switch (safe) {
            case CRAWLED -> "Automatically crawled";
            case MANUAL_TEXT -> "Manually pasted text";
            case FILE_UPLOAD -> "Uploaded file";
        };
    }

    private static boolean hasExternalLink(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    public record SourceStory(String factCode, long rawDocId, String originalTitle,
                              String sourceName, String sourceCode, int sourceTier,
                              String sourceUrl, boolean externalSourceLink,
                              String publishedLabel, String fetchedLabel, String factType,
                              String evidenceLanguageLabel, String intakeLabel,
                              boolean fullTextAvailable, int textCharacters,
                              String topicLabel, String marketLabel, String geographyLabel,
                              String retelling, boolean translatedRetellingAvailable,
                              String context, String productMeaning, String reviewQuestion,
                              String validationStep, String limitation,
                              String originalEvidence, SourceText sourceText) {}

    public record SourceText(String before, String evidence, String after,
                             String fullText, boolean matched) {}
}
