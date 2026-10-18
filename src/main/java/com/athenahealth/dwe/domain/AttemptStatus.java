package com.athenahealth.dwe.domain;

/** One execution attempt's outcome; ABANDONED is reserved for lease-expiry recovery in a later milestone. */
public enum AttemptStatus { RUNNING, COMPLETED, FAILED, ABANDONED }
