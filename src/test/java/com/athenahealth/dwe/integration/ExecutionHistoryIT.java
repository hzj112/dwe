package com.athenahealth.dwe.integration;

import com.athenahealth.dwe.persistence.PageCursor;
import com.athenahealth.dwe.support.IntegrationTestSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;

/** Exercises the public read APIs with real persisted lifecycle transitions and deterministic ordering ties. */
class ExecutionHistoryIT extends IntegrationTestSupport {
    private static final String ROOT = "/api/v1/executions";

    @Test
    void emptyListingHasAnExplicitEndOfPagination() throws Exception {
        JsonNode page = get(ROOT);
        assertThat(page.get("items").size()).isZero();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    /** Each status is produced through engine operations, so summary counts describe actual owned work. */
    @Test
    void listsAllStatusesWithLightweightSummariesAndFilters() throws Exception {
        var completed = submitOne();
        store.advance();
        store.complete(store.claimNext("completed-owner").orElseThrow(), json.createObjectNode());
        store.advance();
        var failed = submitOne();
        store.advance();
        store.fail(store.claimNext("failed-owner").orElseThrow(), "PERMANENT", "Stop", false);
        var running = submitOne();
        store.advance();
        store.claimNext("running-owner").orElseThrow();
        var pending = submitOne();
        for (var pair : List.of(List.of("COMPLETED", completed), List.of("FAILED", failed),
                List.of("RUNNING", running), List.of("PENDING", pending))) {
            String state = pair.get(0).toString();
            JsonNode page = get(ROOT + "?status=" + state);
            assertThat(page.get("items").size()).isEqualTo(1);
            JsonNode item = page.get("items").get(0);
            assertThat(item.get("executionId").stringValue()).isEqualTo(pair.get(1).toString());
            assertThat(item.get("status").stringValue()).isEqualTo(state);
            assertThat(item.get("currentCycleNo").intValue()).isEqualTo(1);
            assertThat(item.get("runningStepCount").intValue()).isEqualTo(state.equals("RUNNING") ? 1 : 0);
            assertThat(item.get("retryAllowed").booleanValue()).isEqualTo(state.equals("FAILED"));
            assertThat(item.get("createdAt").isString()).isTrue();
            assertThat(item.get("firstStartedAt").isNull()).isEqualTo(state.equals("PENDING"));
            assertThat(item.get("finishedAt").isNull()).isEqualTo(state.equals("PENDING") || state.equals("RUNNING"));
            assertThat(item.has("definition")).isFalse();
            assertThat(item.has("steps")).isFalse();
            assertThat(page.get("nextCursor").isNull()).isTrue();
        }
        assertThat(get(ROOT).get("items").size()).isEqualTo(4);
    }

    /** Equal microsecond timestamps force the UUID tie-breaker across every page boundary. */
    @Test
    void executionPagesUseBothTimestampAndUuidAndAllowChangingPageSize() throws Exception {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) ids.add(submitOne().toString());
        jdbc.update("UPDATE workflow_execution SET created_at = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00.123456Z")));
        var expected = ids.stream().sorted(Comparator.reverseOrder()).toList();
        JsonNode first = get(ROOT + "?limit=2");
        assertThat(values(first, "executionId")).containsExactlyElementsOf(expected.subList(0, 2));
        String cursor = first.get("nextCursor").stringValue();
        // Newer submissions appear on refresh; they must not shift an existing continuation boundary.
        submitOne();
        JsonNode second = get(ROOT + "?limit=1&cursor=" + cursor);
        JsonNode third = get(ROOT + "?limit=2&cursor=" + second.get("nextCursor").stringValue());
        var seen = new ArrayList<>(values(first, "executionId"));
        seen.addAll(values(second, "executionId")); seen.addAll(values(third, "executionId"));
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(third.get("nextCursor").isNull()).isTrue();
    }

    /** Bound response sizes remain enforced even when the database contains more than one maximum page. */
    @Test
    void defaultAndMaximumPageSizesAndFinalPageAreBounded() throws Exception {
        for (int i = 0; i < 201; i++) submitOne();
        JsonNode normal = get(ROOT);
        assertThat(normal.get("items").size()).isEqualTo(50);
        assertThat(normal.get("nextCursor").isString()).isTrue();
        JsonNode maximum = get(ROOT + "?limit=200");
        assertThat(maximum.get("items").size()).isEqualTo(200);
        JsonNode last = get(ROOT + "?limit=200&cursor=" + maximum.get("nextCursor").stringValue());
        assertThat(last.get("items").size()).isEqualTo(1);
        assertThat(last.get("nextCursor").isNull()).isTrue();
    }

    /** Manual retry adds cycles; earlier outcomes stay queryable and completed steps have no new attempts. */
    @Test
    void exposesPreservedResultsAndErrorsAcrossManualRetryCycles() throws Exception {
        var id = submit("""
                [{"id":"a","actionType":"ECHO"},{"id":"b","actionType":"ECHO","dependsOn":["a"]},
                 {"id":"c","actionType":"ECHO","dependsOn":["b"]}]
                """);
        store.advance();
        store.complete(store.claimNext("first").orElseThrow(), json.createObjectNode().put("saved", "output"));
        store.advance();
        store.fail(store.claimNext("second").orElseThrow(), "BUSINESS_ERROR", "Bad data", false);
        JsonNode oldCycle = get(path(id, "cycles")).get("items").get(0);
        assertThat(oldCycle.get("cycleNo").intValue()).isEqualTo(1);
        assertThat(oldCycle.get("status").stringValue()).isEqualTo("FAILED");
        assertThat(oldCycle.get("failedStepId").stringValue()).isEqualTo("b");
        assertThat(oldCycle.get("error").get("message").stringValue()).isEqualTo("Bad data");
        assertThat(oldCycle.get("createdAt").isString()).isTrue();
        assertThat(oldCycle.get("startedAt").isString()).isTrue();
        assertThat(oldCycle.get("finishedAt").isString()).isTrue();
        JsonNode oldAttempts = get(path(id, "attempts") + "?cycleNo=1").get("items");
        assertThat(oldAttempts.size()).isEqualTo(2); // Blocked c never acquired an attempt.
        assertThat(oldAttempts.get(0).get("error").get("retryable").booleanValue()).isFalse();
        assertThat(oldAttempts.get(0).get("result").isNull()).isTrue();
        assertThat(oldAttempts.get(1).get("result").get("saved").stringValue()).isEqualTo("output");
        assertThat(oldAttempts.get(1).get("error").isNull()).isTrue();
        store.retry(id);
        JsonNode first = get(path(id, "cycles") + "?limit=1");
        JsonNode pending = first.get("items").get(0);
        assertThat(pending.get("cycleNo").intValue()).isEqualTo(2);
        assertThat(pending.get("status").stringValue()).isEqualTo("PENDING");
        assertThat(pending.get("startedAt").isNull()).isTrue();
        assertThat(pending.get("finishedAt").isNull()).isTrue();
        assertThat(pending.get("error").isNull()).isTrue();
        JsonNode second = get(path(id, "cycles") + "?limit=1&cursor=" + first.get("nextCursor").stringValue());
        assertThat(second.get("items").get(0)).isEqualTo(oldCycle);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        store.advance();
        store.complete(store.claimNext("third").orElseThrow(), json.createObjectNode());
        store.advance();
        store.complete(store.claimNext("fourth").orElseThrow(), json.createObjectNode());
        store.advance();
        JsonNode cycles = get(path(id, "cycles"));
        assertThat(cycles.get("items").get(0).get("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(cycles.get("items").get(1)).isEqualTo(oldCycle);
        assertThat(get(path(id, "attempts") + "?cycleNo=1").get("items")).isEqualTo(oldAttempts);
        JsonNode retried = get(path(id, "attempts") + "?stepId=b&cycleNo=2");
        assertThat(retried.get("items").size()).isEqualTo(1);
        assertThat(retried.get("items").get(0).get("attemptNo").longValue()).isEqualTo(2);
        assertThat(retried.get("items").get(0).get("workerId").stringValue()).isEqualTo("third");
        assertThat(get(path(id, "attempts") + "?stepId=a&cycleNo=2").get("items").size()).isZero();
    }

    /** Mixed ascending/descending keys must remain correct when many attempts share one timestamp. */
    @Test
    void attemptPagesHandleTiesAndLifetimeNumbersAcrossThreeCycles() throws Exception {
        UUID id = threeFailedCycles();
        jdbc.update("UPDATE step_attempt SET started_at = ? WHERE workflow_id = ?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00.654321Z")), id);
        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            JsonNode page = get(path(id, "attempts") + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            for (JsonNode item : page.get("items")) seen.add(item.get("stepId").stringValue() + item.get("attemptNo").longValue());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").stringValue();
        } while (cursor != null && seen.size() < 10);
        assertThat(seen).containsExactly("a3", "a2", "a1", "b3", "b2", "b1");
        assertThat(cursor).isNull();
        assertThat(get(path(id, "attempts") + "?stepId=a").get("items").size()).isEqualTo(3);
        assertThat(get(path(id, "attempts") + "?cycleNo=2").get("items").size()).isEqualTo(2);
        assertThat(get(path(id, "attempts") + "?stepId=b&cycleNo=3").get("items").size()).isEqualTo(1);
        assertThat(get(path(id, "cycles")).get("items").get(0).get("cycleNo").intValue()).isEqualTo(3);
        JsonNode filtered = get(path(id, "attempts") + "?cycleNo=2&limit=1");
        JsonNode continued = get(path(id, "attempts") + "?cycleNo=2&limit=1&cursor=" + filtered.get("nextCursor").stringValue());
        assertThat(values(filtered, "stepId")).containsExactly("a");
        assertThat(values(continued, "stepId")).containsExactly("b");
        assertThat(continued.get("nextCursor").isNull()).isTrue();
        var cycleNumbers = new ArrayList<Integer>();
        cursor = null;
        do {
            JsonNode page = get(path(id, "cycles") + "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
            cycleNumbers.add(page.get("items").get(0).get("cycleNo").intValue());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").stringValue();
        } while (cursor != null && cycleNumbers.size() < 5);
        assertThat(cycleNumbers).containsExactly(3, 2, 1);
        assertThat(cursor).isNull();
    }

    /** Read requests must preserve every persisted row, including timestamps and live ownership. */
    @Test
    void readsDoNotMutateStateAndDistinguishRunningFailedAndAbandonedAttempts() throws Exception {
        var id = submit("""
                [{"id":"a","actionType":"ECHO"},{"id":"b","actionType":"ECHO"},
                 {"id":"c","actionType":"ECHO","dependsOn":["a","b"]}]
                """);
        store.advance();
        var first = store.claimNext("first").orElseThrow();
        var second = store.claimNext("second").orElseThrow();
        store.fail(first, "STOP", "Permanent failure", false);
        List<String> before = snapshot(id);
        JsonNode summary = get(ROOT + "?status=FAILED").get("items").get(0);
        assertThat(summary.get("runningStepCount").intValue()).isEqualTo(1);
        assertThat(summary.get("retryAllowed").booleanValue()).isFalse();
        JsonNode attempts = get(path(id, "attempts"));
        JsonNode running = attempts.get("items").get(0);
        assertThat(running.get("status").stringValue()).isEqualTo("RUNNING");
        assertThat(running.get("finishedAt").isNull()).isTrue();
        assertThat(running.get("result").isNull()).isTrue();
        assertThat(running.get("error").isNull()).isTrue();
        get(path(id, "cycles")); get(ROOT + "/" + id); get(path(id, "steps/c"));
        assertThat(snapshot(id)).isEqualTo(before);
        jdbc.update("UPDATE step_execution SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE workflow_id = ? AND step_id = ?", id, second.stepId());
        assertThat(store.recoverExpired()).isEqualTo(1);
        JsonNode abandoned = get(path(id, "attempts") + "?stepId=" + second.stepId()).get("items").get(0);
        assertThat(abandoned.get("status").stringValue()).isEqualTo("ABANDONED");
        assertThat(abandoned.get("error").get("code").stringValue()).isEqualTo("LEASE_EXPIRED");
        assertThat(abandoned.get("error").get("retryable").isNull()).isTrue();
        assertThat(abandoned.get("finishedAt").isString()).isTrue();
        assertThat(get(path(id, "attempts") + "?stepId=c").get("items").size()).isZero();
        assertThat(get(path(id, "steps/c")).get("error").get("code").stringValue()).isEqualTo("WORKFLOW_FAILED");
        assertThat(get(ROOT + "?status=FAILED").get("items").get(0).get("retryAllowed").booleanValue()).isTrue();
    }

    /** Existing resources with no matching history return empty pages; missing parents return 404. */
    @Test
    void emptyHistoryIsDifferentFromMissingExecution() throws Exception {
        var id = submitOne();
        for (String suffix : List.of("attempts", "attempts?stepId=unknown", "attempts?cycleNo=99")) {
            JsonNode page = get(path(id, suffix));
            assertThat(page.get("items").size()).isZero();
            assertThat(page.get("nextCursor").isNull()).isTrue();
        }
        for (String suffix : List.of("cycles", "attempts")) {
            assertThat(request("GET", path(UUID.randomUUID(), suffix), null).statusCode()).isEqualTo(404);
            assertInvalid(ROOT + "/not-a-uuid/" + suffix);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=failed", "status=UNKNOWN", "status=", "limit=0", "limit=-1", "limit=201", "limit=x",
            "limit=2147483648", "cursor=", "cursor=bad!"})
    void rejectsInvalidListingQueries(String query) throws Exception { assertInvalid(ROOT + "?" + query); }

    @ParameterizedTest
    @ValueSource(strings = {"cycleNo=", "cycleNo=0", "cycleNo=-1", "cycleNo=x", "cycleNo=2147483648", "stepId=", "stepId=%20",
            "limit=0", "limit=201", "cursor=", "cursor=bad!"})
    void rejectsInvalidAttemptQueries(String query) throws Exception { assertInvalid(path(submitOne(), "attempts") + "?" + query); }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=201", "limit=x", "cursor=", "cursor=bad!"})
    void rejectsInvalidCycleQueries(String query) throws Exception { assertInvalid(path(submitOne(), "cycles") + "?" + query); }

    /** A valid token is still invalid when used with a different resource, parent, or filter. */
    @Test
    void rejectsCursorReuseAcrossCollectionsExecutionsAndFilters() throws Exception {
        UUID id = threeFailedCycles();
        UUID other = submitOne();
        String cycles = get(path(id, "cycles") + "?limit=1").get("nextCursor").stringValue();
        assertInvalid(path(other, "cycles") + "?cursor=" + cycles);
        assertInvalid(path(id, "attempts") + "?cursor=" + cycles);
        assertInvalid(ROOT + "?cursor=" + cycles);
        String attempts = get(path(id, "attempts") + "?stepId=a&limit=1").get("nextCursor").stringValue();
        assertInvalid(path(id, "attempts") + "?stepId=b&cursor=" + attempts);
        assertInvalid(path(id, "attempts") + "?stepId=a&cycleNo=2&cursor=" + attempts);
        assertInvalid(path(other, "attempts") + "?stepId=a&cursor=" + attempts);
        String listing = get(ROOT + "?limit=1").get("nextCursor").stringValue();
        assertInvalid(ROOT + "?status=FAILED&cursor=" + listing);
        // Keeping the original filters is accepted and continues, rather than rejecting every token.
        assertThat(get(path(id, "attempts") + "?stepId=a&limit=1&cursor=" + attempts).get("items").size()).isEqualTo(1);
    }

    /** Structurally valid tokens still need positions that make sense for the selected ordering. */
    @Test
    void rejectsInvalidCursorPositions() throws Exception {
        var codec = new PageCursor(json);
        var id = submitOne();
        assertInvalid(ROOT + "?cursor=" + codec.encode("executions", "", new PageCursor.Position(Instant.now(), "not-a-uuid", 0)));
        assertInvalid(path(id, "cycles") + "?cursor=" + codec.encode("executions/" + id + "/cycles", "", new PageCursor.Position(null, null, 0)));
        assertInvalid(path(id, "attempts") + "?cursor=" + codec.encode("executions/" + id + "/attempts", "[\"\",\"\"]", new PageCursor.Position(null, "a", 1)));
    }

    /** Query parameters are bound values; a quoted step identifier must never become executable SQL. */
    @Test
    void treatsStepFilterAsData() throws Exception {
        var id = submitOne();
        String filter = URLEncoder.encode("a' OR true --", StandardCharsets.UTF_8);
        assertThat(get(path(id, "attempts") + "?stepId=" + filter).get("items").size()).isZero();
        assertThat(get(ROOT).get("items").size()).isEqualTo(1);
    }

    private UUID threeFailedCycles() {
        UUID id = submit("[{\"id\":\"a\",\"actionType\":\"ECHO\"},{\"id\":\"b\",\"actionType\":\"ECHO\"}]");
        for (int cycle = 1; cycle <= 3; cycle++) {
            store.advance();
            var a = store.claimNext("worker-a").orElseThrow();
            var b = store.claimNext("worker-b").orElseThrow();
            store.fail(a, "FAIL_A", "Reported failure", true);
            // a may be RETRYING here; b's permanent error fails the workflow and settles waiting a.
            store.fail(b, "FAIL_B", "Permanent failure", false);
            if (cycle < 3) store.retry(id);
        }
        return id;
    }

    private JsonNode get(String path) throws Exception {
        var response = request("GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return body(response);
    }

    private void assertInvalid(String path) throws Exception {
        var response = request("GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").stringValue()).isEqualTo("INVALID_REQUEST");
        assertThat(body(response).get("error").get("message").stringValue()).isNotBlank();
        assertThat(body(response).get("error").get("details").isArray()).isTrue();
    }

    private List<String> values(JsonNode page, String field) {
        var result = new ArrayList<String>();
        for (JsonNode item : page.get("items")) result.add(item.get(field).stringValue());
        return result;
    }

    private String path(UUID id, String suffix) { return ROOT + "/" + id + "/" + suffix; }

    /** Compare all columns in all five tables, not just the status returned by the API. */
    private List<String> snapshot(UUID id) {
        var rows = new ArrayList<String>();
        for (String table : List.of("workflow_execution", "workflow_cycle", "step_execution", "step_dependency", "step_attempt")) {
            String key = table.equals("workflow_execution") ? "id" : "workflow_id";
            rows.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t WHERE " + key + " = ? ORDER BY row_to_json(t)::text", String.class, id));
        }
        return rows;
    }
}
