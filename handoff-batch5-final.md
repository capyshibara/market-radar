# Handoff — Market Radar MVP: sau Batch 5 (SEQUENCE LÕI 1–10 HOÀN TẤT)

*Nếu cần phiên làm việc tiếp theo: dán file này + 3 file kiến trúc gốc
(`market-radar-kien-truc-quyet-dinh-cuoi.md`, `market-radar-kien-truc-day-du.md`,
`market-radar-mvp-hackathon.md`) + `market-radar-batch5.zip`. Các handoff cũ KHÔNG cần nữa.*

---

## 1. Đã build (Batch 1–5) — TOÀN BỘ bước 1–10 sequence Mục 9

| Batch | Bước | Nội dung |
|---|---|---|
| 1 | 1–3 | Schema DB (H2) · SafeFetcher · ContentParsers · IngestionJob · template `weekly-report.html` |
| 2 | 4 | Category/Department enum · TopicClassifier (self-consistency N=3) · Router · AnthropicLlmClient + Stub · `/classifications` |
| 3 | 5–6 | AI#3 Interpreter (template-first) · Gate L1 (exact-match) · `/interpret/run` · `/claims` · `/demo/inject-ungrounded` |
| 4 | 7–9 | Gate L2 (entailment khác họ) · Risk Tier Router (placeholder) · Reviewer Console · Label log |
| 5 | 8–10 | **Hot Alert** (T3-T4 *_APPROVED → Slack webhook / stub, sau-commit, idempotent) · **Dedup/Conflict** (72h, exact→Jaccard→LLM pairwise; official>media · mới>cũ · cùng tier→flag; bản trùng lọc khỏi report, không xoá) · **Polish** (nhãn Duyệt tay cho FORCE_APPROVED · PDF export OpenHTMLtoPDF + DejaVu · demo storyline 5 nhịp) |

**File giao cuối:** `market-radar-batch5.zip` (~90 file) + `BATCH5-NOTES.md` (chi tiết) +
3 test standalone: `GateL1Test` 19/19 · `Batch4LogicTest` 32/32 · `Batch5LogicTest` **61/61**
(compile TRỰC TIẾP AlertRules + DedupRules của main code, JRE 21).

## 2. Kiến trúc dữ liệu bổ sung Batch 5
- `alert_log` (append-only) · `dedup_decisions` (append-only, unique cặp docA<docB)
- `raw_docs.duplicateOfId` (nullable) — bản trùng bị LỌC khỏi report, không xoá
- Config mới: `marketradar.alert.*` (env `SLACK_WEBHOOK_URL`) · `marketradar.dedup.*` (72h, Jaccard 0.90/0.50)
- Endpoints mới: `/report/weekly.pdf` · `/dedup` · `POST /dedup/run` · `POST /demo/inject-duplicate` · `/alerts` · `POST /alerts/test`

## 3. Trạng thái xác thực — nói thẳng
- Logic thuần cả 3 batch có test ĐÃ chạy thật trên JRE 21 (L1 19/19, B4 32/32, B5 61/61).
- **Toàn bộ project CHƯA từng `mvn clean package` — 4 batch chồng. RỦI RO SỐ 1.**
- PDF export chưa render thật (điểm dễ vấp: chữ ký `useFont`/`FontStyle` của OpenHTMLtoPDF 1.0.10).
- Slack webhook chưa bắn thật (smoke-test: `POST /alerts/test`).
- DejaVu KHÔNG có glyph Hán — span zh trong PDF sẽ hiện ô vuông (bundle thêm Noto Sans SC nếu cần).
- OpenAiCompatibleLlmClient (verifier) chưa gọi API thật; 5 URL SeedData chưa verify (tồn cũ).

## 4. Giả định đang mở (phủ quyết được)
**Batch 3:** ngày dd/MM/yyyy VN · evidence không gỡ ngày trước khi bóc số · chưa hỗ trợ số chữ Hán ·
Interpreter không self-consistency · interpret không đòi classification CONFIRMED · max-tokens 1024 chưa calibrate.
**Batch 4:** text sửa không gọi lại LLM verifier (chỉ L1 chạy lại) · cờ evidenceViewed từ client ·
T1+ENTAILED auto 100% chưa sample · AUTO_APPROVED không vào queue · reviewer tự khai tên.
**Batch 5:** alert 1 lần/claim · ngưỡng Jaccard 0.90/0.50 chưa calibrate · dedup chỉ đánh dấu doc
(claim/fact của doc trùng giữ nguyên trong DB, chỉ lọc khỏi report) · LLM pairwise dùng writer client ·
cụm >2 bản trùng cần chạy dedup nhiều lần · alert link tới report (chưa deep-link claim) ·
PDF render đồng bộ trong request.

## 5. Không còn batch code theo sequence — việc còn lại là VẬN HÀNH
Checklist Hanh (thứ tự ưu tiên):
- [ ] **`mvn clean package`** rồi `mvn spring-boot:run` — sửa lỗi compile lặt vặt nếu có
- [ ] Chạy demo storyline 5 nhịp (BATCH5-NOTES mục cuối) từ đầu tới cuối 1 lần sạch
- [ ] Mở `/report/weekly.pdf` — kiểm tra dấu tiếng Việt; quyết định có bundle Noto Sans SC cho span Hán không
- [ ] Slack webhook: tạo app → set `SLACK_WEBHOOK_URL` → `POST /alerts/test`
- [ ] Chốt model verifier + `VERIFIER_API_KEY`, smoke-test 1 call
- [ ] Verify 5 URL SeedData, set `urlUnverified=false`
- [ ] Rà 3 nhóm giả định Mục 4 — phủ quyết cái nào thì đổi config/1 hàm rule tương ứng
Nâng cấp sau MVP (đã ghi chỗ đặt sẵn trong code): Impact Scorer thật thay RiskTierRouter placeholder ·
sampling cho T1 auto · dwell-time server-side chống rubber-stamp · auth reviewer · async PDF.
