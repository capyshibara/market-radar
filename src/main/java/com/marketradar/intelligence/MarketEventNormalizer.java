package com.marketradar.intelligence;

import org.springframework.stereotype.Component;
import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.MarketEvent;
import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;

/** Pure deterministic mapping from evidence to a normalized market event. */
@Component
public class MarketEventNormalizer {

    public static final String PIPELINE_VERSION = "market-event-v2-temporal";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public MarketEvent normalize(EvidenceFact fact, String modelVersion) {
        if (fact == null || fact.getRawDoc() == null || fact.getRawDoc().getSource() == null) {
            throw new IllegalArgumentException("fact, rawDoc and source are required");
        }
        if (fact.getFactCode() == null || fact.getFactCode().isBlank()) {
            throw new IllegalArgumentException("factCode is required");
        }

        RawDoc doc = fact.getRawDoc();
        Source source = doc.getSource();
        LocalDate publishedDate = doc.getPublishedAt() == null ? null
                : doc.getPublishedAt().atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate sourceDate = fact.getEventDate();
        DateDimensions legacyDates = dateDimensions(fact.getFactType(), sourceDate, publishedDate);
        LocalDate occurred = first(fact.getOccurredDate(), legacyDates.occurredDate());
        LocalDate effective = first(fact.getEffectiveDate(), legacyDates.effectiveDate());
        LocalDate expiry = fact.getExpiryDate();
        LocalDate forecast = first(fact.getForecastHorizon(), legacyDates.forecastHorizon());
        MarketEvent.MarketScope scope = marketScope(source);

        return new MarketEvent(
                eventKey(fact.getFactCode(), PIPELINE_VERSION),
                fact,
                MarketEvent.EventType.valueOf(fact.getFactType().name()),
                scope,
                geography(source, scope),
                clean(fact.getCompany()),
                clean(fact.getProductName()),
                publishedDate,
                sourceDate,
                occurred,
                effective,
                expiry,
                forecast,
                source.getCode(),
                source.getTier(),
                PIPELINE_VERSION,
                clean(modelVersion) == null ? "UNKNOWN_LEGACY" : clean(modelVersion));
    }

    /**
     * The legacy extractor exposes one eventDate only. Rules below are deliberately
     * narrow and sourceEventDate is always retained so later versions can correct
     * semantics without losing provenance.
     */
    static DateDimensions dateDimensions(EvidenceFact.FactType type, LocalDate eventDate,
                                         LocalDate publishedDate) {
        // Without a publication baseline, or with a future launch/event date, the
        // legacy field is too ambiguous to assign a stronger semantic safely.
        if (eventDate == null || publishedDate == null) {
            return new DateDimensions(null, null, null);
        }
        boolean future = eventDate.isAfter(publishedDate);
        return switch (type) {
            case REGULATION, FEE_CHANGE -> future
                    ? new DateDimensions(null, eventDate, null)
                    : new DateDimensions(eventDate, null, null);
            case METRIC -> future
                    ? new DateDimensions(null, null, eventDate)
                    : new DateDimensions(eventDate, null, null);
            case EVENT, PRODUCT_LAUNCH -> future
                    ? new DateDimensions(null, null, null)
                    : new DateDimensions(eventDate, null, null);
        };
    }

    static MarketEvent.MarketScope marketScope(Source source) {
        String host = lower(source.getAllowedHost());
        return "vi".equals(source.getLanguage()) || host.endsWith(".vn")
                ? MarketEvent.MarketScope.VIETNAM
                : MarketEvent.MarketScope.REGIONAL;
    }

    static String geography(Source source, MarketEvent.MarketScope scope) {
        if (scope == MarketEvent.MarketScope.VIETNAM) return "Vietnam";

        String code = "_" + upper(source.getCode()) + "_";
        String host = lower(source.getAllowedHost());
        if (token(code, "HK") || countryHost(host, "hk")) return "Hong Kong";
        if (token(code, "SG") || countryHost(host, "sg")) return "Singapore";
        if (token(code, "TW") || countryHost(host, "tw")) return "Taiwan";
        if (token(code, "KR") || countryHost(host, "kr")) return "South Korea";
        if (token(code, "JP") || countryHost(host, "jp")) return "Japan";
        if (token(code, "CN") || countryHost(host, "cn")) return "China";
        if (token(code, "ID") || countryHost(host, "id")) return "Indonesia";
        if (token(code, "MY") || countryHost(host, "my")) return "Malaysia";
        if (token(code, "PH") || countryHost(host, "ph")) return "Philippines";
        if (token(code, "TH") || countryHost(host, "th")) return "Thailand";
        return "Regional / international";
    }

    static String eventKey(String factCode, String pipelineVersion) {
        return "ME:" + factCode + ":" + sha256(pipelineVersion).substring(0, 16);
    }

    private static LocalDate first(LocalDate explicit, LocalDate fallback) {
        return explicit != null ? explicit : fallback;
    }

    private static boolean token(String paddedCode, String value) {
        return paddedCode.contains("_" + value + "_");
    }

    private static boolean countryHost(String host, String cc) {
        return host.endsWith("." + cc) || host.contains("." + cc + ".");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record DateDimensions(LocalDate occurredDate, LocalDate effectiveDate,
                          LocalDate forecastHorizon) {}
}
