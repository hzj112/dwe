package com.athenahealth.dwe.domain;

/** Overall execution state; COMPLETED requires every step to complete, while FAILED ends scheduling. */
public enum WorkflowStatus { PENDING, RUNNING, COMPLETED, FAILED }
