package com.athenahealth.dwe.domain;

/** One attempt's outcome; ABANDONED records lease expiry without asserting that external work stopped. */
public enum AttemptStatus { RUNNING, COMPLETED, FAILED, ABANDONED }
