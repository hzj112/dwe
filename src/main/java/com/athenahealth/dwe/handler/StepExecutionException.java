package com.athenahealth.dwe.handler;

/** A handler-reported failure with a stable error code and whether the retry policy may retry it. */
public class StepExecutionException extends Exception {
    private final String code;
    private final boolean retryable;

    public StepExecutionException(String code, String message, boolean retryable) {
        super(message);
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Execution failures require a code and message");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
