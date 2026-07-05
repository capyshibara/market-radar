package com.marketradar.llm;

public class LlmException extends Exception {
    public LlmException(String message) { super(message); }
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
