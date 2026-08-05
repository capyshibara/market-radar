import com.marketradar.domain.GeographyScope;
import com.marketradar.domain.Source;
import com.marketradar.domain.SourceAuthority;
import com.marketradar.intelligence.EntityResolutionRules;
import com.marketradar.intelligence.SourceIntelligencePolicy;

/** Regression controls for CFO-critical authority/geography/entity separation. */
public class SourceIntelligencePolicyTest {
    public static void main(String[] args) {
        var mof = SourceIntelligencePolicy.infer("MOF_ISA", "Bộ Tài chính", "www.mof.gov.vn", "vi");
        check(mof.authority() == SourceAuthority.REGULATOR, "MOF authority");
        check(mof.marketScope() == GeographyScope.VIETNAM && "VN".equals(mof.marketCode()), "MOF market");

        var globalOfficial = SourceIntelligencePolicy.infer(
                "AIA_GROUP_RESULTS", "AIA Group results", "www.aia.com", "en");
        check(globalOfficial.authority() == SourceAuthority.OFFICIAL_COMPANY, "global official source is authoritative");
        check(globalOfficial.marketScope() == GeographyScope.GLOBAL, "authority does not force Vietnam geography");

        var media = SourceIntelligencePolicy.infer("VNECONOMY", "VnEconomy", "vneconomy.vn", "vi");
        check(media.authority() == SourceAuthority.ESTABLISHED_MEDIA, "Vietnam media authority");
        check(media.marketScope() == GeographyScope.VIETNAM, "Vietnam media geography");

        var translatedGlobal = SourceIntelligencePolicy.infer(
                "GLOBAL_RESEARCH", "Global research translated for Vietnam readers",
                "research.example.com", "vi");
        check(translatedGlobal.marketScope() == GeographyScope.GLOBAL,
                "language must not override an explicit global source scope");
        var vietnameseLanguageOnly = SourceIntelligencePolicy.infer(
                "MANUAL_RESEARCH", "Uploaded research", "example.com", "vi");
        check(vietnameseLanguageOnly.marketScope() == GeographyScope.UNKNOWN,
                "Vietnamese prose alone is not Vietnam-market evidence");
        var arbitraryVietnamBlog = SourceIntelligencePolicy.infer(
                "BLOG_VN", "Vietnam Insurance Blog", "example.vn", "vi");
        check(arbitraryVietnamBlog.authority() == SourceAuthority.UNKNOWN,
                "a Vietnam suffix or insurance word must not fabricate official-company authority");

        var vnPrudential = EntityResolutionRules.resolve("Prudential Việt Nam công bố sản phẩm", "VN");
        check(vnPrudential.status() == EntityResolutionRules.Status.RESOLVED, "explicit Vietnam entity resolves");
        check("PRUDENTIAL_VN".equals(vnPrudential.singleEntity().key()), "Vietnam entity key");

        var parent = EntityResolutionRules.resolve("Prudential plc reported group-wide APE", "GLOBAL");
        check(parent.status() == EntityResolutionRules.Status.RESOLVED, "parent resolves separately");
        check("PRUDENTIAL_PLC".equals(parent.singleEntity().key()), "parent entity key");

        var us = EntityResolutionRules.resolve("PGIM is part of Prudential Financial, Inc.", "US");
        check(us.status() == EntityResolutionRules.Status.RESOLVED, "US confusable resolves separately");
        check("PRUDENTIAL_FINANCIAL_US".equals(us.singleEntity().key()), "US confusable key");

        var bareGlobal = EntityResolutionRules.resolve("Prudential announced new results", "GLOBAL");
        check(bareGlobal.status() == EntityResolutionRules.Status.AMBIGUOUS,
                "bare global brand must not be attributed to Vietnam");

        var conflict = EntityResolutionRules.resolve(
                "Prudential Việt Nam used metrics from Prudential Financial and PGIM", "VN");
        check(conflict.status() == EntityResolutionRules.Status.CONFLICT,
                "confusable legal entities in one statement are a conflict");

        Source legacyInternational = new Source("TEST_GLOBAL", "Official global insurer",
                "https://example.com/news", "example.com", Source.SourceType.HTML, 3, "en");
        legacyInternational.setIntelligenceMetadata(SourceAuthority.OFFICIAL_COMPANY,
                GeographyScope.GLOBAL, "GLOBAL");
        check(legacyInternational.getAuthority().credibilityScore() > 90,
                "international geography must not downgrade authority");

        System.out.println("SourceIntelligencePolicyTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
