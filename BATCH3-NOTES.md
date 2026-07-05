# Batch 3 — Ghi chú giao hàng (04/07/2026)

## Đã thêm (bước 5–6 sequence: AI#3 Interpreter + Gate L1)
| File | Vai trò |
|---|---|
| `domain/InterpretedClaim.java` | Entity: 1 câu + fact_codes CSV + gate status + detail JSON + origin (PIPELINE/DEMO_INJECT) |
| `repo/InterpretedClaimRepository.java` | Fetch-join queries (tránh LazyInit, open-in-view=false) |
| `interpret/EvidencePack.java` | Pack = đầu vào DUY NHẤT của Interpreter (bounded) |
| `interpret/Interpreter.java` | AI#3 template-first: điền slot why/implication/exec-summary, 1 call/doc, replay-cache, temperature=null |
| `interpret/GroundingGateL1.java` | Gate L1 100% code: citation → tên ngoặc kép verbatim → ngày (chuẩn hoá ISO) → số (tập dạng chuẩn + cờ %) |
| `interpret/InterpretationJob.java` | Orchestration, idempotent, lưu MỌI câu kể cả FAIL (fail loud) |
| `report/ClaimController.java` | POST /interpret/run · GET /claims · POST /demo/inject-ungrounded |
| `templates/claims.html` | Trang audit read-only: claim ↔ evidence ↔ gate detail |

## Đã sửa
- `StubLlmClient`: thêm 2 mode INTERPRET_DOC / EXEC_SUMMARY (marker trong system prompt)
- `application.yml`: max-tokens 300 → 1024 (output interpreter dài hơn)
- `ReportController` + `weekly-report.html`: exec summary + AI-block giờ render từ claim ĐÃ PASS gate, kèm citation chip; câu fail không bao giờ vào report

## Trạng thái xác thực — nói thẳng
- **Logic thuần của Gate L1 ĐÃ TEST THẬT**: 19/19 assertion pass trên JRE 21 (port standalone,
  file test kèm trong zip: `GateL1Test.java` ở gốc) — gồm khớp số xuyên định dạng vi/zh
  (2,0% ↔ 2.0%, 10.000 ↔ 10,000), ngày xuyên định dạng (年月日 ↔ dd/MM/yyyy ↔ ISO ↔ "ngày…tháng…năm"),
  tên script gốc verbatim, và demo claim bị chặn đúng cả tên lẫn số.
- **Toàn bộ project vẫn CHƯA `mvn clean package`** (container không mạng) — rà tĩnh
  (chữ ký repo, import, brace, Thymeleaf expr) nhưng lỗi compile lặt vặt có thể còn.
- Checklist cũ chưa đóng: verify 5 URL nguồn, chạy mvn local.

## Giả định đã đặt (phủ quyết được)
1. dd/MM/yyyy theo quy ước VN (không hỗ trợ MM/dd của Mỹ).
2. Evidence phía đối chiếu KHÔNG gỡ ngày trước khi bóc số → giảm false-fail,
   chấp nhận ít false-pass ở L1 (lớp entailment batch 4 + HITL bọc sau).
3. Số chữ Hán (一千万) chưa hỗ trợ — regulator zh dùng chữ số Ả Rập cho phí/lãi suất.
4. Interpreter không self-consistency (sinh văn bản ≠ phân loại; độ tin nằm ở gate).
5. Interpret không đòi classification CONFIRMED — chỉ đòi doc có fact
   (fact extraction từ doc thật vẫn là bước mở, facts hiện là sample data).

## Demo flow batch 3
1. `POST /interpret/run` → AI#3 điền slot, gate chấm từng câu
2. `GET /claims` → xem mọi câu + phán quyết + JSON chi tiết
3. `POST /demo/inject-ungrounded` → claim "bán chạy nhất tuần" bị chặn SỐNG
   (FAIL_NAME vì tên dịch không verbatim + FAIL_NUMBER vì 25.000 bịa)
4. `GET /report/weekly` → chỉ câu PASS xuất hiện, có citation chip

## Batch 4 (kế tiếp)
Gate lớp 2 (entailment Option A — LLM khác họ, claim cô lập + evidence chunk) ·
Reviewer Console thật (approve/edit/reject, nút duyệt khoá tới khi mở evidence) ·
Label log. Nên mở chat mới với handoff.
