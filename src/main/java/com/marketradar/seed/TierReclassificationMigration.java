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
 * 2026-08-03 (Strategy request): active status for these ~60 seed sources is now a HARD RULE
 * derived from tier — Tier 1/2 always active, Tier 3 always inactive — enforced on EVERY boot,
 * not just once. Before this, active/inactive was set ad hoc per source at seed time for
 * unrelated technical reasons (WAF blocks, dead certs, gone RSS feeds), which left several
 * VN Tier 1/2 sources inactive while many Tier 3 foreign sources stayed active — the opposite
 * of what the taxonomy is meant to express. Re-enforcing this every boot is intentional: this
 * table is documented as a "bảng tier cố định (Invariant)" on Source.java, so an operator
 * manually reactivating one of these specific 60 sources is not an expected workflow (unlike
 * a source the operator added themselves through /sources, which this migration never touches).
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
        int flippedActive = 0;
        for (Map.Entry<String, Integer> entry : newTierByCode.entrySet()) {
            Source source = sources.findByCode(entry.getKey()).orElse(null);
            if (source == null) continue;
            int newTier = entry.getValue();
            boolean changed = false;
            if (source.getTier() != newTier) {
                source.setTier(newTier);
                retagged++;
                changed = true;
            }
            boolean shouldBeActive = newTier <= 2;
            if (source.isActive() != shouldBeActive) {
                source.setActive(shouldBeActive);
                flippedActive++;
                changed = true;
            }
            if (changed) {
                sources.save(source);
            }
        }
        if (retagged > 0 || flippedActive > 0) {
            log.info("Tier reclassification: retagged {} source(s) to the VN/media/foreign taxonomy, "
                    + "flipped active status on {} source(s) to match active=(tier<=2).",
                    retagged, flippedActive);
        }
    }
}
