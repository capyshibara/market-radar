package com.marketradar.pipeline;

import com.marketradar.llm.SwitchableLlmClient;

/** Server-side safety rules for stages that would otherwise persist STUB output. */
public final class PipelineExecutionRules {

    private PipelineExecutionRules() {}

    public static void requireConfigured(String stage, SwitchableLlmClient... clients) {
        for (SwitchableLlmClient client : clients) {
            SwitchableLlmClient.Kind kind = client.config().kind();
            if (kind == null || kind == SwitchableLlmClient.Kind.STUB
                    || kind == SwitchableLlmClient.Kind.STUB_VERIFIER) {
                throw new IllegalStateException("BLOCKED: " + stage
                        + " cannot run while " + client.providerName()
                        + " is in STUB mode. Configure a real provider in LLM Settings first.");
            }
        }
    }
}
