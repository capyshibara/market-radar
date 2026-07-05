# Batch 5 — Ghi chú giao hàng (04/07/2026)

## Đã build (bước 8–10 sequence Mục 9: Hot Alert + Dedup/Conflict + Polish/PDF)
**Đây là batch cuối của sequence MVP** — phần lõi 1–10 đã đủ mặt.

### File MỚI
| File | Vai trò |
|---|---|
| `alert/AlertRules.java` | **Logic THUẦN Hot Alert, zero dependency** (pattern ReviewRules): shouldAlert = tier ≥ min (T3) **VÀ** status *_APPROVED — alert không đi tắt qua gate; template alert 4 phần fact + nguồn + "vì sao" + hành động; jsonEscape; validate webhook URL |
| `alert/HotAlertService.java` | Bắn Slack incoming webhook (đúng 1 POST `{"text":...}`). Payload dựng TRONG transaction, HTTP gửi SAU COMMIT (TransactionSynchronization) — duyệt fail không bắn nhầm, alert fail không phá duyệt (mọi exception được nuốt + log). Idempotent theo claimCode. Không có `SLACK_WEBHOOK_URL` → **STUB mode**: không gọi mạng, alert vẫn ghi `/alerts` |
| `alert/AlertController.java` | `GET /alerts` audit + `POST /alerts/test` smoke-test webhook |
| `domain/AlertLog.java` · `repo/AlertLogRepository.java` | alert_log append-only: SENT/FAILED/SKIPPED_DUPLICATE + payload nguyên văn + HTTP status |
| `dedup/DedupRules.java` | **Logic THUẦN Dedup/Conflict, zero dependency**: normalizeTitle (GIỮ dấu tiếng Việt — dấu là thông tin), Jaccard, cửa sổ 72h, thang exact URL/hash → Jaccard → GRAY, pickWinner (official>media · mới>cũ · cùng tier→FLAG), parseSameEvent (lỗi parse → null, không đoán) |
| `dedup/DedupJob.java` | Điều phối pairwise trong cửa sổ 72h, cặp đã quyết thì bỏ qua; vùng xám [0.50, 0.90) → LLM pairwise (WRITER client — không phải verify đối kháng nên không cần khác họ; replay-cache dùng chung); chạy STUB → vùng xám route thẳng NEEDS_REVIEW. Bản THUA đánh dấu `raw_docs.duplicateOfId` — KHÔNG xoá gì |
| `dedup/DedupController.java` | `GET /dedup` (NEEDS_REVIEW nổi đầu) · `POST /dedup/run` · `POST /demo/inject-duplicate` (bản đăng lại: title y hệt → Jaccard 1.0 bắt deterministic; nguồn tier thấp hơn + publishedAt cũ hơn → bản gốc luôn thắng) |
| `domain/DedupDecision.java` · `repo/DedupDecisionRepository.java` | dedup_decisions append-only, unique theo cặp (docAId<docBId), snapshot title + lý do đọc được |
| `report/PdfExportService.java` | `GET /report/weekly.pdf`: render ĐÚNG template weekly-report (một nguồn sự thật) → jsoup W3CDom chuẩn hoá XHTML → OpenHTMLtoPDF; **embed DejaVu Sans** (font base-14 của PDF không phủ dấu tiếng Việt); CSS override riêng bản PDF (@page A4, ẩn toolbar, page-break) |
| `resources/fonts/DejaVu*.ttf` (3 file, ~1.8MB) | Font phủ đủ tiếng Việt, license Bitstream Vera (dùng tự do) |
| `templates/alerts.html` · `templates/dedup.html` | UI cùng style hệ thống |
| `Batch5LogicTest.java` (gốc repo) | Test standalone compile TRỰC TIẾP AlertRules + DedupRules của main code — **61/61 PASS trên JRE 21** |

### File SỬA
- `pom.xml`: thêm `com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10` (dùng chung pdfbox 2.0.x đã có; bản pin 2.0.31 thắng theo nearest-wins)
- `application.yml`: block `marketradar.alert.*` (webhook từ env `SLACK_WEBHOOK_URL`, min-tier T3, base-url cho link hành động) + `marketradar.dedup.*` (window 72h, ngưỡng Jaccard 0.90/0.50)
- `domain/RawDoc.java`: thêm `duplicateOfId` (nullable — ddl-auto update tự thêm cột)
- `report/ReportController.java`: tách `buildWeeklyModel()` dùng chung HTML/PDF; **report lọc fact/claim thuộc doc trùng**; thêm `GET /report/weekly.pdf`
- `verify/VerificationJob.java`: hook `maybeAlert` khi AUTO_APPROVED
- `review/ReviewController.java`: hook `maybeAlert` sau approve / edit-approve / force-approve
- `templates/weekly-report.html`: **nhãn "Duyệt tay" cho FORCE_APPROVED** (đóng giả định #6 batch 4) + chú giải; toolbar (Tải PDF + link console — không in/không vào PDF); print CSS page-break; ghi chú dedup trong mục Phương pháp
- Nav `claims.html` / `review-queue.html` / `labels.html`: thêm link Dedup + Alerts

## Vòng đời claim sau Batch 5 (phần alert nối thêm, gate GIỮ NGUYÊN)
```
… (vòng đời Batch 4 không đổi) → *_APPROVED ──┬→ REPORT (HTML + PDF, lọc doc trùng,
                                              │   FORCE_APPROVED có nhãn riêng)
                                              └→ tier T3-T4 → HOT ALERT (Slack/stub)
raw_docs ── DedupJob (72h, exact→Jaccard→LLM) ──→ bản thua: duplicateOfId (lọc khỏi report)
                                              └──→ không phân định: NEEDS_REVIEW tại /dedup
```

## Trạng thái xác thực — nói thẳng
- **Logic thuần Batch 5 ĐÃ chạy test thật: 61/61 PASS** (JRE 21, compile qua `java -m jdk.compiler`, compile TRỰC TIẾP file main code).
- **Toàn bộ project vẫn CHƯA `mvn clean package`** — giờ đã 4 batch chồng (container không mạng, hạn chế lặp từ batch 2). Đã rà tĩnh: brace/paren balance, getter khớp template, fetch-join đủ cho open-in-view=false. **Vẫn là RỦI RO SỐ 1.**
- **PDF export CHƯA render thật** (cần dependency OpenHTMLtoPDF tải về): luồng Thymeleaf→jsoup→W3CDom→PdfRendererBuilder viết theo API 1.0.10; điểm dễ vấp nhất nếu có: chữ ký `useFont`/`FontStyle` — sửa 1–2 dòng nếu lệch.
- **Slack webhook CHƯA bắn thật** (không mạng) — format `{"text": ...}` là chuẩn incoming webhook; smoke-test bằng `POST /alerts/test`.
- 5 URL nguồn trong SeedData vẫn chưa verify (tồn từ batch 2).

## Giả định MỚI đặt ở Batch 5 (phủ quyết được)
1. **Alert bắn 1 lần/claim** (idempotent theo claimCode) — claim bị reject rồi sửa luồng khác không bắn lại.
2. **Ngưỡng Jaccard 0.90/0.50 chưa calibrate** — chọn bảo thủ: chỉ trùng gần-y-hệt mới deterministic, vùng xám rộng đẩy sang LLM/người.
3. **Dedup chỉ đánh dấu doc, KHÔNG động vào claim/fact đã sinh** — claim của doc trùng vẫn *_APPROVED trong DB, chỉ bị lọc khỏi report (audit giữ nguyên). Nếu muốn "gộp evidence về bản thắng" là việc pilot.
4. **LLM pairwise dùng WRITER client** — dedup không phải bước verify đối kháng, Invariant #2 chỉ ràng cặp writer/verifier của Gate L2.
5. **Doc đã là bản trùng không so tiếp** (tránh chuỗi trùng lồng nhau) — cụm >2 bản trùng cần nhiều lần chạy `/dedup/run` để quét hết (chấp nhận ở MVP, mỗi lần chạy là idempotent).
6. **Alert "hành động" = link report** — chưa deep-link tới claim cụ thể (cần claim anchor trong report, việc polish sau).
7. **PDF render đồng bộ trong request** — report nhỏ (MVP) nên chấp nhận; report lớn cần async + cache.

## Endpoints mới
- `GET /report/weekly.pdf` — tải PDF tuần san (đúng nội dung bản HTML)
- `POST /dedup/run` · `GET /dedup` — chạy + audit dedup/conflict
- `POST /demo/inject-duplicate` — nhịp demo: chèn bản đăng lại
- `GET /alerts` · `POST /alerts/test` — audit + smoke-test hot alert

## Demo storyline 5 nhịp (bước 10 — chạy trơn từ đầu tới cuối)
1. **Nguồn & ingest** — `/sources` cho thấy whitelist + tier, bấm chạy ingest tay → dữ liệu vào có kiểm soát.
2. **AI sinh + máy chặn** — `POST /interpret/run` → `POST /verify/run`: câu qua L1+L2 tự duyệt (T1), rồi `POST /demo/inject-ungrounded` → claim bịa bị chặn SỐNG, nằm ở `/review` (T3).
3. **Người duyệt có kỷ luật** — mở `/review`: nút duyệt KHOÁ tới khi mở evidence; sửa text → Gate L1 chạy lại sống → EDITED_APPROVED; **màn hình Slack/`/alerts` nhảy hot alert T3 ngay khi duyệt** (nhịp mới batch 5).
4. **Tin trùng tự sạch** — `POST /demo/inject-duplicate` → `POST /dedup/run`: bản đăng lại bị bắt bằng Jaccard, `/dedup` ghi rõ "official > media"; `/report/weekly` không nhân đôi tin.
5. **Sản phẩm cuối** — `/report/weekly`: nhãn Fact / Tổng hợp AI / **Duyệt tay** rõ ràng, bấm **Tải PDF** → file mang đi được; `/labels` + `/alerts` + `/dedup` = audit trail khép kín.

## Việc Hanh cần làm (cũ + mới)
- [ ] **`mvn clean package` local — ưu tiên số 1** (4 batch chưa compile thật)
- [ ] Mở `/report/weekly.pdf` kiểm tra: dấu tiếng Việt + chữ Hán trong span gốc (DejaVu KHÔNG có glyph Hán — span zh sẽ hiện ô vuông trong PDF; nếu cần, bundle thêm Noto Sans SC và useFont tương tự)
- [ ] Tạo Slack incoming webhook (App → Incoming Webhooks) → set env `SLACK_WEBHOOK_URL` → `POST /alerts/test`
- [ ] Chốt model verifier + `VERIFIER_API_KEY`; smoke-test 1 call (tồn từ batch 4)
- [ ] Verify 5 URL nguồn trong SeedData (tồn từ batch 2)
- [ ] Xác nhận/phủ quyết 7 giả định mới ở trên
