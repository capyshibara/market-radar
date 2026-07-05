package vn.techcomlife.marketradar.llm;

/**
 * Stub verifier OFFLINE (không có VERIFIER_API_KEY).
 * Nguyên tắc: KHÔNG có verifier thật → KHÔNG được tự xuất bản.
 * Vì vậy mặc định trả NEUTRAL → mọi claim đi qua Reviewer Console (fail loud).
 *
 * Ngoại lệ duy nhất để test E2E offline trọn luồng AUTO_APPROVED:
 * claim do StubLlmClient sinh (đánh dấu "[STUB]", cố tình không chứa số/ngày/tên)
 * → trả ENTAILED. Nội dung thật KHÔNG BAO GIỜ nhận ENTAILED từ stub.
 */
public class StubVerifierClient implements LlmClient {

    @Override
    public String complete(String systemPrompt, String userPrompt, Double temperature) {
        boolean stubContent = userPrompt != null && userPrompt.contains("[STUB]");
        String verdict = stubContent ? "ENTAILED" : "NEUTRAL";
        String rationale = stubContent
                ? "[STUB_VERIFIER] Nội dung stub không chứa fact kiểm chứng được — cho qua để test luồng."
                : "[STUB_VERIFIER] Không có verifier thật — mặc định NEUTRAL, bắt buộc review.";
        return "{\"verdict\":\"" + verdict + "\",\"rationale\":\"" + rationale + "\"}";
    }

    @Override
    public String providerName() { return "STUB_VERIFIER"; }
}
