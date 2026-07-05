# Market Radar MVP — trạng thái sau Batch 5 (bước 1–10 sequence: ĐỦ PHẦN LÕI)

**Chạy nhanh** (cần JDK 17+ và Maven; xem chi tiết từng batch ở `BATCH*-NOTES.md`):
```bash
mvn clean package          # ⚠️ 4 batch chưa từng compile thật — kỳ vọng vài lỗi nhỏ, sửa nhanh
mvn spring-boot:run        # http://localhost:8080
```
Env (đều KHÔNG bắt buộc — thiếu thì chạy chế độ STUB an toàn, không auto-publish, không bắn Slack):
`ANTHROPIC_API_KEY` (writer) · `VERIFIER_API_KEY` (Gate L2) · `SLACK_WEBHOOK_URL` (hot alert).

Trang chính: `/report/weekly` (+ `.pdf`) · `/sources` · `/classifications` · `/claims`
· `/review` · `/labels` · `/dedup` · `/alerts` · `/h2-console`.
Demo storyline 5 nhịp: xem `BATCH5-NOTES.md` mục cuối.

---

# Market Radar MVP — Batch 1 (bước 1–3 / sequence Mục 9)

Phạm vi batch này: **schema DB + store · fetch/parse 5 nguồn · template tuần san với fact đặt tay**.
Chưa có: classifier (AI#1), interpreter (AI#3), gate, review page, alert — các batch sau.

## ⚠️ Trạng thái: CODE CHƯA COMPILE/TEST
Viết trong môi trường offline (không tải được dependency Maven). Trước khi tin bất kỳ dòng nào:
```bash
mvn clean package        # kỳ vọng vài lỗi nhỏ (import/version) — sửa được nhanh
mvn spring-boot:run
```
Sau khi chạy:
- `http://localhost:8080/report/weekly` — tuần san (fact mẫu đặt tay, template chuẩn Mục 7)
- `http://localhost:8080/sources` — source registry auditable + nút chạy ingest tay
- `http://localhost:8080/h2-console` — soi DB (JDBC URL: `jdbc:h2:mem:marketradar`, user `sa`)

## Checklist verify trước demo (bắt buộc)
1. **5 fetchUrl trong `SeedData.java` là placeholder soạn offline** — mở từng URL bằng tay,
   cập nhật đường dẫn đúng (đặc biệt trang mục tin của MOF/ISA và RSS của TNCK), rồi set
   `urlUnverified = false`.
2. Fact mẫu (F-001, F-002) dùng **công ty hư cấu** — thay bằng fact thật khi pipeline chạy.
3. `marketradar.ingest.enabled=false` mặc định — demo chạy tay qua `/sources` để deterministic.

## Các lớp an toàn crawl (yêu cầu "không dính mã độc")
Mọi request ra ngoài đi qua **một cửa duy nhất: `SafeFetcher`**:

| # | Lớp | Chống gì |
|---|---|---|
| 1 | Chỉ https | downgrade/MITM |
| 2 | Host whitelist exact-match từ source_registry | fetch ngoài phạm vi, kể cả link trong RSS |
| 3 | Resolve DNS → chặn IP private/loopback/link-local | SSRF vào mạng nội bộ |
| 4 | Không follow redirect (3xx = fail loud) | thoát whitelist qua redirect |
| 5 | Content-Type phải khớp loại nguồn khai báo | file thực thi đội lốt HTML/PDF |
| 6 | Cap body 5 MB + timeout 5s/15s | payload quá cỡ, treo pipeline |
| 7 | Nội dung chỉ là dữ liệu: Jsoup `.text()`, PDFBox text-only, template chỉ `th:text` | XSS / script trong nội dung crawl |

**Rủi ro còn lại (nói thẳng):** PDF/HTML độc khai thác lỗ hổng chính parser là rủi ro lý thuyết
— giảm thiểu bằng size cap + giữ PDFBox/Jsoup bản mới + chỉ ingest PDF từ tier 1–2.
Chặt tuyệt đối (ngoài scope hackathon): chạy parser trong container/sandbox tách biệt.

## Cấu trúc
```
domain/    Source · RawDoc · EvidenceFact      (source_registry, raw_docs, evidence_store)
repo/      3 JPA repository
fetch/     SafeFetcher                          (cửa fetch duy nhất, 7 lớp phòng thủ)
parse/     ContentParsers                       (Jsoup / Rome / PDFBox — text-only, fail loud)
pipeline/  IngestionJob                         (orchestrate + SHA-256 dedup + ghi lỗi có lý do)
seed/      SeedData                             (5 nguồn + fact mẫu)
report/    ReportController                     (/report/weekly · /sources · /ingest/run)
templates/ weekly-report.html · sources.html
```

## Invariants đã cài vào code (đối chiếu kiến trúc đầy đủ)
- **Whitelist + tier**: nguồn ngoài registry không có đường vào hệ thống.
- **Fail loud**: fetch bị từ chối / parse lỗi → log + record kèm lý do, không đoán nội dung.
- **Evidence span giữ nguyên văn ngôn ngữ gốc** (zh/vi), bản dịch gắn nhãn riêng.
- **Zero claim không nguồn**: template ép mọi dòng hiển thị kèm mã fact `F-xxx` click về nguồn.
- Ranh giới **Fact / Gợi ý AI** hiện hình bằng mắt (Principle 3) — vùng AI nền xanh, nhãn rõ.

## Batch tiếp theo (theo sequence)
4. Classifier + routing (AI#1, JSON enum 5 category, self-consistency N=3)
5. Interpreter + gate lớp 1 exact-match → 6. Gate lớp 2 (Option A: LLM khác họ)
7. Review page → 8. Hot alert → 9. Dedup/conflict → 10. CSS polish + PDF (OpenHTMLtoPDF)

Ghi chú kỹ thuật: schema đang dùng `CLOB` (H2). Nếu chuyển PostgreSQL, đổi `columnDefinition`
sang `TEXT`.

---

# Batch 2 — Classifier (AI#1) + Routing (bước 4 / sequence)

## Thêm mới
- `domain/` Category (enum đóng 5 nhãn) · Department · Classification · RoutingRule · LlmCallLog
- `llm/` LlmClient · AnthropicLlmClient (REST `/v1/messages`, format verify 07/2026) · StubLlmClient (offline) · LlmClientFactory
- `classify/` TopicClassifier (self-consistency N=3, schema reject, vote ≥2/3) · Router (bảng tra)
- `pipeline/ClassificationJob` · trang `/classifications` + POST `/classify/run`

## Cách chạy
```bash
export ANTHROPIC_API_KEY=sk-ant-...   # không set → tự chuyển STUB mode (keyword, không phải AI)
mvn spring-boot:run
# 1) POST /ingest/run (hoặc nút trên /sources)  2) POST /classify/run  3) xem /classifications
```

## Invariants đã cài (đối chiếu kiến trúc)
- **Không verbalized confidence**: status suy từ vote qua N run độc lập; votesJson lưu bằng chứng.
- **Schema reject**: nhãn ngoài enum / JSON hỏng → run bị loại, không lọc im lặng.
- **Routing chỉ qua bảng tra**: Router đọc `routing_rules`, note ghi rõ "vì category X map dept Y".
- **Fail loud**: <2 run hợp lệ → UNCERTAIN_REVIEW; bất đồng → NO_LABEL_REVIEW; category thiếu rule → ADMIN_QUEUE. Không silent-default.
- **Audit + replay**: mọi response LLM log vào `llm_call_log`; replay-cache = fallback demo.

## Điểm cần biết
- Bảng routing là **placeholder** (cột placeholder=true hiển thị trên UI) — ontology thật vẫn là deliverable riêng.
- STUB mode được log RẤT TO lúc khởi động + banner đỏ trên `/classifications` — không nhầm được với AI thật.
- Ngưỡng min-votes=2/3 là đề xuất bảo thủ, CHƯA calibrate bằng dữ liệu thật (đúng ghi chú Phần 3 doc quyết định).


## Batch 4 (bước 7–9): Gate L2 + Reviewer Console + Label log
- `POST /verify/run` — Gate L2: entailment bằng LLM KHÁC HỌ với writer (Invariant #2 enforce lúc khởi động)
- `GET /review` — hàng đợi reviewer · `GET /review/{id}` — claim↔evidence, nút duyệt khoá tới khi mở evidence
- `GET /labels` — mọi hành động review thành nhãn (label store, MVP chỉ log)
- Verifier config: `marketradar.verifier.*` + env `VERIFIER_API_KEY` (không key → stub, mọi thứ vào review)
- Chi tiết: `BATCH4-NOTES.md` · Test standalone: `Batch4LogicTest.java` (32/32 pass)
