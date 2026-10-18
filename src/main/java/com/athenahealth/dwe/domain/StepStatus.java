package com.athenahealth.dwe.domain;

/** A step's execution state; its separate eligible_at timestamp controls when it may be claimed. */
public enum StepStatus { PENDING, RUNNING, COMPLETED, FAILED, RETRYING }
