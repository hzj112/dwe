package com.athenahealth.dwe.domain;

/** Limits total attempts, including the first execution, and controls the delay between retries. */
public record RetryPolicy(int maxAttempts, long backoffInitialMs, long backoffMaxMs) {
    // Keep milliseconds-to-interval conversion and future eligibility within PostgreSQL's finite range.
    public static final long MAX_BACKOFF_MS = 9_000_000_000_000_000L;

    public RetryPolicy {
        if (maxAttempts < 1 || backoffInitialMs < 1 || backoffMaxMs < backoffInitialMs
                || backoffMaxMs > MAX_BACKOFF_MS) {
            throw new IllegalArgumentException("Invalid retry policy");
        }
    }

    /** Returns the retry delay after an attempt: initial delay, then doubling up to the configured cap. */
    public long delayAfter(int attemptsStarted) {
        long delay = backoffInitialMs;
        for (int i = 1; i < attemptsStarted && delay < backoffMaxMs; i++) {
            delay = delay > backoffMaxMs / 2 ? backoffMaxMs : Math.min(backoffMaxMs, delay * 2);
        }
        return delay;
    }
}
