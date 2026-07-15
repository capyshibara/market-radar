package com.marketradar.product;

/** A candidate edition is not persisted when structured Product writing fails. */
public class ProductInsightWritingException extends RuntimeException {
    public ProductInsightWritingException(String message) { super(message); }
    public ProductInsightWritingException(String message, Throwable cause) { super(message, cause); }
}
