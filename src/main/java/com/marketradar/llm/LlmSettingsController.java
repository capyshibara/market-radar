package com.marketradar.llm;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Batch 9 ("Change LLM" UI): GET/POST /llm-settings — đổi provider/model của
 * 3 slot (classifier/writer/verifier) TẠI RUNTIME, không cần khởi động lại JVM.
 * API key nhập vào KHÔNG lưu xuống đĩa — chỉ giữ trong SwitchableLlmClient
 * (bộ nhớ) cho tới lần đổi tiếp theo hoặc app restart.
 */
@Controller
public class LlmSettingsController {

    private final SwitchableLlmClient classifierClient;
    private final SwitchableLlmClient writerClient;
    private final SwitchableLlmClient verifierClient;

    public LlmSettingsController(@Qualifier("classifierLlmClient") LlmClient classifierClient,
                                 LlmClient writerClient, // @Primary
                                 @Qualifier("verifierLlmClient") LlmClient verifierClient) {
        this.classifierClient = (SwitchableLlmClient) classifierClient;
        this.writerClient = (SwitchableLlmClient) writerClient;
        this.verifierClient = (SwitchableLlmClient) verifierClient;
    }

    @ModelAttribute
    public void addSlots(Model model) {
        model.addAttribute("classifier", classifierClient.config());
        model.addAttribute("classifierProvider", classifierClient.providerName());
        model.addAttribute("writer", writerClient.config());
        model.addAttribute("writerProvider", writerClient.providerName());
        model.addAttribute("verifier", verifierClient.config());
        model.addAttribute("verifierProvider", verifierClient.providerName());
    }

    @GetMapping("/llm-settings")
    public String page() {
        return "llm-settings";
    }

    @PostMapping("/llm-settings/{slot}")
    public String update(@PathVariable String slot,
                         @RequestParam SwitchableLlmClient.Kind kind,
                         @RequestParam(required = false) String baseUrl,
                         @RequestParam(required = false) String model,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(defaultValue = "1024") int maxTokens,
                         RedirectAttributes redirect) {
        SwitchableLlmClient target = switch (slot) {
            case "classifier" -> classifierClient;
            case "writer" -> writerClient;
            case "verifier" -> verifierClient;
            default -> null;
        };
        if (target == null) {
            redirect.addFlashAttribute("error", "Unknown slot: " + slot);
            return "redirect:/llm-settings";
        }

        try {
            LlmClient newDelegate;
            SwitchableLlmClient.Config newConfig;
            switch (kind) {
                case ANTHROPIC -> {
                    require(apiKey, "API key");
                    require(model, "Model");
                    newDelegate = new AnthropicLlmClient(apiKey, model, maxTokens);
                    newConfig = new SwitchableLlmClient.Config(kind, null, model, maxTokens);
                }
                case OPENAI_COMPAT -> {
                    require(apiKey, "API key");
                    require(baseUrl, "Base URL");
                    require(model, "Model");
                    newDelegate = new OpenAiCompatibleLlmClient(baseUrl, apiKey, model, maxTokens);
                    newConfig = new SwitchableLlmClient.Config(kind, baseUrl, model, maxTokens);
                }
                case STUB, STUB_VERIFIER -> {
                    boolean isVerifier = "verifier".equals(slot);
                    newDelegate = isVerifier ? new StubVerifierClient() : new StubLlmClient();
                    newConfig = new SwitchableLlmClient.Config(
                            isVerifier ? SwitchableLlmClient.Kind.STUB_VERIFIER : SwitchableLlmClient.Kind.STUB,
                            null, isVerifier ? "STUB_VERIFIER" : "STUB", maxTokens);
                }
                default -> throw new IllegalArgumentException("Unsupported kind: " + kind);
            }

            // Invariant #2 chỉ ràng writer/verifier — đổi bên nào cũng kiểm tra chéo với bên kia.
            if ("writer".equals(slot)) {
                Invariant2.assertDifferentFamily(newConfig, verifierClient.config());
            } else if ("verifier".equals(slot)) {
                Invariant2.assertDifferentFamily(writerClient.config(), newConfig);
            }

            target.reconfigure(newDelegate, newConfig);
            redirect.addFlashAttribute("success", "Updated " + slot + " → " + newDelegate.providerName());
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Failed to update " + slot + ": " + e.getMessage());
        }
        return "redirect:/llm-settings";
    }

    private static void require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required for this provider kind.");
        }
    }
}
