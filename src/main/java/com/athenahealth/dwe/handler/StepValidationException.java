package com.athenahealth.dwe.handler;

/** Signals invalid handler parameters during workflow submission, before the workflow is persisted. */
public class StepValidationException extends Exception {
    public StepValidationException(String message) {
        super(message);
    }
}
