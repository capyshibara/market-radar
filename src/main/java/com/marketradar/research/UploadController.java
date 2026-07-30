package com.marketradar.research;

import com.marketradar.domain.RawDoc;
import com.marketradar.domain.Source;
import com.marketradar.parse.ContentParsers;
import com.marketradar.repo.RawDocRepository;
import com.marketradar.repo.SourceRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Kênh 4 (upload tay) — KHÁC hẳn nguồn 1/2/3: không có URL fetch được, độ tin cậy không đến từ
 * uy tín một trang web mà từ việc CON NGƯỜI (Strategy Expert) tự chịu trách nhiệm khi nộp. Vì
 * vậy KHÔNG dùng SafeFetcher (không có gì để fetch) — chỉ nhận text dán tay hoặc file PDF
 * (tái dùng ContentParsers.parsePdf() đã có sẵn cho nguồn 1; DOCX chưa hỗ trợ — cần thêm
 * Apache POI, để dành khi thực sự có nhu cầu, không xây trước).
 *
 * "reference" (bắt buộc) thay cho URL — mô tả nguồn gốc thật (VD "Email nội bộ phòng Kinh doanh,
 * 30/07/2026") để giữ audit trail dù không có link — invariant "mọi câu phải truy được về nguồn"
 * áp dụng ngay cả khi nguồn là con người, không phải trang web.
 */
@Controller
public class UploadController {

    private final RawDocRepository rawDocs;
    private final SourceRepository sources;
    private final ContentParsers parsers;

    public UploadController(RawDocRepository rawDocs, SourceRepository sources, ContentParsers parsers) {
        this.rawDocs = rawDocs;
        this.sources = sources;
        this.parsers = parsers;
    }

    @PostMapping("/research/upload")
    @ResponseBody
    public String upload(@RequestParam(value = "reference", required = false) String reference,
                         @RequestParam(value = "text", required = false) String text,
                         @RequestParam(value = "file", required = false) MultipartFile file) {
        if (reference == null || reference.isBlank()) {
            return "THIẾU 'reference' — bắt buộc mô tả nguồn gốc tài liệu (VD người cung cấp, ngày, kênh)";
        }

        Source uploadSource = sources.findByCode("MANUAL_UPLOAD")
                .orElseThrow(() -> new IllegalStateException(
                        "Thiếu Source MANUAL_UPLOAD trong registry — kiểm tra SeedData"));

        String content;
        if (file != null && !file.isEmpty()) {
            try {
                content = parsers.parsePdf(file.getBytes()).text();
            } catch (ContentParsers.ParseFailedException e) {
                return "PARSE_ERROR (file PDF): " + e.getMessage();
            } catch (Exception e) {
                return "LỖI ĐỌC FILE: " + e.getMessage()
                        + " — hiện chỉ hỗ trợ PDF hoặc dán text trực tiếp (chưa hỗ trợ DOCX)";
            }
        } else if (text != null && !text.isBlank()) {
            content = text;
        } else {
            return "THIẾU NỘI DUNG: cần 1 trong 2 — 'text' (dán tay) hoặc 'file' (PDF)";
        }

        if (content.isBlank()) {
            return "NỘI DUNG RỖNG sau khi đọc — không lưu (fail loud, không đoán nội dung)";
        }

        String hash = sha256(content);
        if (rawDocs.existsByContentHash(hash)) {
            return "Trùng (hash đã có) — không lưu bản mới: " + reference;
        }
        RawDoc doc = new RawDoc(uploadSource, reference, null, null, Instant.now(),
                hash, content, uploadSource.getLanguage(), RawDoc.ParseStatus.OK, null);
        doc.setAcquisition(RawDoc.Acquisition.MANUAL_UPLOAD);
        rawDocs.save(doc);
        return "LƯU: " + reference;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }
}
