package com.marketradar.seed;

import com.marketradar.domain.RawDoc;
import com.marketradar.repo.ClaimVerificationRepository;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.RawDocRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dọn 2 tài liệu mẫu hư cấu do SeedData.seedSampleFacts() (đã bỏ) từng cắm sẵn vào mọi DB
 * mới ("BHNT Hoa Sen" / "华晟人寿保险" — công ty KHÔNG có thật, URL fetchUrl là placeholder
 * .../SAMPLE/demo-doc-1|2 không trỏ tới trang thật nào).
 *
 * Lỗi thật (không phải chủ đích): InterpretationJob trước đây lấy TOÀN BỘ EvidenceFact đang
 * active mà không loại rawDoc.sampleData=true (khác findCurrentProductNewsCandidates() vốn đã
 * loại đúng cách) — nên 2 doc mẫu này từng bị Interpreter/Verifier xử lý y hệt tài liệu thật,
 * sinh ra claim thật (C-xxx) nằm lẫn trong Reviewer Queue, không có gì phân biệt với claim từ
 * crawl thật ngoài đường link fetchUrl không tồn tại — phát hiện khi operator bấm "Open original
 * source" và thấy trang trống (2026-08-02).
 *
 * Xoá theo đúng thứ tự khoá ngoại: ClaimVerification → InterpretedClaim → EvidenceFact → RawDoc.
 * Idempotent: DB không còn RawDoc nào sampleData=true thì không làm gì.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 22)
public class SampleDataCleanupMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataCleanupMigration.class);

    private final RawDocRepository rawDocs;
    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;
    private final ClaimVerificationRepository verifications;

    public SampleDataCleanupMigration(RawDocRepository rawDocs, EvidenceFactRepository facts,
                                      InterpretedClaimRepository claims,
                                      ClaimVerificationRepository verifications) {
        this.rawDocs = rawDocs;
        this.facts = facts;
        this.claims = claims;
        this.verifications = verifications;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<RawDoc> sampleDocs = rawDocs.findBySampleDataTrue();
        if (sampleDocs.isEmpty()) return;

        verifications.deleteByClaimSampleRawDoc();
        claims.deleteBySampleRawDoc();
        facts.deleteBySampleRawDoc();
        rawDocs.deleteAll(sampleDocs);

        log.warn("Đã xoá {} tài liệu MẪU HƯ CẤU (và fact/claim/verification liên quan) từng bị "
                + "Interpret/Verify xử lý như tài liệu thật — xem javadoc SampleDataCleanupMigration.",
                sampleDocs.size());
    }
}
