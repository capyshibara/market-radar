package com.marketradar.tools;

import com.marketradar.domain.EvidenceFact;
import com.marketradar.domain.InterpretedClaim;
import com.marketradar.domain.RawDoc;
import com.marketradar.repo.EvidenceFactRepository;
import com.marketradar.repo.InterpretedClaimRepository;
import com.marketradar.repo.RawDocRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * KHÔNG CHẠY khi vận hành thường — chỉ kích hoạt qua profile "seed-demo"
 * (--spring.profiles.active=seed-demo), dùng MỘT LẦN để kiểm chứng luồng
 * duyệt→báo cáo và entity guard bằng nội dung THẬT đã import tay (không phải
 * do AI/pipeline sinh ra — vì sandbox này không gọi được LLM/internet thật).
 *
 * Đọc 2 RawDoc đã upload qua /documents/intake (id truyền qua ApplicationArguments
 * --doc1=<id> --doc2=<id>), tạo EvidenceFact với span VERBATIM (đúng nguyên văn
 * trong rawText) + InterpretedClaim đã PASS gate L1, PENDING_REVIEW — để test qua
 * /review như một claim thật sự chờ duyệt, không giả lập trạng thái đã duyệt sẵn.
 *
 * Xoá file này sau khi dùng xong — đây là công cụ kiểm chứng một lần, không phải
 * tính năng của hệ thống.
 */
@Component
@Profile("seed-demo")
public class SeedDemoClaims implements ApplicationRunner {

    private final RawDocRepository rawDocs;
    private final EvidenceFactRepository facts;
    private final InterpretedClaimRepository claims;

    public SeedDemoClaims(RawDocRepository rawDocs, EvidenceFactRepository facts,
                          InterpretedClaimRepository claims) {
        this.rawDocs = rawDocs;
        this.facts = facts;
        this.claims = claims;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long doc1Id = Long.parseLong(args.getOptionValues("doc1").get(0));
        long doc2Id = Long.parseLong(args.getOptionValues("doc2").get(0));
        RawDoc doc1 = rawDocs.findById(doc1Id).orElseThrow();
        RawDoc doc2 = rawDocs.findById(doc2Id).orElseThrow();

        // Claim A: quy kết ĐÚNG (Prudential Việt Nam), span verbatim từ doc1.
        String spanA = "Prudential Vietnam serves approximately 2.4 million policyholders in Vietnam.";
        assertVerbatim(doc1, spanA);
        EvidenceFact factA = new EvidenceFact("F-SEED-A01", doc1,
                EvidenceFact.FactType.METRIC, spanA, "en");
        facts.save(factA);
        InterpretedClaim claimA = new InterpretedClaim("C-SEED-A01", doc1,
                InterpretedClaim.Slot.WHY_MATTERS, InterpretedClaim.Origin.PIPELINE,
                "Prudential Việt Nam hiện phục vụ khoảng 2,4 triệu khách hàng bảo hiểm tại Việt Nam.",
                spanA, "F-SEED-A01", InterpretedClaim.GateStatus.PASS,
                "{\"note\":\"seed-demo verbatim insert, not LLM-generated\"}",
                "MANUAL_TEST_HARNESS");
        claims.save(claimA);

        // Claim B: quy kết SAI thực thể (test entity guard) — claim nói về "Prudential"
        // nhưng evidence trích dẫn lại là Prudential Financial Inc. (Mỹ)/PGIM, một công ty
        // KHÔNG liên quan chỉ trùng thương hiệu. Đúng kịch bản CFO mô tả.
        String spanB = "Financial, Inc., headquartered in Newark, New Jersey and listed as NYSE: PRU,";
        assertVerbatim(doc2, spanB);
        EvidenceFact factB = new EvidenceFact("F-SEED-B01", doc2,
                EvidenceFact.FactType.METRIC, spanB, "en");
        facts.save(factB);
        InterpretedClaim claimB = new InterpretedClaim("C-SEED-B01", doc2,
                InterpretedClaim.Slot.WHY_MATTERS, InterpretedClaim.Origin.PIPELINE,
                "Prudential đạt kết quả quản lý tài sản mạnh trong kỳ báo cáo.",
                "Prudential reported strong asset management results in the period.",
                "F-SEED-B01", InterpretedClaim.GateStatus.PASS,
                "{\"note\":\"seed-demo verbatim insert, not LLM-generated\"}",
                "MANUAL_TEST_HARNESS");
        claims.save(claimB);

        System.out.println("SEED_DEMO_DONE claimA=" + claimA.getId() + " claimB=" + claimB.getId());
    }

    private static void assertVerbatim(RawDoc doc, String span) {
        if (doc.getRawText() == null || !doc.getRawText().contains(span)) {
            throw new IllegalStateException("Span not verbatim in doc " + doc.getId() + ": " + span);
        }
    }
}
