package com.marketradar.llm;

/** Unchecked stage-level signal used to stop a batch after a terminal provider failure. */
public class TerminalLlmRuntimeException extends RuntimeException {
    public TerminalLlmRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
