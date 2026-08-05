package com.marketradar.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic legal-entity resolution for names that share a consumer brand.
 *
 * <p>A bare brand is not a legal entity. It may be narrowed by an explicit
 * jurisdiction marker or by a Vietnam-only source context, otherwise it remains
 * ambiguous. This prevents parent-group and unrelated US-company metrics from
 * being silently attributed to a Vietnam operating company.</p>
 */
public final class EntityResolutionRules {

    private EntityResolutionRules() {}

    public enum EntityKind {
        LOCAL_OPERATING_COMPANY,
        PARENT_GROUP,
        FOREIGN_OPERATING_COMPANY,
        UNRELATED_CONFUSABLE,
        REGULATOR,
        OTHER
    }

    public enum Status { RESOLVED, AMBIGUOUS, MULTIPLE, CONFLICT, UNRESOLVED }

    public record Entity(String key, String canonicalName, EntityKind kind,
                         String marketCode, String parentKey, String brand,
                         List<String> explicitAliases, List<String> genericAliases) {
        public Entity {
            explicitAliases = List.copyOf(explicitAliases);
            genericAliases = List.copyOf(genericAliases);
        }
    }

    public record Resolution(Status status, List<Entity> entities,
                             List<String> matchedAliases, String reason) {
        public Resolution {
            entities = List.copyOf(entities);
            matchedAliases = List.copyOf(matchedAliases);
        }
        public Entity singleEntity() { return entities.size() == 1 ? entities.get(0) : null; }
    }

    private static final List<Entity> ENTITIES = List.of(
            entity("PRUDENTIAL_VN", "Prudential Việt Nam", EntityKind.LOCAL_OPERATING_COMPANY,
                    "VN", "PRUDENTIAL_PLC", "PRUDENTIAL",
                    List.of("prudential việt nam", "prudential vietnam", "công ty tnhh bảo hiểm nhân thọ prudential việt nam"),
                    List.of("prudential")),
            entity("PRUDENTIAL_PLC", "Prudential plc", EntityKind.PARENT_GROUP,
                    "GLOBAL", null, "PRUDENTIAL",
                    List.of("prudential plc", "prudential group plc", "lse: pru", "hkex: 2378"),
                    List.of("prudential")),
            entity("PRUDENTIAL_FINANCIAL_US", "Prudential Financial (Hoa Kỳ)", EntityKind.UNRELATED_CONFUSABLE,
                    "US", null, "PRUDENTIAL_US",
                    List.of("prudential financial", "prudential financial, inc", "pgim", "nyse: pru", "nyse:pru", "newark, new jersey"),
                    List.of()),

            local("MANULIFE_VN", "Manulife Việt Nam", "MANULIFE", "MANULIFE_GROUP",
                    List.of("manulife việt nam", "manulife vietnam"), List.of("manulife")),
            entity("MANULIFE_GROUP", "Manulife Financial Corporation", EntityKind.PARENT_GROUP,
                    "GLOBAL", null, "MANULIFE", List.of("manulife financial corporation", "tsx: mfc"), List.of("manulife")),

            local("AIA_VN", "AIA Việt Nam", "AIA", "AIA_GROUP",
                    List.of("aia việt nam", "aia vietnam"), List.of("aia")),
            entity("AIA_GROUP", "AIA Group", EntityKind.PARENT_GROUP,
                    "GLOBAL", null, "AIA", List.of("aia group", "aia group limited", "hkex: 1299"), List.of("aia")),

            local("DAIICHI_VN", "Dai-ichi Life Việt Nam", "DAIICHI", "DAIICHI_GROUP",
                    List.of("dai-ichi life việt nam", "dai-ichi life vietnam"), List.of("dai-ichi", "daiichi")),
            local("CHUBB_VN", "Chubb Life Việt Nam", "CHUBB", "CHUBB_GROUP",
                    List.of("chubb life việt nam", "chubb life vietnam"), List.of("chubb life", "chubb")),
            local("HANWHA_VN", "Hanwha Life Việt Nam", "HANWHA", "HANWHA_GROUP",
                    List.of("hanwha life việt nam", "hanwha life vietnam"), List.of("hanwha life", "hanwha")),
            local("FWD_VN", "FWD Việt Nam", "FWD", "FWD_GROUP",
                    List.of("fwd việt nam", "fwd vietnam"), List.of("fwd")),
            local("GENERALI_VN", "Generali Việt Nam", "GENERALI", "GENERALI_GROUP",
                    List.of("generali việt nam", "generali vietnam"), List.of("generali")),
            local("SUNLIFE_VN", "Sun Life Việt Nam", "SUNLIFE", "SUNLIFE_GROUP",
                    List.of("sun life việt nam", "sun life vietnam"), List.of("sun life", "sunlife")),
            local("BAOVIET_LIFE_VN", "Bảo Việt Nhân thọ", "BAOVIET_LIFE", "BAOVIET_GROUP",
                    List.of("bảo việt nhân thọ", "bao viet life", "baoviet life"), List.of()),
            entity("BAOVIET_GENERAL_VN", "Bảo hiểm Bảo Việt (phi nhân thọ)", EntityKind.UNRELATED_CONFUSABLE,
                    "VN", "BAOVIET_GROUP", "BAOVIET_GENERAL",
                    List.of("bảo hiểm bảo việt", "baoviet insurance", "bảo việt phi nhân thọ"), List.of()),
            local("MB_AGEAS_VN", "MB Ageas Life", "MB_AGEAS", null,
                    List.of("mb ageas life", "mb ageas", "mb life", "mbal"), List.of()),
            local("BIDV_METLIFE_VN", "BIDV MetLife", "METLIFE", null,
                    List.of("bidv metlife", "bidv-metlife"), List.of()),
            entity("METLIFE_US", "MetLife, Inc. (Hoa Kỳ)", EntityKind.UNRELATED_CONFUSABLE,
                    "US", null, "METLIFE_US", List.of("metlife, inc", "metlife inc", "nyse: met"), List.of()),
            local("TECHCOM_LIFE_VN", "Techcom Life", "TECHCOM_LIFE", null,
                    List.of("techcom life", "công ty cổ phần bảo hiểm nhân thọ kỹ thương"), List.of()),
            local("MVI_LIFE_VN", "MVI Life", "MVI_LIFE", null,
                    List.of("mvi life", "công ty tnhh bảo hiểm nhân thọ mvi"), List.of()),
            local("CATHAY_VN", "Cathay Life Việt Nam", "CATHAY", "CATHAY_GROUP",
                    List.of("cathay life việt nam", "cathay life vietnam"), List.of("cathay life", "cathay")),
            local("SHINHAN_VN", "Shinhan Life Việt Nam", "SHINHAN", "SHINHAN_GROUP",
                    List.of("shinhan life việt nam", "shinhan life vietnam"), List.of("shinhan life")),
            local("PHUHUNG_VN", "Phú Hưng Life", "PHUHUNG", null,
                    List.of("phú hưng life", "phu hung life"), List.of()),

            entity("MOF_INSURANCE_VN", "Bộ Tài chính Việt Nam / Cục QLGSBH", EntityKind.REGULATOR,
                    "VN", null, "MOF_VN",
                    List.of("bộ tài chính", "ministry of finance of vietnam", "cục quản lý, giám sát bảo hiểm",
                            "cục quản lý giám sát bảo hiểm", "insurance supervisory authority of vietnam"),
                    List.of()),
            entity("SBV_VN", "Ngân hàng Nhà nước Việt Nam", EntityKind.REGULATOR,
                    "VN", null, "SBV_VN",
                    List.of("ngân hàng nhà nước việt nam", "state bank of vietnam"), List.of()),
            entity("NSO_VN", "Cục Thống kê Việt Nam", EntityKind.REGULATOR,
                    "VN", null, "NSO_VN",
                    List.of("cục thống kê", "national statistics office of vietnam",
                            "general statistics office of vietnam"), List.of()),
            entity("IAV_VN", "Hiệp hội Bảo hiểm Việt Nam", EntityKind.OTHER,
                    "VN", null, "IAV_VN",
                    List.of("hiệp hội bảo hiểm việt nam", "insurance association of vietnam"), List.of())
    );

    private static final Map<String, List<Pattern>> EXPLICIT = patterns(Entity::explicitAliases);
    private static final Map<String, List<Pattern>> GENERIC = patterns(Entity::genericAliases);

    public static List<Entity> entities() { return ENTITIES; }

    public static Resolution resolve(String text, String defaultMarketCode) {
        if (text == null || text.isBlank()) {
            return new Resolution(Status.UNRESOLVED, List.of(), List.of(), "No entity text");
        }
        Set<Entity> explicit = new LinkedHashSet<>();
        List<String> aliases = new ArrayList<>();
        for (Entity entity : ENTITIES) {
            List<String> sourceAliases = entity.explicitAliases();
            List<Pattern> patterns = EXPLICIT.get(entity.key());
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matcher(text).find()) {
                    explicit.add(entity);
                    aliases.add(sourceAliases.get(i));
                }
            }
        }
        if (explicit.size() > 1) {
            List<Entity> hits = List.copyOf(explicit);
            boolean sameOrConfusableBrand = hasConfusableBrand(hits);
            return new Resolution(sameOrConfusableBrand ? Status.CONFLICT : Status.MULTIPLE,
                    hits, aliases, sameOrConfusableBrand
                            ? "Text contains identifiers for different or confusable legal entities"
                            : "Text contains multiple legal entities");
        }
        if (explicit.size() == 1) {
            Entity entity = explicit.iterator().next();
            return new Resolution(Status.RESOLVED, List.of(entity), aliases, "Explicit legal-entity alias");
        }

        Set<Entity> generic = new LinkedHashSet<>();
        for (Entity entity : ENTITIES) {
            List<String> sourceAliases = entity.genericAliases();
            List<Pattern> patterns = GENERIC.get(entity.key());
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matcher(text).find()) {
                    generic.add(entity);
                    aliases.add(sourceAliases.get(i));
                }
            }
        }
        if (generic.isEmpty()) {
            return new Resolution(Status.UNRESOLVED, List.of(), List.of(), "No registered entity alias");
        }
        String market = upper(defaultMarketCode);
        // GLOBAL/REGIONAL/MULTI describe coverage, not a legal jurisdiction.
        // They must never turn a bare shared brand (for example "Prudential")
        // into a parent-company attribution. Only a concrete country context
        // may narrow a generic brand.
        List<Entity> narrowed = isConcreteMarket(market)
                ? generic.stream().filter(entity -> market.equals(entity.marketCode())).toList()
                : List.of();
        if (narrowed.size() == 1) {
            return new Resolution(Status.RESOLVED, narrowed, aliases,
                    "Generic brand narrowed by source market " + market);
        }
        return new Resolution(Status.AMBIGUOUS, List.copyOf(generic), aliases,
                "Bare brand does not identify a unique legal entity");
    }

    private static boolean hasConfusableBrand(List<Entity> hits) {
        Set<String> brands = new LinkedHashSet<>();
        for (Entity entity : hits) {
            String brand = entity.brand();
            if (brand.startsWith("PRUDENTIAL")) brand = "PRUDENTIAL";
            if (brand.startsWith("BAOVIET")) brand = "BAOVIET";
            if (brand.startsWith("METLIFE")) brand = "METLIFE";
            brands.add(brand);
        }
        return brands.size() < hits.size();
    }

    private static Map<String, List<Pattern>> patterns(
            java.util.function.Function<Entity, List<String>> aliases) {
        Map<String, List<Pattern>> out = new LinkedHashMap<>();
        for (Entity entity : ENTITIES) {
            out.put(entity.key(), aliases.apply(entity).stream().map(EntityResolutionRules::wordPattern).toList());
        }
        return Map.copyOf(out);
    }

    private static Pattern wordPattern(String alias) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(alias)
                + "(?![\\p{L}\\p{N}])");
    }

    private static Entity local(String key, String name, String brand, String parentKey,
                                List<String> explicit, List<String> generic) {
        return entity(key, name, EntityKind.LOCAL_OPERATING_COMPANY, "VN", parentKey, brand, explicit, generic);
    }

    private static Entity entity(String key, String name, EntityKind kind, String marketCode,
                                 String parentKey, String brand, List<String> explicit, List<String> generic) {
        return new Entity(key, name, kind, marketCode, parentKey, brand, explicit, generic);
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean isConcreteMarket(String market) {
        return !market.isBlank()
                && !Set.of("GLOBAL", "REGIONAL", "MULTI", "MULTI_MARKET", "UNKNOWN")
                .contains(market);
    }
}
