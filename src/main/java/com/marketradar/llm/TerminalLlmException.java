package com.marketradar.llm;

/**
 * Provider/account failures that cannot succeed by retrying the same request
 * (for example exhausted credit, invalid credentials, or a missing model).
 */
public class TerminalLlmException extends LlmException {
    public TerminalLlmException(String message) {
        super(message);
    }
}
