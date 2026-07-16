package com.marketradar.report;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.marketradar.llm.LlmClient;
import com.marketradar.llm.SwitchableLlmClient;

/**
 * /agents — "The Newsroom": trang giới thiệu 9 AI agent (3 desk) + 1 chữ ký con người,
 * áp dụng agent identity system từ bundle Claude Design 2026-07-16 (agent-marks fragment
 * + agents.css). Nội dung tĩnh trừ chip model: đọc TRỰC TIẾP từ SwitchableLlmClient
 * (đúng runtime, không phải giá trị lúc boot) — cùng pattern PipelineRunnerController.
 */
@Controller
public class AgentsController {

    private final SwitchableLlmClient classifier;
    private final SwitchableLlmClient writer;
    private final SwitchableLlmClient verifier;

    public AgentsController(@Qualifier("classifierLlmClient") LlmClient classifier,
                            LlmClient writer, // @Primary
                            @Qualifier("verifierLlmClient") LlmClient verifier) {
        this.classifier = (SwitchableLlmClient) classifier;
        this.writer = (SwitchableLlmClient) writer;
        this.verifier = (SwitchableLlmClient) verifier;
    }

    @GetMapping("/agents")
    public String agents(Model model) {
        model.addAttribute("routerModel", chip(classifier));
        model.addAttribute("writerModel", chip(writer));
        model.addAttribute("verifierModel", chip(verifier));
        return "agents";
    }

    /** Nhãn ngắn cho chip trên card — vd "MODEL: gpt-5-mini" / "MODEL: STUB". */
    private static String chip(SwitchableLlmClient client) {
        var c = client.config();
        return switch (c.kind()) {
            case STUB, STUB_VERIFIER -> "MODEL: STUB (no key)";
            case ANTHROPIC -> "MODEL: " + c.model();
            case OPENAI_COMPAT -> "MODEL: " + c.model();
        };
    }
}
