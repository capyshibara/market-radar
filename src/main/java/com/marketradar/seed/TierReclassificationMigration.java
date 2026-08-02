package com.marketradar.seed;

import com.marketradar.domain.Source;
import com.marketradar.repo.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Backfills the 2026-08-02 tier redefinition (Strategy request) into databases seeded before
 * it existed. SeedData.java already reflects the new taxonomy for a fresh database, but this
 * app's H2 file DB persists across restarts — an existing database keeps whatever tier value
 * was written on first seed, so it needs a one-time correction on top.
 *
 * New taxonomy (previously: 1=regulator, 2=established publisher, 3=secondary publisher,
 * a credibility axis cutting across every country):
 *   Tier 1 = Bộ Tài chính (MOF) + trang chính thức của đối thủ tại Việt Nam
 *   Tier 2 = báo mạng Việt Nam
 *   Tier 3 = nguồn nước ngoài
 * The axis is now domestic vs. international — the CFO's stated concern — not source
 * credibility. Only the ~60 codes this codebase itself seeded are touched; a source an
 * operator added later through /sources is left alone (this migration doesn't know its intent).
 *
 * Tier 3 is also auto-deactivated, but ONLY the first time a source is reclassified INTO tier 3
 * (detected by the tier actually changing to 3 during this same pass) — an operator who later
 * flips a Tier 3 source back on via the /sources toggle must stay flipped on across restarts,
 * or the toggle button would be pointless.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TierReclassificationMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TierReclassificationMigration.class);

    private static final Set<String> TIER1 = Set.of(
            "MOF_ISA", "IAV_VN", "BVNT", "AIA_VN", "MANULIFE_VN", "PRUDENTIAL_VN", "MB_AGEAS",
            "PHU_HUNG_LIFE", "BIDV_METLIFE", "MAP_LIFE", "FUBON_VN", "CATHAY_VN", "SUNLIFE_VN",
            "SHINHAN_VN", "CHUBB_VN", "DAIICHI_VN", "FWD_VN", "GENERALI_VN", "HANWHA_VN");
    private static final Set<String> TIER2 = Set.of("TNCK_VN", "VNECONOMY", "TBNH", "CAFEF");
    private static final Set<String> TIER3 = Set.of(
            "NFRA_CN", "CBIRC_NEWS", "FSA_JP", "FSC_TW", "HKMA", "AIR", "BT_SG", "PINGAN_MEDIA",
            "CHINALIFE_HK", "HKIA", "AIA_HK", "PRU_HK", "FUBON_TW", "CATHAY_TW", "FSC_KR", "FSS_KR",
            "HANWHA_GLOBAL", "TOKIO_MARINE", "MSAD", "NIPPON_LIFE", "MAS_SG", "GREAT_EASTERN",
            "INCOME_SG", "AIA_SG", "OJK_ID", "BNM_MY", "IC_PH", "THAILIFE_TH", "PRULIFE_PH",
            "PHILAM_PH", "NAIC", "SWISSRE_INST", "MUNICHRE", "LIMRA", "MCKINSEY_INS",
            "INS_ASIA_NEWS", "INS_BIZ_ASIA");

    private final SourceRepository sources;

    public TierReclassificationMigration(SourceRepository sources) {
        this.sources = sources;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Integer> newTierByCode = new java.util.HashMap<>();
        TIER1.forEach(code -> newTierByCode.put(code, 1));
        TIER2.forEach(code -> newTierByCode.put(code, 2));
        TIER3.forEach(code -> newTierByCode.put(code, 3));

        int retagged = 0;
        int deactivated = 0;
        for (Map.Entry<String, Integer> entry : newTierByCode.entrySet()) {
            Source source = sources.findByCode(entry.getKey()).orElse(null);
            if (source == null) continue;
            int oldTier = source.getTier();
            int newTier = entry.getValue();
            if (oldTier == newTier) continue;
            source.setTier(newTier);
            retagged++;
            if (newTier == 3 && oldTier != 3 && source.isActive()) {
                source.setActive(false);
                deactivated++;
            }
            sources.save(source);
        }
        if (retagged > 0) {
            log.info("Tier reclassification: retagged {} source(s) to the VN/media/foreign taxonomy, "
                    + "auto-deactivated {} newly-Tier-3 source(s) (use the /sources toggle to turn any back on).",
                    retagged, deactivated);
        }
    }
}
