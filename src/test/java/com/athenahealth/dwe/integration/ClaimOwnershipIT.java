package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.domain.StepClaim;
import com.athenahealth.dwe.domain.StepStatus;
import com.athenahealth.dwe.domain.WorkflowStatus;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClaimOwnershipIT extends IntegrationTestSupport {
    @Test
    void simultaneousClaimsCreateOnlyOneOwnerAndAttempt() throws Exception {
        var id = submitOne();
        store.advance();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return store.claimNext("first"); });
            var second = pool.submit(() -> { start.await(); return store.claimNext("second"); });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat((a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM step_attempt WHERE workflow_id = ?", Integer.class, id)).isEqualTo(1);
        }
    }

    @Test
    void wrongOwnerOrTokenAndExpiredClaimsCannotWriteOrRenew() {
        var id = submitOne();
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        var wrong = new StepClaim(id, "a", "ECHO", 1, claim.attemptNo() + 1, "other",
                claim.workflowInputs(), claim.parameters(), Map.of(), System.nanoTime());
        assertThat(store.complete(wrong, json.createObjectNode()).accepted()).isFalse();
        assertThat(store.renew(wrong)).isFalse();
        assertThat(store.renew(claim)).isTrue();
        jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE workflow_id = ?", id);
        assertThat(store.renew(claim)).isFalse();
        assertThat(store.complete(claim, json.createObjectNode()).accepted()).isFalse();
        assertThat(store.fail(claim, "ERROR", "Expired", true).accepted()).isFalse();
        assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.RUNNING);
    }

    @Test
    void duplicateReportsReturnStoredOutcomeAndNeverOverwriteIt() {
        var id = submitOne();
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        assertThat(store.complete(claim, json.createObjectNode().put("value", 1)).accepted()).isTrue();
        assertThat(store.complete(claim, json.createObjectNode().put("value", 2)).accepted()).isTrue();
        assertThat(store.fail(claim, "LATE", "Late failure", false).accepted()).isTrue();
        store.advance();
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(queries.step(id, "a").output().get("value").intValue()).isEqualTo(1);
    }

    @Test
    void permanentFailureFailsWaitingStepsAndClosesWorkflow() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO"},{"id":"b","actionType":"ECHO","dependsOn":["a"]}]
                """);
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        assertThat(store.fail(claim, "PERMANENT", "Cannot continue", false).accepted()).isTrue();
        store.advance();
        assertThat(queries.execution(id).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(queries.step(id, "b").error().code()).isEqualTo("WORKFLOW_FAILED");
        assertThat(store.claimNext("other")).isEmpty();
    }

    @Test
    void largestSupportedBackoffCanBePersistedAsFiniteEligibility() {
        var id = submit("""
                [{"id":"a","actionType":"ECHO","retry":{"backoffInitialMs":9000000000000000,
                  "backoffMaxMs":9000000000000000}}]
                """);
        store.advance();
        var claim = store.claimNext("owner").orElseThrow();
        assertThat(store.fail(claim, "TRANSIENT", "Try later", true).accepted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT isfinite(eligible_at) FROM step_execution WHERE workflow_id = ?", Boolean.class, id)).isTrue();
        assertThat(queries.step(id, "a").status()).isEqualTo(StepStatus.RETRYING);
    }
}
