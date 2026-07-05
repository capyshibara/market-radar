# Batch 4 — Ghi chú giao hàng (04/07/2026)

## Đã build (bước 7–9 sequence: Gate L2 + Risk Tier Router + Reviewer Console + Label log)

### File MỚI
| File | Vai trò |
|---|---|
| `llm/OpenAiCompatibleLlmClient.java` | Client generic chuẩn OpenAI chat/completions — đổi provider verifier = đổi CONFIG (base-url/model/env `VERIFIER_API_KEY`), không sửa code |
| `llm/StubVerifierClient.java` | Verifier offline: mặc định NEUTRAL → mọi thứ vào review (không verifier thật = không auto-publish); riêng nội dung `[STUB]` → ENTAILED để test E2E luồng auto |
| `llm/VerifierClientFactory.java` | Bean `verifierLlmClient`; **ENFORCE Invariant #2 lúc khởi động**: writer ANTHROPIC + verifier họ Claude → app từ chối chạy |
| `verify/EntailmentVerifier.java` | Gate L2: claim CÔ LẬP + evidence chunk (cách ly persona theo D2), output JSON verdict 3 trạng thái; parse lỗi/verdict lạ → VERIFIER_ERROR; replay-cache cùng cơ chế cũ |
| `verify/VerificationJob.java` | Điều phối: PENDING_VERIFICATION → verify → ENTAILED + T0/T1 → AUTO_APPROVED; mọi trường hợp khác → PENDING_REVIEW |
| `domain/ClaimVerification.java` | Verdict L2 append-only (chạy lại = record mới, cũ giữ nguyên — audit) |
| `domain/LabelLog.java` | label_store: mọi hành động reviewer + context (gate/verdict/tier lúc quyết định) + oldText verbatim |
| `repo/ClaimVerificationRepository.java` · `repo/LabelLogRepository.java` | — |
| `review/RiskTierRouter.java` | ⚠️ PLACEHOLDER (Impact Scorer thật ở bước 9 thay thế): nguồn tier 1 → T3 (hard-override giữ được cả sau này) · EXEC_SUMMARY → T3 · DEMO_INJECT → T3 · còn lại → T1 |
| `review/ReviewRules.java` | **Logic THUẦN, zero dependency** — tier rule, autoPublishable, precondition 4 hành động, normalize verdict. Controller/Router/Verifier đều gọi vào đây (một nguồn sự thật) |
| `review/ReviewController.java` | Console: `/review` queue (tier cao trước) · `/review/{id}` claim↔evidence song song · approve/edit/force-approve/reject · `/labels` audit |
| `templates/review-queue.html` · `review-detail.html` · `labels.html` | UI cùng style batch cũ; **nút duyệt khoá tới khi mở panel evidence** |
| `Batch4LogicTest.java` (gốc repo) | Test standalone — compile TRỰC TIẾP `ReviewRules.java` của main code (không phải bản port như GateL1Test) |

### File SỬA
- `domain/InterpretedClaim.java`: thêm `riskTier` (String, không khoá enum) + `reviewStatus` (7 trạng thái) + setter cho luồng edit
- `repo/InterpretedClaimRepository.java`: `findByReviewStatusFetched` (tham số, không dùng enum literal JPQL — cú pháp enum lồng dễ vỡ giữa phiên bản Hibernate) · `findPublishable` · `findByIdFetched`
- `interpret/InterpretationJob.java`: gán tier + route (L1 PASS → PENDING_VERIFICATION; L1 FAIL/SCHEMA_REJECTED → PENDING_REVIEW thẳng)
- `report/ClaimController.java`: thêm `POST /verify/run`; demo-inject giờ vào Reviewer Console (T3, PENDING_REVIEW)
- `report/ReportController.java`: report chỉ nhận 4 trạng thái `*_APPROVED` (siết thêm trên điều kiện PASS L1 của batch 3)
- `llm/LlmClientFactory.java`: `@Primary` cho writer (giờ có 2 bean LlmClient)
- `application.yml`: block `marketradar.verifier.*`
- `templates/claims.html`: nav thêm link Reviewer Console + Label log

## Vòng đời claim (chốt Batch 4)
```
AI#3 sinh câu → Gate L1 (deterministic)
  ├─ FAIL/SCHEMA_REJECTED ────────────────→ PENDING_REVIEW
  └─ PASS → PENDING_VERIFICATION → Gate L2 (LLM khác họ)
       ├─ ENTAILED + tier T0/T1 ──────────→ AUTO_APPROVED  ┐
       └─ mọi trường hợp khác ────────────→ PENDING_REVIEW │
                                                │           │→ REPORT chỉ nhận
            Reviewer Console ←──────────────────┘           │  4 trạng thái
       approve → APPROVED · sửa (re-run L1) → EDITED_APPROVED · *_APPROVED
       override (lý do ≥10 ký tự + có citation) → FORCE_APPROVED
       reject (lý do ≥5 ký tự) → REJECTED  — mọi hành động → label_log
```

## Trạng thái xác thực — nói thẳng
- **Logic thuần Batch 4 ĐÃ chạy test thật: 32/32 assertion pass** (JRE 21, compile qua `java -m jdk.compiler/com.sun.tools.javac.Main` vì container không có binary javac). Khác batch 3: test compile TRỰC TIẾP `ReviewRules.java` — đúng code chạy trong app.
- **Toàn bộ project vẫn CHƯA `mvn clean package`** (container không mạng — hạn chế lặp từ batch 2). Đã rà tĩnh: chữ ký repo/gate.check, getter/setter tồn tại, brace balance, null-safe flash trong Thymeleaf. Lỗi compile lặt vặt có thể còn ở phần Spring/JPA/Thymeleaf chưa từng chạy.
- **OpenAiCompatibleLlmClient CHƯA gọi API thật** — format request/response theo chuẩn OpenAI-compat phổ biến nhưng cần smoke-test 1 call khi có mạng + key.
- **Luồng Spring end-to-end** (`/interpret/run` → `/verify/run` → `/review` → report) chưa chạy thật.

## Giả định MỚI đặt ở Batch 4 (phủ quyết được)
1. **Text reviewer sửa KHÔNG gọi lại LLM verifier** — con người đang đối chiếu evidence chính là verifier L2 cho text đó; chỉ Gate L1 chạy lại (máy vẫn bắt lỗi số/ngày/tên).
2. **Cờ evidenceViewed do client gửi** (JS mở panel → set true, server từ chối nếu thiếu): chặn duyệt VÔ Ý, không chặn reviewer cố tình lách. Giải pháp thật (dwell-time server-side, gold-task sampling) là việc pilot — ghi thẳng trong comment.
3. **Tier T1 = auto-publish khi ENTAILED** (diện "sample" E1) nhưng MVP chưa sample thật — 100% claim T1+ENTAILED tự duyệt. Sampling ngẫu nhiên là việc sau.
4. **AUTO_APPROVED không có trong hàng đợi review** — muốn audit thì xem `/claims` + `claim_verifications`.
5. **Reviewer tự khai tên** (chưa có auth) — field text, mặc định `reviewer-demo`.
6. **FORCE_APPROVED vào report như claim thường** (chưa gắn nhãn riêng trong report HTML) — nếu muốn hiển thị "duyệt tay" khác biệt, thêm ở batch polish.

## Endpoints mới
- `POST /verify/run` — chạy Gate L2 cho mọi claim chờ verify
- `GET /review` — hàng đợi reviewer (tier cao trước)
- `GET /review/{id}` — claim ↔ evidence ↔ verdict; 4 nút hành động
- `POST /review/{id}/approve|edit|force-approve|reject`
- `GET /labels` — label log audit

## Demo flow Batch 4 (nối tiếp flow batch 3)
1. `POST /interpret/run` → câu PASS L1 thành PENDING_VERIFICATION
2. `POST /verify/run` → T1+ENTAILED tự duyệt, còn lại vào queue
3. `POST /demo/inject-ungrounded` → claim bịa vào thẳng `/review` (T3)
4. Mở `/review` → mở claim → **nút duyệt đang khoá** → mở evidence → nút mở khoá → sửa text (bỏ "bán chạy nhất", sửa số cho khớp span) → Gate L1 chạy lại SỐNG → EDITED_APPROVED
5. `/labels` → hành động vừa rồi đã thành label; `/report/weekly` → chỉ câu đã duyệt xuất hiện

## Việc Hanh cần làm (cũ + mới)
- [ ] `mvn clean package` local — ưu tiên số 1, giờ đã 2 batch chồng chưa compile thật
- [ ] Chốt model verifier thật + set env `VERIFIER_API_KEY` (config sẵn ở `marketradar.verifier.*`; placeholder gpt-4o-mini)
- [ ] Smoke-test 1 call OpenAiCompatibleLlmClient với key thật
- [ ] Verify 5 URL nguồn trong SeedData (tồn từ batch 2)
- [ ] Xác nhận/phủ quyết 6 giả định mới ở trên
